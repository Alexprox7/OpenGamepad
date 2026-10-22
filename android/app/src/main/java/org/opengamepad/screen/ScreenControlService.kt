package org.opengamepad.screen

import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.opengamepad.IScreenControl
import kotlin.system.exitProcess

/**
 * Shizuku UserService, runs as shell in its own process and outlives the app
 * (daemon mode) so that it can turn the panel back on if the app crashes or is killed.
 *
 * Safety, independent of the app:
 * - while the panel is off the app must send a heartbeat at least every
 *   [HEARTBEAT_TIMEOUT_MS]; otherwise the watchdog turns the panel on and the service exits;
 * - the hardware Volume Up key is read straight from the kernel input devices
 *   (getevent), so it turns the panel on even if the app or Android's key routing
 *   does not deliver it.
 */
class ScreenControlService : IScreenControl.Stub() {
    @Volatile private var lastHeartbeat = SystemClock.uptimeMillis()
    @Volatile private var panelOff = false
    private var keyWatcher: Process? = null

    init {
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
        Thread(::watchdog, "panel-watchdog").apply { isDaemon = true }.start()
    }

    @Synchronized
    override fun setPanelPower(on: Boolean): Boolean {
        lastHeartbeat = SystemClock.uptimeMillis()
        val ok = DisplayPower.set(on)
        panelOff = ok && !on
        if (panelOff) startKeyWatcher() else stopKeyWatcher()
        Log.i(TAG, "setPanelPower(on=$on) -> $ok")
        return ok
    }

    override fun heartbeat(): Boolean {
        lastHeartbeat = SystemClock.uptimeMillis()
        return panelOff
    }

    /** Watches the raw input devices for the emergency key while the panel is off. */
    private fun startKeyWatcher() {
        if (keyWatcher != null) return
        val process = try {
            ProcessBuilder("getevent", "-ql").redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.e(TAG, "cannot watch hardware keys", e)
            return
        }
        keyWatcher = process
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    // e.g. "/dev/input/event1: EV_KEY       KEY_VOLUMEUP         DOWN"
                    if (lines.any { EMERGENCY_KEY in it && it.trimEnd().endsWith("DOWN") }) {
                        restore("hardware Volume Up")
                    }
                }
            } catch (_: Exception) {
                // process destroyed when the panel came back on
            }
        }, "panel-key-watcher").apply { isDaemon = true }.start()
    }

    private fun stopKeyWatcher() {
        keyWatcher?.destroy()
        keyWatcher = null
    }

    override fun destroy() {
        restore("destroy")
        exitProcess(0)
    }

    private fun watchdog() {
        while (true) {
            SystemClock.sleep(WATCHDOG_INTERVAL_MS)
            if (panelOff && SystemClock.uptimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                restore("heartbeat lost")
                // The app is gone or frozen: nobody will reuse this process.
                exitProcess(0)
            }
        }
    }

    @Synchronized
    private fun restore(reason: String) {
        if (!panelOff) return
        Log.w(TAG, "turning panel back on ($reason)")
        DisplayPower.set(true)
        panelOff = false
        stopKeyWatcher()
    }

    companion object {
        private const val TAG = "ScreenControlService"
        const val HEARTBEAT_TIMEOUT_MS = 2500L
        private const val WATCHDOG_INTERVAL_MS = 250L
        private const val EMERGENCY_KEY = "KEY_VOLUMEUP"
    }
}
