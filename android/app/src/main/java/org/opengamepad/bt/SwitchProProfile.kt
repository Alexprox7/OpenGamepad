package org.opengamepad.bt

import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.opengamepad.motion.MotionSample
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Nintendo Switch Pro Controller over Bluetooth, written from the public protocol
 * documentation (dekuNukem/Nintendo_Switch_Reverse_Engineering).
 *
 * The Switch sends subcommands in output report 0x01 and expects 0x21 replies; once it
 * selects the standard full mode it reads input report 0x30 at ~66 Hz. Buttons follow the
 * position of the Xbox layout (bottom = B, right = A, left = Y, top = X).
 */
class SwitchProProfile(private val address: ByteArray) : HidProfile {
    override val id = ID

    override val sdp = BluetoothHidDeviceAppSdpSettings(
        "Wireless Gamepad",
        "Gamepad",
        "Nintendo",
        // HIDDeviceSubclass uses the class-of-device minor layout: gamepad = 0b0010 << 2.
        // (Android's SUBCLASS2_GAMEPAD constant is 0x02, not what real controllers send.)
        0x08,
        DESCRIPTOR,
    )

    /** The Switch only lists controllers called like this in "Change Grip/Order". */
    override val deviceName = "Pro Controller"

    private val _player = MutableStateFlow(0)
    /** Player number shown by the Switch's lights (0 = none). */
    val player: StateFlow<Int> = _player

    private val _rumble = MutableStateFlow(false)
    val rumble: StateFlow<Boolean> = _rumble

    @Volatile private var link: HidLink? = null
    @Volatile private var state = ControllerState.NEUTRAL
    @Volatile private var motion: MotionSample? = null
    private var timer = 0
    private var scheduler: ScheduledExecutorService? = null
    private val spi = SwitchSpi()

    override fun attach(link: HidLink) {
        this.link = link
        timer = 0
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate(::sendFullReport, 0, REPORT_PERIOD_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun detach() {
        scheduler?.shutdownNow()
        scheduler = null
        link = null
        _player.value = 0
        _rumble.value = false
    }

    override fun onState(state: ControllerState) {
        this.state = state
    }

    override fun onMotion(sample: MotionSample?) {
        motion = sample
    }

    override fun onGetReport(reportId: Int): ByteArray = fullReport()

    override fun onOutputReport(reportId: Int, data: ByteArray) {
        when (reportId) {
            0x01 -> {
                if (data.size < 10) return
                updateRumble(data.copyOfRange(1, 9))
                handleSubcommand(data[9].toInt() and 0xFF, data.copyOfRange(10, data.size))
            }
            0x10 -> if (data.size >= 9) updateRumble(data.copyOfRange(1, 9))
            else -> Log.d(TAG, "output report 0x%02x ignored".format(reportId))
        }
    }

    private fun handleSubcommand(command: Int, args: ByteArray) {
        Log.i(TAG, "subcommand 0x%02x %s".format(command, args.take(8).joinToString(" ") { "%02x".format(it) }))
        val reply = when (command) {
            0x02 -> Reply(0x82, byteArrayOf(0x04, 0x00, 0x03, 0x02) + address + byteArrayOf(0x01, 0x01))
            0x10 -> {
                val offset = (args[0].toInt() and 0xFF) or ((args[1].toInt() and 0xFF) shl 8) or
                    ((args[2].toInt() and 0xFF) shl 16) or ((args[3].toInt() and 0xFF) shl 24)
                val size = args[4].toInt() and 0xFF
                Reply(0x90, args.copyOfRange(0, 5) + spi.read(offset, size))
            }
            0x04 -> Reply(0x83, ByteArray(14))
            0x21 -> Reply(0xA0, mcuConfigReply())
            0x30 -> {
                _player.value = playerFromLights(args.firstOrNull()?.toInt() ?: 0)
                Reply(0x80)
            }
            // 0x03 report mode, 0x08 shipment, 0x22 MCU, 0x38 home light, 0x40 IMU, 0x48 vibration…
            else -> Reply(0x80)
        }
        send(0x21, baseReport().also { report ->
            report[12] = reply.ack.toByte()
            report[13] = command.toByte()
            reply.data.copyInto(report, 14, 0, minOf(reply.data.size, report.size - 14))
        })
    }

    private class Reply(val ack: Int, val data: ByteArray = ByteArray(0))

    private fun sendFullReport() {
        if (link == null) return
        send(0x30, fullReport())
    }

    /** Without gyro passthrough the IMU samples stay at zero, as before. */
    private fun fullReport() = baseReport().also { report -> motion?.let { packImu(report, it) } }

    private fun send(reportId: Int, data: ByteArray) {
        try {
            link?.send(reportId, data)
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
        }
    }

    /** Common part of 0x21/0x30: timer, battery, buttons, sticks, vibrator byte. */
    @Synchronized
    private fun baseReport(): ByteArray {
        val s = state
        val report = ByteArray(REPORT_SIZE)
        report[0] = (timer++ and 0xFF).toByte()
        report[1] = 0x90.toByte() // battery full, Pro Controller connection
        var right = 0
        var shared = 0
        var left = 0
        // Positional mapping: Xbox A (bottom) = Switch B, Xbox B (right) = Switch A…
        if (s.isPressed(Buttons.X)) right = right or 0x01 // Y
        if (s.isPressed(Buttons.Y)) right = right or 0x02 // X
        if (s.isPressed(Buttons.A)) right = right or 0x04 // B
        if (s.isPressed(Buttons.B)) right = right or 0x08 // A
        if (s.isPressed(Buttons.RIGHT_SHOULDER)) right = right or 0x40 // R
        if (s.rightTrigger > TRIGGER_THRESHOLD) right = right or 0x80 // ZR
        if (s.isPressed(Buttons.BACK)) shared = shared or 0x01 // -
        if (s.isPressed(Buttons.START)) shared = shared or 0x02 // +
        if (s.isPressed(Buttons.RIGHT_THUMB)) shared = shared or 0x04
        if (s.isPressed(Buttons.LEFT_THUMB)) shared = shared or 0x08
        if (s.isPressed(Buttons.GUIDE)) shared = shared or 0x10 // Home
        if (s.isPressed(Buttons.DPAD_DOWN)) left = left or 0x01
        if (s.isPressed(Buttons.DPAD_UP)) left = left or 0x02
        if (s.isPressed(Buttons.DPAD_RIGHT)) left = left or 0x04
        if (s.isPressed(Buttons.DPAD_LEFT)) left = left or 0x08
        if (s.isPressed(Buttons.LEFT_SHOULDER)) left = left or 0x40 // L
        if (s.leftTrigger > TRIGGER_THRESHOLD) left = left or 0x80 // ZL
        report[2] = right.toByte()
        report[3] = shared.toByte()
        report[4] = left.toByte()
        packStick(report, 5, s.leftX, s.leftY)
        packStick(report, 8, s.rightX, s.rightY)
        report[11] = 0x80.toByte()
        return report
    }

    /** Player lights bitmask (bits 0-3 on, 4-7 flashing) → player number. */
    private fun playerFromLights(bits: Int): Int {
        val on = bits and 0x0F
        return when {
            on == 0 -> 0
            on and (on - 1) == 0 -> Integer.numberOfTrailingZeros(on) + 1 // single light
            else -> Integer.bitCount(on) // 5-8 are shown with several lights
        }
    }

    private fun updateRumble(data: ByteArray) {
        // Each side encodes frequency/amplitude; amplitude 0 is "00 01 40 40".
        val idle = data.all { it == 0.toByte() } ||
            (0 until 2).all { i -> data[i * 4 + 1].toInt() and 0xFE == 0 && data[i * 4 + 3].toInt() and 0xFF == 0x40 }
        _rumble.value = !idle
    }

    private fun mcuConfigReply(): ByteArray {
        val reply = ByteArray(34)
        byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x00, 0x08, 0x00, 0x1B, 0x01).copyInto(reply)
        reply[33] = crc8(reply, 0, 33)
        return reply
    }

    companion object {
        const val ID = "switch"
        private const val TAG = "SwitchPro"
        private const val REPORT_SIZE = 49
        private const val REPORT_PERIOD_MS = 15L
        private const val TRIGGER_THRESHOLD = 128

        /** Stick calibration served from SPI: 12-bit center and range used by [packStick]. */
        const val STICK_CENTER = 2048
        const val STICK_RANGE = 1400

        internal fun packStick(target: ByteArray, offset: Int, x: Short, y: Short) {
            val rawX = (STICK_CENTER + x / 32768f * STICK_RANGE).roundToInt().coerceIn(0, 4095)
            val rawY = (STICK_CENTER + y / 32768f * STICK_RANGE).roundToInt().coerceIn(0, 4095)
            pack12(target, offset, rawX, rawY)
        }

        // Factory IMU calibration served from SPI 0x6020: accel origin/sensitivity, gyro origin/sensitivity.
        private val ACCEL_ORIGIN = intArrayOf(-45, -43, 341)
        private const val ACCEL_SENSITIVITY = 0x4000
        private val GYRO_ORIGIN = intArrayOf(25, -35, -36)
        private const val GYRO_SENSITIVITY = 0x343B

        /**
         * Report 0x30 bytes 12-47: three IMU samples (accel x/y/z, gyro x/y/z, i16 each), filled
         * with the latest reading. Pro Controller sensor axes (dekuNukem's IMU notes): x towards
         * the top edge, y towards the left, z out of the face, accel +1 g up at rest.
         * Raw values invert the console's calibration: g = (raw - origin) * 4 / (sensitivity -
         * origin), °/s = (raw - origin) * 936 / (sensitivity - origin).
         */
        internal fun packImu(report: ByteArray, m: MotionSample) {
            val accel = floatArrayOf(m.accelY, -m.accelX, m.accelZ)
            val gyro = floatArrayOf(m.gyroY, -m.gyroX, m.gyroZ)
            val raw = IntArray(6)
            for (i in 0..2) {
                raw[i] = (ACCEL_ORIGIN[i] + accel[i] * (ACCEL_SENSITIVITY - ACCEL_ORIGIN[i]) / 4f).roundToInt()
                raw[3 + i] = (GYRO_ORIGIN[i] + gyro[i] * (GYRO_SENSITIVITY - GYRO_ORIGIN[i]) / 936f).roundToInt()
            }
            for (sample in 0 until 3) {
                for (i in 0 until 6) {
                    val value = raw[i].coerceIn(-32768, 32767)
                    val at = 12 + sample * 12 + i * 2
                    report[at] = (value and 0xFF).toByte()
                    report[at + 1] = (value shr 8).toByte()
                }
            }
        }

        internal fun pack12(target: ByteArray, offset: Int, x: Int, y: Int) {
            target[offset] = (x and 0xFF).toByte()
            target[offset + 1] = (((x shr 8) and 0x0F) or ((y and 0x0F) shl 4)).toByte()
            target[offset + 2] = ((y shr 4) and 0xFF).toByte()
        }

        private fun crc8(data: ByteArray, from: Int, length: Int): Byte {
            var crc = 0
            for (i in from until from + length) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc.toByte()
        }

        /** HID report descriptor of the Pro Controller. */
        val DESCRIPTOR: ByteArray = intArrayOf(
            0x05, 0x01, 0x15, 0x00, 0x09, 0x04, 0xA1, 0x01, 0x85, 0x30, 0x05, 0x01, 0x05, 0x09,
            0x19, 0x01, 0x29, 0x0A, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0A, 0x55, 0x00,
            0x65, 0x00, 0x81, 0x02, 0x05, 0x09, 0x19, 0x0B, 0x29, 0x0E, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x04, 0x81, 0x02, 0x75, 0x01, 0x95, 0x02, 0x81, 0x03, 0x0B, 0x01,
            0x00, 0x01, 0x00, 0xA1, 0x00, 0x0B, 0x30, 0x00, 0x01, 0x00, 0x0B, 0x31, 0x00, 0x01,
            0x00, 0x0B, 0x32, 0x00, 0x01, 0x00, 0x0B, 0x35, 0x00, 0x01, 0x00, 0x15, 0x00, 0x27,
            0xFF, 0xFF, 0x00, 0x00, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02, 0xC0, 0x0B, 0x39, 0x00,
            0x01, 0x00, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75,
            0x04, 0x95, 0x01, 0x81, 0x02, 0x05, 0x09, 0x19, 0x0F, 0x29, 0x12, 0x15, 0x00, 0x25,
            0x01, 0x75, 0x01, 0x95, 0x04, 0x81, 0x02, 0x75, 0x08, 0x95, 0x34, 0x81, 0x03, 0x06,
            0x00, 0xFF, 0x85, 0x21, 0x09, 0x01, 0x75, 0x08, 0x95, 0x3F, 0x81, 0x03, 0x85, 0x81,
            0x09, 0x02, 0x75, 0x08, 0x95, 0x3F, 0x81, 0x03, 0x85, 0x01, 0x09, 0x03, 0x75, 0x08,
            0x95, 0x3F, 0x91, 0x83, 0x85, 0x10, 0x09, 0x04, 0x75, 0x08, 0x95, 0x3F, 0x91, 0x83,
            0x85, 0x80, 0x09, 0x05, 0x75, 0x08, 0x95, 0x3F, 0x91, 0x83, 0x85, 0x82, 0x09, 0x06,
            0x75, 0x08, 0x95, 0x3F, 0x91, 0x83, 0xC0,
        ).let { ints -> ByteArray(ints.size) { ints[it].toByte() } }
    }
}

/**
 * The parts of the controller's SPI flash the Switch reads while pairing: serial,
 * IMU and stick calibration, stick parameters and colours. Everything else reads 0xFF.
 */
internal class SwitchSpi {
    private val memory = ByteArray(0x10000) { 0xFF.toByte() }

    init {
        // 0x6020 IMU factory calibration
        put(0x6020, "D3 FF D5 FF 55 01 00 40 00 40 00 40 19 00 DD FF DC FF 3B 34 3B 34 3B 34")
        // 0x603D stick factory calibration: left = max-above, center, min-below; right = center, min, max
        val c = SwitchProProfile.STICK_CENTER
        val r = SwitchProProfile.STICK_RANGE
        val left = ByteArray(9).also {
            SwitchProProfile.pack12(it, 0, r, r)
            SwitchProProfile.pack12(it, 3, c, c)
            SwitchProProfile.pack12(it, 6, r, r)
        }
        val right = ByteArray(9).also {
            SwitchProProfile.pack12(it, 0, c, c)
            SwitchProProfile.pack12(it, 3, r, r)
            SwitchProProfile.pack12(it, 6, r, r)
        }
        (left + right).copyInto(memory, 0x603D)
        // 0x6050 colours: body, buttons, left grip, right grip (OpenGamepad black and blue)
        put(0x6050, "1A 1A 1A 7C 9C FF 1A 1A 1A 1A 1A 1A")
        // 0x6080 6-axis horizontal offsets, then stick parameters for both sticks
        put(0x6080, "50 FD 00 00 C6 0F")
        put(0x6086, "0F 30 61 96 30 F3 D4 14 54 41 15 54 C7 79 9C 33 36 63")
        put(0x6098, "0F 30 61 96 30 F3 D4 14 54 41 15 54 C7 79 9C 33 36 63")
    }

    private fun put(offset: Int, hex: String) {
        hex.split(' ').map { it.toInt(16).toByte() }.toByteArray().copyInto(memory, offset)
    }

    fun read(offset: Int, size: Int): ByteArray {
        if (offset < 0 || offset + size > memory.size) return ByteArray(size) { 0xFF.toByte() }
        return memory.copyOfRange(offset, offset + size)
    }
}
