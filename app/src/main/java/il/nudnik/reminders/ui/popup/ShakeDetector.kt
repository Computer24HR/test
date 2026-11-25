package il.nudnik.reminders.ui.popup

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Fires [onShake] after 3 strong jolts within 1.5 seconds. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var windowStart = 0L
    private var hits = 0
    private var lastShake = 0L

    fun start() {
        val manager = sm ?: return
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        val now = System.currentTimeMillis()
        if (now - windowStart > WINDOW_MS) {
            windowStart = now
            hits = 0
        }
        hits++
        if (hits >= 3 && now - lastShake > COOLDOWN_MS) {
            lastShake = now
            hits = 0
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD_G = 2.5f
        const val WINDOW_MS = 1500L
        const val COOLDOWN_MS = 2000L
    }
}
