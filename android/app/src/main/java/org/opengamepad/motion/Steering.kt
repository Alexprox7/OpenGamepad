package org.opengamepad.motion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sign

/** Tilt angles and their mapping to a stick axis. Pure functions, unit tested. */
object Steering {
    private const val DEGREES = (180.0 / Math.PI).toFloat()

    /**
     * Steering-wheel angle in degrees from gravity in the controller frame (any unit):
     * 0 held upright, positive with the right edge lowered (turning right). It does not depend
     * on how far the phone leans back, only on the rotation around the screen normal.
     */
    fun roll(gx: Float, gy: Float): Float = atan2(-gx, gy) * DEGREES

    /** Forward tilt in degrees: positive when the top edge leans away from the player. */
    fun pitch(gy: Float, gz: Float): Float = atan2(gz, gy) * DEGREES

    /**
     * Maps an angle to -1..1. [deadZone] degrees around [center] give 0, [maxAngle] gives full
     * deflection; [curve] is the exponent of the response (1 linear, >1 finer near the center).
     */
    fun axis(angle: Float, center: Float, maxAngle: Float, deadZone: Float, curve: Float): Float {
        val offset = wrap(angle - center)
        val magnitude = abs(offset)
        if (magnitude <= deadZone) return 0f
        val span = (maxAngle - deadZone).coerceAtLeast(1f)
        val t = ((magnitude - deadZone) / span).coerceIn(0f, 1f)
        return sign(offset) * t.pow(curve.coerceIn(0.2f, 5f))
    }

    /** Brings an angle difference into -180..180. */
    fun wrap(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
