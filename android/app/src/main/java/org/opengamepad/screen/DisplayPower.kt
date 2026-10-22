package org.opengamepad.screen

import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Switches the physical display panel on/off while the system stays awake, so touch
 * and controller input keep working. Same technique as scrcpy's "turn screen off"
 * (Apache-2.0). Works only from a privileged process (shell uid via Shizuku/ADB).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
object DisplayPower {
    private const val TAG = "DisplayPower"
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    private val surfaceControl by lazy { Class.forName("android.view.SurfaceControl") }
    private var displayControl: Class<*>? = null

    fun set(on: Boolean): Boolean {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val setMode = surfaceControl.getMethod(
            "setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType,
        )
        var applied = false
        for (token in displayTokens()) {
            try {
                setMode.invoke(null, token, mode)
                applied = true
            } catch (e: Exception) {
                Log.e(TAG, "setDisplayPowerMode($mode) failed", e)
            }
        }
        return applied
    }

    private fun displayTokens(): List<IBinder> = try {
        when {
            Build.VERSION.SDK_INT >= 34 -> physicalTokens(loadDisplayControl())
            Build.VERSION.SDK_INT >= 29 -> physicalTokens(surfaceControl)
            else -> listOf(
                surfaceControl.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                    .invoke(null, 0) as IBinder,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot enumerate displays", e)
        emptyList()
    }

    private fun physicalTokens(owner: Class<*>): List<IBinder> {
        val ids = owner.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
        val getToken = owner.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
        return ids.toList().mapNotNull { getToken.invoke(null, it) as IBinder? }
    }

    /** Android 14+: the display token API moved to services.jar (DisplayControl). */
    private fun loadDisplayControl(): Class<*> {
        displayControl?.let { return it }
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(
            null, "/system/framework/services.jar", null, null,
            ClassLoader.getSystemClassLoader(), 0, true, null,
        ) as ClassLoader
        val cls = loader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary = Runtime::class.java.getDeclaredMethod(
            "loadLibrary0", Class::class.java, String::class.java,
        )
        loadLibrary.isAccessible = true
        loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers")
        displayControl = cls
        return cls
    }
}
