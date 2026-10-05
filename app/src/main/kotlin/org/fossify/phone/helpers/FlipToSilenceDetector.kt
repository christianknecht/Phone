package org.fossify.phone.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

// gravity (m/s²) on the z axis below which the screen points to the floor; full gravity is about -9.8
private const val FACE_DOWN_Z_THRESHOLD = -8f

// max gravity (m/s²) on x and y while face down, so a phone on its edge or tilted doesn't count
private const val FACE_DOWN_XY_TOLERANCE = 3f

// the phone has to be held at least level (screen not pointing down) to arm the detector. Far from the
// face-down threshold so that the ringing vibration of a phone already lying face down can't arm it
private const val ARMING_Z_THRESHOLD = 0f

// how long the phone has to stay face down before the call is silenced
private const val FACE_DOWN_DURATION_NANOS = 500_000_000L

// low-pass factor isolating gravity when only the raw accelerometer is available
private const val GRAVITY_FILTER_ALPHA = 0.8f

/**
 * Detects the phone being flipped face down while a call rings. It only fires on a real flip: the phone
 * must first be seen with the screen not pointing down (so a phone already lying face down when the call
 * arrives is ignored until it has been turned over once), then stay face down for a short while.
 * One-shot: it stops listening once it fires. Main thread only, driven by the InCallService.
 */
class FlipToSilenceDetector(context: Context, private val onFlip: () -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor = gravitySensor ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var isListening = false
    private var isArmed = false
    private var faceDownSince = 0L
    private var gravity: FloatArray? = null

    fun start() {
        if (isListening || sensor == null) {
            return
        }

        isArmed = false
        faceDownSince = 0L
        gravity = null
        isListening = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
    }

    fun stop() {
        if (isListening) {
            sensorManager?.unregisterListener(this)
            isListening = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isListening) {
            return
        }

        val (x, y, z) = filterGravity(event)
        val isFaceDown = z < FACE_DOWN_Z_THRESHOLD &&
            abs(x) < FACE_DOWN_XY_TOLERANCE &&
            abs(y) < FACE_DOWN_XY_TOLERANCE

        if (!isFaceDown) {
            faceDownSince = 0L
            if (z > ARMING_Z_THRESHOLD) {
                isArmed = true
            }
            return
        }

        if (!isArmed) {
            return
        }

        if (faceDownSince == 0L) {
            faceDownSince = event.timestamp
        } else if (event.timestamp - faceDownSince >= FACE_DOWN_DURATION_NANOS) {
            stop()
            onFlip()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun filterGravity(event: SensorEvent): FloatArray {
        if (gravitySensor != null) {
            return event.values
        }

        val previous = gravity
        val filtered = if (previous == null) {
            event.values.copyOf(event.values.size)
        } else {
            FloatArray(previous.size) { i ->
                GRAVITY_FILTER_ALPHA * previous[i] + (1 - GRAVITY_FILTER_ALPHA) * event.values[i]
            }
        }
        gravity = filtered
        return filtered
    }

    companion object {
        fun isSupported(context: Context): Boolean {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            return sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        }
    }
}
