package org.opengamepad.motion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opengamepad.bt.SwitchProProfile
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import org.opengamepad.protocol.Wire
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotionTest {
    @Test
    fun `DSU server listens on its own port`() {
        // Regression: inside DatagramSocket.apply a bare `port` was the socket's (-1) and crashed onResume.
        val port = java.net.DatagramSocket(0).use { it.localPort } // a free port
        val server = DsuServer(ByteArray(6), port)
        server.start()
        try {
            // "listening", not "port busy": the bind on the real port worked.
            assertEquals(org.opengamepad.str(org.opengamepad.R.string.dsu_listening, port), server.status.value)
        } finally {
            server.stop()
        }
    }


    // --- steering ---

    private fun axis(angle: Float, center: Float = 0f, max: Float = 40f, dead: Float = 4f, curve: Float = 1f) =
        Steering.axis(angle, center, max, dead, curve)

    @Test
    fun `dead zone gives zero, max angle gives full deflection`() {
        assertEquals(0f, axis(0f), 0f)
        assertEquals(0f, axis(4f), 0f)
        assertEquals(0f, axis(-3.9f), 0f)
        assertEquals(1f, axis(40f), 1e-6f)
        assertEquals(-1f, axis(-40f), 1e-6f)
        assertEquals(1f, axis(75f), 1e-6f) // past the maximum it stays at full lock
    }

    @Test
    fun `linear response starts at the edge of the dead zone`() {
        assertEquals(0.5f, axis(22f), 1e-5f)       // halfway between 4° and 40°
        assertEquals(-0.25f, axis(-13f), 1e-5f)
        assertTrue(axis(4.5f) > 0f && axis(4.5f) < 0.05f) // no jump when leaving the dead zone
    }

    @Test
    fun `curve above 1 is finer near the center and keeps the ends`() {
        assertEquals(0.25f, axis(22f, curve = 2f), 1e-5f)
        assertEquals(1f, axis(40f, curve = 2f), 1e-6f)
        assertTrue(axis(22f, curve = 0.5f) > 0.7f)
    }

    @Test
    fun `center offsets the angle and wraps around 180`() {
        assertEquals(0f, axis(30f, center = 30f), 0f)
        assertEquals(0.5f, axis(52f, center = 30f), 1e-5f)
        // Center at 170°, current -170°: 20° to the right, not 340° to the left.
        assertEquals(axis(20f), axis(-170f, center = 170f), 1e-5f)
    }

    @Test
    fun `roll and pitch from gravity in the controller frame`() {
        assertEquals(0f, Steering.roll(0f, 9.81f), 1e-4f)                     // upright
        assertEquals(90f, Steering.roll(-9.81f, 0f), 1e-3f)                   // right edge down: steer right
        assertEquals(-30f, Steering.roll(9.81f * 0.5f, 9.81f * 0.866f), 0.1f) // left edge down
        // Leaning the phone back does not change the steering angle.
        val lean = 0.5f
        assertEquals(Steering.roll(-3f, 5f), Steering.roll(-3f * lean, 5f * lean), 1e-3f)
        assertEquals(45f, Steering.pitch(5f, 5f), 1e-3f)                      // top edge away from the player
    }

    // --- shake ---

    private val ms = 1_000_000L

    @Test
    fun `one bump is not a shake, a back-and-forth is`() {
        val d = ShakeDetector(sensitivity = 0.5f) // threshold 1.9 g
        assertFalse(d.onSample(0, 0.1f))
        assertFalse(d.onSample(10 * ms, 2.5f))
        assertFalse(d.onSample(20 * ms, 0.2f))
        assertFalse(d.onSample(400 * ms, 0.2f))
        assertFalse(d.onSample(1000 * ms, 2.5f)) // too late to pair with the first: starts over
        assertFalse(d.onSample(1050 * ms, 0.3f))
        assertTrue(d.onSample(1150 * ms, 2.4f))
    }

    @Test
    fun `cooldown keeps one shake from pressing twice`() {
        val d = ShakeDetector(sensitivity = 0.5f)
        var presses = 0
        var t = 0L
        repeat(10) {
            if (d.onSample(t, 3f)) presses++
            t += 50 * ms
            if (d.onSample(t, 0f)) presses++
            t += 50 * ms
        }
        // 1 s of continuous shaking: one press, then a second once the 600 ms cooldown is over.
        assertEquals(2, presses)
    }

    @Test
    fun `peaks below the threshold need more sensitivity`() {
        fun shakes(sensitivity: Float): Boolean {
            val d = ShakeDetector(sensitivity)
            d.onSample(0, 1.5f)
            d.onSample(50 * ms, 0f)
            return d.onSample(120 * ms, 1.5f)
        }
        assertFalse(shakes(0f))
        assertTrue(shakes(1f))
    }

    // --- type 17 payload ---

    @Test
    fun `motion payload is timestamp, mg and tenths of a degree per second`() {
        val sample = MotionSample(
            timestampNs = 123_456_789_000L,
            accelX = 0.001f, accelY = -1f, accelZ = 0.5f,
            gyroX = 12.34f, gyroY = -2000f, gyroZ = 5000f,
        )
        val bytes = sample.encode()
        assertEquals(MotionSample.PAYLOAD_SIZE, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(123_456_789, b.int)
        assertEquals(1, b.short.toInt())
        assertEquals(-1000, b.short.toInt())
        assertEquals(500, b.short.toInt())
        assertEquals(123, b.short.toInt())
        assertEquals(-20000, b.short.toInt())
        assertEquals(32767, b.short.toInt()) // 5000 °/s is clamped
    }

    @Test
    fun `motion packet uses type 17 and the phone header`() {
        val packet = Wire.frame(Wire.TYPE_MOTION, 7, MotionSample(0, 0f, 0f, 1f, 0f, 0f, 0f).encode())
        assertEquals(23, packet.size)
        assertArrayEquals(byteArrayOf(23, 0, 1, 9, 7, 0, 17), packet.copyOf(7))
        assertEquals(17, Wire.TYPE_MOTION)
    }

    @Test
    fun `timestamp wraps as an unsigned 32-bit microsecond counter`() {
        val bytes = MotionSample(0x1_0000_0005L * 1000, 0f, 0f, 0f, 0f, 0f, 0f).encode()
        assertArrayEquals(byteArrayOf(5, 0, 0, 0), bytes.copyOf(4))
    }

    // --- DSU ---

    @Test
    fun `crc32 is the zlib one`() {
        assertEquals(0xCBF43926L, DsuProtocol.crc32("123456789".toByteArray()))
    }

    private fun le(packet: ByteArray) = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

    private fun assertValidServerPacket(packet: ByteArray, type: Int) {
        assertArrayEquals("DSUS".toByteArray(), packet.copyOf(4))
        val b = le(packet)
        assertEquals(1001, b.getShort(4).toInt())
        assertEquals(packet.size - 16, b.getShort(6).toInt())
        val zeroed = packet.copyOf().also { for (i in 8..11) it[i] = 0 }
        assertEquals(DsuProtocol.crc32(zeroed).toInt(), b.getInt(8))
        assertEquals(type, b.getInt(16))
    }

    private val mac = byteArrayOf(0x98.toByte(), 0xB6.toByte(), 0xE9.toByte(), 1, 2, 3)

    @Test
    fun `version and port info replies`() {
        val version = DsuProtocol.versionReply(42)
        assertEquals(22, version.size)
        assertValidServerPacket(version, DsuProtocol.TYPE_VERSION)
        assertEquals(1001, le(version).getShort(20).toInt())
        assertEquals(42, le(version).getInt(12))

        val port = DsuProtocol.portInfo(42, 0, mac)
        assertEquals(DsuProtocol.PORT_PACKET_SIZE, port.size)
        assertValidServerPacket(port, DsuProtocol.TYPE_PORTS)
        assertArrayEquals(byteArrayOf(0, 2, 2, 2) + mac + byteArrayOf(5, 0), port.copyOfRange(20, 32))
        val empty = DsuProtocol.portInfo(42, 3, mac)
        assertEquals(3, empty[20].toInt())
        assertEquals(0, empty[21].toInt()) // not connected
    }

    @Test
    fun `pad data carries buttons, sticks and converted motion`() {
        val state = ControllerState(
            buttons = Buttons.A or Buttons.DPAD_UP or Buttons.GUIDE,
            leftX = 32767, leftY = -32768, rightTrigger = 200,
        )
        val sample = MotionSample(5_000_000_000L, 0.1f, 0.2f, 0.9f, 10f, 20f, 30f)
        val packet = DsuProtocol.padData(7, 99, mac, state, sample)
        assertEquals(DsuProtocol.PAD_PACKET_SIZE, packet.size)
        assertValidServerPacket(packet, DsuProtocol.TYPE_PAD_DATA)
        val b = le(packet)
        assertEquals(1, packet[31].toInt())                  // connected
        assertEquals(99, b.getInt(32))                       // packet number
        assertEquals(0x10, packet[36].toInt() and 0xFF)      // D-pad up
        assertEquals(0x42, packet[37].toInt() and 0xFF)      // Xbox A = bottom = DSU "B", plus R2 digital
        assertEquals(1, packet[38].toInt())                  // home
        assertEquals(255, packet[40].toInt() and 0xFF)       // left stick right
        assertEquals(0, packet[41].toInt() and 0xFF)         // left stick down
        assertEquals(128, packet[42].toInt() and 0xFF)       // right stick centered
        assertEquals(200, packet[54].toInt() and 0xFF)       // R2 analog
        assertEquals(5_000_000L, b.getLong(68))              // µs
        assertEquals(0.1f, b.getFloat(76), 0f)               // accel X = x
        assertEquals(-0.9f, b.getFloat(80), 0f)              // accel Y = -z: flat face up reads -1
        assertEquals(-0.2f, b.getFloat(84), 0f)              // accel Z = -y
        assertEquals(10f, b.getFloat(88), 0f)                // pitch = x
        assertEquals(30f, b.getFloat(92), 0f)                // yaw = z
        assertEquals(20f, b.getFloat(96), 0f)                // roll = y
    }

    private fun clientPacket(type: Int, body: ByteArray, corrupt: Boolean = false): ByteArray {
        val packet = ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("DSUC".toByteArray()).putShort(1001).putShort((4 + body.size).toShort())
            .putInt(0).putInt(1234).putInt(type).put(body).array()
        val crc = DsuProtocol.crc32(packet).toInt() + if (corrupt) 1 else 0
        le(packet).putInt(8, crc)
        return packet
    }

    @Test
    fun `requests are parsed only with a valid crc`() {
        val request = clientPacket(DsuProtocol.TYPE_PAD_DATA, byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
        val parsed = DsuProtocol.parseRequest(request, request.size)
        assertNotNull(parsed)
        val (type, data) = parsed!!
        assertEquals(DsuProtocol.TYPE_PAD_DATA, type)
        assertEquals(8, data.remaining())
        assertEquals(1, data.get().toInt())

        val bad = clientPacket(DsuProtocol.TYPE_VERSION, ByteArray(0), corrupt = true)
        assertNull(DsuProtocol.parseRequest(bad, bad.size))
        val server = DsuProtocol.versionReply(1) // "DSUS" is not a request
        assertNull(DsuProtocol.parseRequest(server, server.size))
        assertNull(DsuProtocol.parseRequest(request, 10)) // truncated
    }

    // --- settings ---

    @Test
    fun `everything is off by default and settings survive a round trip`() {
        val defaults = MotionSettings.parse(null)
        assertFalse(defaults.steering || defaults.shake || defaults.gyro || defaults.dsu || defaults.pitchY)
        assertEquals(defaults, MotionSettings.parse("not json"))
        val custom = MotionSettings(
            steering = true, maxAngle = 55f, deadZone = 2f, curve = 1.6f, pitchY = true,
            rollCenter = -3.5f, pitchCenter = 41f, shake = true, shakeButton = Buttons.Y,
            shakeSensitivity = 0.8f, gyro = true, dsu = true,
        )
        assertEquals(custom, MotionSettings.parse(custom.toJson()))
    }

    // --- Bluetooth Pro Controller IMU ---

    private fun assertNear(expected: Int, actual: Int) =
        assertTrue("expected ~$expected, got $actual", kotlin.math.abs(expected - actual) <= 1)

    @Test
    fun `pro controller IMU samples follow the factory calibration`() {
        val report = ByteArray(49)
        // Lying flat, still: +1 g on the face normal (Pro Controller z).
        SwitchProProfile.packImu(report, MotionSample(0, 0f, 0f, 1f, 0f, 0f, 0f))
        val b = le(report)
        for (sample in 0 until 3) {
            val at = 12 + sample * 12
            assertEquals(-45, b.getShort(at).toInt())             // accel x at its origin
            assertEquals(-43, b.getShort(at + 2).toInt())
            assertNear(341 + (0x4000 - 341) / 4, b.getShort(at + 4).toInt())
            assertEquals(25, b.getShort(at + 6).toInt())          // gyro at its origin
        }
        // Steering-wheel turn to the left (+z in the phone frame) is +z on the controller too.
        SwitchProProfile.packImu(report, MotionSample(0, 0f, 0f, 0f, 0f, 0f, 93.6f))
        assertNear(-36 + (0x343B + 36) / 10, b.getShort(22).toInt())
        assertEquals(0, report[48].toInt())
    }

    @Test
    fun gyroModeDefaultsToFullAndRoundTrips() {
        assertEquals(GyroMode.FULL, MotionSettings().gyroMode)
        // Settings saved before the option existed: full motion, default shake strength.
        val old = MotionSettings.parse("""{"gyro":true}""")
        assertEquals(GyroMode.FULL, old.gyroMode)
        assertEquals(2.5f, old.shakeGain)
        val full = MotionSettings(gyro = true, gyroMode = GyroMode.FULL, shakeGain = 4f)
        assertEquals(full, MotionSettings.parse(full.toJson()))
        assertEquals(5f, MotionSettings.parse("""{"shakeGain":40}""").shakeGain)
    }

    @Test
    fun axisPresetDefaultsToKartAndCappyFlipsAccelOnly() {
        assertEquals(AxisPreset.KART, MotionSettings().axes)
        assertEquals(AxisPreset.KART, MotionSettings.parse("""{"axes":"JOYCON"}""").axes)
        val cappy = MotionSettings(axes = AxisPreset.CAPPY)
        assertEquals(cappy, MotionSettings.parse(cappy.toJson()))
        val flipped = MotionSample(1, 0.1f, 0.2f, 0.3f, 4f, 5f, 6f).flippedAccelXY()
        assertEquals(MotionSample(1, -0.1f, -0.2f, 0.3f, 4f, 5f, 6f), flipped)
    }

    @Test
    fun kartGainScalesRotationOnly() {
        assertEquals(2f, MotionSettings().kartGain)
        assertEquals(4f, MotionSettings.parse("""{"kartGain":9}""").kartGain)
        val scaled = MotionSample(1, 0.1f, 0.2f, 0.3f, 4f, 5f, 6f).scaledGyro(2f)
        assertEquals(MotionSample(1, 0.1f, 0.2f, 0.3f, 8f, 10f, 12f), scaled)
    }
}
