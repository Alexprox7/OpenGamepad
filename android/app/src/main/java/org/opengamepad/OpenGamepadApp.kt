package org.opengamepad

import android.app.Application
import org.opengamepad.bt.BluetoothGamepad

class OpenGamepadApp : Application() {
    /** One HID registration per process: it outlives the activity while Bluetooth mode is on. */
    val bluetooth: BluetoothGamepad by lazy { BluetoothGamepad(this) }
}
