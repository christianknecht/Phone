package org.fossify.phone.helpers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import org.fossify.phone.extensions.audioManager

/**
 * Plays our own ringtone once the system ringer has been silenced (see the call screening service).
 * Re-implements the parts of the system ringer we take over: audio focus, ringer-mode handling
 * (silent / vibrate / normal), looping playback and vibration. Not thread-safe; the InCallService
 * drives it from the main thread only.
 */
class RingtoneHelper(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    private val audioManager = context.audioManager

    fun start(uri: Uri) {
        // never stack players/vibrations if start is somehow called twice
        stop()

        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return
            AudioManager.RINGER_MODE_VIBRATE -> startVibration()
            else -> {
                startSound(uri)
                if (shouldVibrateWhenRinging()) {
                    startVibration()
                }
            }
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {
        }
        mediaPlayer = null

        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null

        vibrator?.cancel()
        vibrator = null
    }

    private fun startSound(uri: Uri) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)

        if (!playUri(uri, attributes)) {
            // bad or removed URI: fall back to the system default ringtone so the phone is never silent
            val fallback = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            playUri(fallback, attributes)
        }
    }

    private fun playUri(uri: Uri, attributes: AudioAttributes): Boolean {
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(attributes)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (ignored: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            false
        }
    }

    private fun startVibration() {
        val vibrator = getVibrator() ?: return
        this.vibrator = vibrator
        val timings = longArrayOf(0, 1000, 1000)
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
    }

    private fun getVibrator(): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        return vibrator?.takeIf { it.hasVibrator() }
    }

    private fun shouldVibrateWhenRinging(): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 0) == 1
        } catch (ignored: Exception) {
            false
        }
    }
}
