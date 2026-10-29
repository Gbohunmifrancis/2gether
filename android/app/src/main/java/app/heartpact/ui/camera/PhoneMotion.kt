package app.heartpact.ui.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import app.heartpact.exercise.MotionLog
import java.io.Closeable
import kotlin.math.sqrt

/**
 * Feeds [log] from the phone's gyroscope (or, without one, its linear acceleration) while the camera
 * counts: a phone propped up reads near zero, a phone being shaken or swung reads well over 1.
 * Times are uptime, the clock the pose frames carry.
 */
class PhoneMotion(context: Context, private val log: MotionLog) : SensorEventListener, Closeable {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val scale = if (sensor?.type == Sensor.TYPE_GYROSCOPE) GYRO_MOVING else ACCEL_MOVING

    init {
        sensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        log.add(SystemClock.uptimeMillis(), sqrt(x * x + y * y + z * z) / scale)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() { sensors?.unregisterListener(this) }

    private companion object {
        /** Radians per second that count as clearly turning. */
        const val GYRO_MOVING = 1.2f
        /** Metres per second squared that count as clearly moving. */
        const val ACCEL_MOVING = 2.5f
    }
}
