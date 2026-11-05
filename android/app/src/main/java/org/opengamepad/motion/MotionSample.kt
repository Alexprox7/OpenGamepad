package org.opengamepad.motion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * One accelerometer + gyroscope reading in the controller frame: the phone held in landscape
 * like a gamepad, x towards the right edge, y towards the top edge (shoulder buttons), z out
 * of the screen towards the player. This is Android's sensor frame rotated to the screen.
 *
 * Acceleration is proper acceleration in g, as Android reports it: lying flat face up the
 * phone reads z = +1. Angular velocity is in degrees per second, positive counter-clockwise
 * around each axis (right-hand rule). Seen by a player holding it upright: +x tilts the top
 * edge towards the player, +y moves the right edge away from the player, +z turns it like a
 * steering wheel to the left.
 */
data class MotionSample(
    /** Sensor time in nanoseconds (SensorEvent.timestamp base). */
    val timestampNs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
) {
    /**
     * 16-byte payload of a type-17 packet:
     *   u32 timestamp µs (wraps) | i16 accel x/y/z in mg | i16 gyro x/y/z in 0.1 °/s
     */
    fun encode(): ByteArray =
        ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((timestampNs / 1000).toInt())
            .putShort(clamp16(accelX * 1000f))
            .putShort(clamp16(accelY * 1000f))
            .putShort(clamp16(accelZ * 1000f))
            .putShort(clamp16(gyroX * 10f))
            .putShort(clamp16(gyroY * 10f))
            .putShort(clamp16(gyroZ * 10f))
            .array()

    /** x and y of both vectors flipped: with the sysmodule's z flip this gives all three axes in
     *  the console sensor's convention ([AxisPreset.JOYCON]). */
    fun flippedXY() = copy(accelX = -accelX, accelY = -accelY, gyroX = -gyroX, gyroY = -gyroY)

    companion object {
        const val PAYLOAD_SIZE = 16

        private fun clamp16(value: Float): Short =
            if (value.isNaN()) 0 else value.roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
