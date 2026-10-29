package org.opengamepad.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.opengamepad.input.GamepadInput
import org.opengamepad.protocol.ControllerState
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.sqrt

/**
 * Reads the phone's motion sensors and turns them into tilt steering and shake presses (into
 * [GamepadInput]) and into raw motion samples for hosts that understand gyro ([onSample]) and
 * for the DSU server. Sensors run only while the activity is resumed and a mode needs them.
 */
class MotionController(
    context: Context,
    private val input: GamepadInput,
    initial: MotionSettings,
    private val save: (MotionSettings) -> Unit,
    mac: ByteArray,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val gravitySensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasTilt = gravitySensor != null || accelSensor != null
    val hasGyro = gyroSensor != null && accelSensor != null

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<MotionSettings> = _settings

    /** Current steering output -1..1 (null while steering is off), for the settings preview. */
    private val _steer = MutableStateFlow<Float?>(null)
    val steer: StateFlow<Float?> = _steer

    val dsu = DsuServer(mac)

    /** Gyro samples for the Switch sysmodule / Bluetooth Pro Controller; null when they stop. */
    var onSample: (MotionSample?) -> Unit = {}

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var resumed = false
    private var registered = emptySet<Sensor>()
    private var gyroActive = false

    // Controller-frame vectors, touched only on the sensor thread.
    private val gravity = FloatArray(3)
    private var haveGravity = false
    private val accel = FloatArray(3)
    private var haveAccel = false
    private val gyro = FloatArray(3)
    private val raw = FloatArray(3)
    private var lastSampleNs = 0L
    private var rotation = Surface.ROTATION_90
    private var rotationCheckedNs = 0L
    @Volatile private var recenterPending = false
    private val shake = ShakeDetector(initial.shakeSensitivity)
    private var output = ControllerState.NEUTRAL

    @Synchronized
    fun update(transform: (MotionSettings) -> MotionSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        // Turning steering (or the Y axis) on takes the current position as the center.
        if ((new.steering && !old.steering) || (new.pitchY && !old.pitchY)) recenterPending = true
        _settings.value = new
        shake.sensitivity = new.shakeSensitivity
        save(new)
        apply()
    }

    /** "Centra": the current tilt becomes straight ahead. */
    fun recenter() {
        recenterPending = true
    }

    fun resume() {
        resumed = true
        apply()
    }

    fun pause() {
        resumed = false
        apply()
    }

    fun release() {
        pause()
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** IPv4 addresses of this phone, to type into the emulator's DSU settings. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
    }.getOrDefault(emptyList())

    /** Registers exactly the sensors the enabled modes need, and starts/stops the DSU server. */
    @Synchronized
    private fun apply() {
        val s = _settings.value
        val wanted = mutableSetOf<Sensor>()
        if (resumed && s.needsTilt) {
            (gravitySensor ?: accelSensor)?.let(wanted::add)
            if (s.shake) accelSensor?.let(wanted::add)
        }
        if (resumed && s.needsGyro && hasGyro) {
            wanted += gyroSensor!!
            wanted += accelSensor!!
        }
        if (resumed && s.dsu) dsu.start() else dsu.stop()
        // Paused or switched off: hosts must not keep the last (possibly tilted) sample.
        val gyroNow = resumed && s.gyro && hasGyro
        if (gyroActive && !gyroNow) onSample(null)
        gyroActive = gyroNow
        if (wanted != registered) {
            sensors?.unregisterListener(this)
            if (wanted.isNotEmpty()) {
                val h = handler ?: HandlerThread("motion").also { it.start(); thread = it }
                    .let { Handler(it.looper) }.also { handler = it }
                for (sensor in wanted) sensors?.registerListener(this, sensor, SAMPLING_US, h)
            }
            registered = wanted
            haveGravity = false
            haveAccel = false
        }
        if (!(resumed && s.steering) || !hasTilt) _steer.value = null
        // Leaving a mode must not leave the stick deflected or the button held.
        if (!resumed || !s.steering) setOutput { it.copy(leftX = 0, leftY = 0) }
        if (!s.pitchY) setOutput { it.copy(leftY = 0) }
        if (!resumed || !s.shake) setOutput { ControllerState(leftX = it.leftX, leftY = it.leftY) }
    }

    private fun setOutput(transform: (ControllerState) -> ControllerState) {
        synchronized(this) {
            val next = transform(output)
            if (next == output) return
            output = next
        }
        input.motion { output }
    }

    override fun onSensorChanged(event: SensorEvent) {
        refreshRotation(event.timestamp)
        toController(event.values, raw)
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                raw.copyInto(gravity)
                haveGravity = true
                onTilt()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) accel[i] = raw[i] / SensorManager.GRAVITY_EARTH
                haveAccel = true
                if (gravitySensor == null) {
                    // No fused gravity sensor: low-pass the accelerometer.
                    if (!haveGravity) raw.copyInto(gravity)
                    for (i in 0..2) gravity[i] += (raw[i] - gravity[i]) * 0.1f
                    haveGravity = true
                    onTilt()
                }
                onShake(event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                for (i in 0..2) gyro[i] = raw[i] * RAD_TO_DEG
                onGyro(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun onTilt() {
        val s = _settings.value
        if (!s.steering) return
        val roll = Steering.roll(gravity[0], gravity[1])
        val pitch = Steering.pitch(gravity[1], gravity[2])
        if (recenterPending) {
            recenterPending = false
            update { it.copy(rollCenter = roll, pitchCenter = pitch) }
            return
        }
        val x = Steering.axis(roll, s.rollCenter, s.maxAngle, s.deadZone, s.curve)
        val y = if (s.pitchY) Steering.axis(pitch, s.pitchCenter, s.maxAngle, s.deadZone, s.curve) else 0f
        _steer.value = x
        setOutput { it.copy(leftX = ControllerState.axis(x), leftY = ControllerState.axis(y)) }
    }

    private fun onShake(timestampNs: Long) {
        val s = _settings.value
        if (!s.shake || !haveGravity) return
        // Linear acceleration: the accelerometer minus gravity, in g.
        val g = SensorManager.GRAVITY_EARTH
        var sum = 0f
        for (i in 0..2) {
            val d = accel[i] - gravity[i] / g
            sum += d * d
        }
        if (!shake.onSample(timestampNs, sqrt(sum))) return
        val button = s.shakeButton
        setOutput { it.with(button, true) }
        handler?.postDelayed({ setOutput { it.with(button, false) } }, MotionSettings.SHAKE_PRESS_MS)
    }

    private fun onGyro(timestampNs: Long) {
        val s = _settings.value
        if (!haveAccel || timestampNs - lastSampleNs < MIN_SAMPLE_INTERVAL_NS) return
        lastSampleNs = timestampNs
        val sample = MotionSample(timestampNs, accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2])
        if (s.gyro) onSample(sample)
        if (s.dsu) dsu.publish(sample)
    }

    /** Buttons and sticks for the DSU server, from the final output (macros included). */
    fun onPadState(state: ControllerState) {
        dsu.state = state
    }

    @Suppress("DEPRECATION")
    private fun refreshRotation(nowNs: Long) {
        if (nowNs - rotationCheckedNs < 250_000_000L) return
        rotationCheckedNs = nowNs
        rotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_90
    }

    /** Android sensor axes (portrait) → controller frame for the current screen rotation. */
    private fun toController(v: FloatArray, out: FloatArray) {
        val x = v[0]
        val y = v[1]
        when (rotation) {
            Surface.ROTATION_90 -> { out[0] = -y; out[1] = x }
            Surface.ROTATION_270 -> { out[0] = y; out[1] = -x }
            Surface.ROTATION_180 -> { out[0] = -x; out[1] = -y }
            else -> { out[0] = x; out[1] = y }
        }
        out[2] = v[2]
    }

    private companion object {
        const val SAMPLING_US = 10_000 // 100 Hz
        const val MIN_SAMPLE_INTERVAL_NS = 9_000_000L
        const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }
}
