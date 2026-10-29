package org.opengamepad.motion

import org.json.JSONObject
import org.opengamepad.protocol.Buttons

/** What the controller's motion sensor reports to the Switch / Bluetooth Pro Controller. */
enum class GyroMode {
    /**
     * Like shaking a Joy-Con: the controller always reads still and level (no rotation, so no
     * camera or aiming), and only the phone's shakes come through as acceleration, scaled by
     * [MotionSettings.shakeGain] to Joy-Con strength.
     */
    SHAKE,

    /** The phone's full motion: rotation (gyro camera, aiming) and acceleration. */
    FULL,
}

/** Motion options. Everything is off by default. Angles in degrees. */
data class MotionSettings(
    /** Tilt steering: rolling the phone drives the left stick X. */
    val steering: Boolean = false,
    val maxAngle: Float = 40f,
    val deadZone: Float = 3f,
    /** Response exponent: 1 linear, >1 finer control near the center. */
    val curve: Float = 1f,
    /** Also drive the left stick Y by leaning forward/back. */
    val pitchY: Boolean = false,
    val rollCenter: Float = 0f,
    val pitchCenter: Float = 0f,
    /** Shake: presses [shakeButton] for [SHAKE_PRESS_MS]. */
    val shake: Boolean = false,
    val shakeButton: Int = Buttons.RIGHT_SHOULDER,
    val shakeSensitivity: Float = 0.5f,
    /** Real gyro to the Switch sysmodule (type 17) and the Bluetooth Pro Controller. */
    val gyro: Boolean = false,
    val gyroMode: GyroMode = GyroMode.SHAKE,
    /** [GyroMode.SHAKE]: factor applied to the phone's linear acceleration. */
    val shakeGain: Float = 2.5f,
    /** DSU (cemuhook) server for PC emulators on UDP [DSU_PORT]. */
    val dsu: Boolean = false,
) {
    val needsTilt get() = steering || shake
    val needsGyro get() = gyro || dsu

    fun toJson(): String = JSONObject()
        .put("steering", steering)
        .put("maxAngle", maxAngle.toDouble())
        .put("deadZone", deadZone.toDouble())
        .put("curve", curve.toDouble())
        .put("pitchY", pitchY)
        .put("rollCenter", rollCenter.toDouble())
        .put("pitchCenter", pitchCenter.toDouble())
        .put("shake", shake)
        .put("shakeButton", shakeButton)
        .put("shakeSensitivity", shakeSensitivity.toDouble())
        .put("gyro", gyro)
        .put("gyroMode", gyroMode.name)
        .put("shakeGain", shakeGain.toDouble())
        .put("dsu", dsu)
        .toString()

    companion object {
        const val SHAKE_PRESS_MS = 150L
        const val DSU_PORT = 26760

        /** Unknown or broken JSON gives the defaults: motion stays off. */
        fun parse(json: String?): MotionSettings {
            if (json.isNullOrBlank()) return MotionSettings()
            return runCatching {
                val o = JSONObject(json)
                val d = MotionSettings()
                MotionSettings(
                    steering = o.optBoolean("steering", d.steering),
                    maxAngle = o.optDouble("maxAngle", d.maxAngle.toDouble()).toFloat().coerceIn(5f, 90f),
                    deadZone = o.optDouble("deadZone", d.deadZone.toDouble()).toFloat().coerceIn(0f, 30f),
                    curve = o.optDouble("curve", d.curve.toDouble()).toFloat().coerceIn(0.3f, 3f),
                    pitchY = o.optBoolean("pitchY", d.pitchY),
                    rollCenter = o.optDouble("rollCenter", 0.0).toFloat(),
                    pitchCenter = o.optDouble("pitchCenter", 0.0).toFloat(),
                    shake = o.optBoolean("shake", d.shake),
                    shakeButton = o.optInt("shakeButton", d.shakeButton),
                    shakeSensitivity = o.optDouble("shakeSensitivity", d.shakeSensitivity.toDouble()).toFloat().coerceIn(0f, 1f),
                    gyro = o.optBoolean("gyro", d.gyro),
                    gyroMode = runCatching { GyroMode.valueOf(o.optString("gyroMode")) }.getOrDefault(d.gyroMode),
                    shakeGain = o.optDouble("shakeGain", d.shakeGain.toDouble()).toFloat().coerceIn(1f, 5f),
                    dsu = o.optBoolean("dsu", d.dsu),
                )
            }.getOrDefault(MotionSettings())
        }
    }
}
