package org.opengamepad.screen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opengamepad.BuildConfig
import org.opengamepad.IScreenControl
import rikka.shizuku.Shizuku

/** App side of the panel-off feature: binds [ScreenControlService] through Shizuku. */
class ShizukuScreen(private val context: Context) {

    enum class Availability { NOT_RUNNING, NO_PERMISSION, READY }

    private var service: IScreenControl? = null
    private var pending = CompletableDeferred<IScreenControl>()

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ScreenControlService::class.java.name),
    )
        .daemon(true)
        .processNameSuffix("screen")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val bound = IScreenControl.Stub.asInterface(binder)
            service = bound
            pending.complete(bound)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            pending = CompletableDeferred()
        }
    }

    fun availability(): Availability = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> Availability.NOT_RUNNING
        Shizuku.isPreV11() -> Availability.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Availability.NO_PERMISSION
        else -> Availability.READY
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private suspend fun bound(): IScreenControl? {
        service?.let { return it }
        if (availability() != Availability.READY) return null
        Shizuku.bindUserService(args, connection)
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { pending.await() }
    }

    /** Returns false if Shizuku is unavailable or the device refused the call. */
    suspend fun setPanel(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            bound()?.setPanelPower(on) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "setPanelPower($on) failed", e)
            false
        }
    }

    /** False when the service has turned the panel back on by itself; null if unreachable. */
    fun heartbeat(): Boolean? = runCatching { service?.heartbeat() }.getOrNull()

    /** Turns the panel back on and stops the privileged process. */
    fun release() {
        val current = service ?: return
        runCatching { current.setPanelPower(true) }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        pending = CompletableDeferred()
    }

    companion object {
        private const val TAG = "ShizukuScreen"
        private const val REQUEST_CODE = 4242
        private const val BIND_TIMEOUT_MS = 5000L
    }
}
