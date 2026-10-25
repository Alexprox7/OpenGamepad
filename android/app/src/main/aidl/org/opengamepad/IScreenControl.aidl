package org.opengamepad;

// Runs inside a Shizuku UserService (shell uid). Transaction 16777114 is the
// destroy code Shizuku expects.
interface IScreenControl {
    void destroy() = 16777114;

    // Turns the display panel on/off without putting the device to sleep.
    boolean setPanelPower(boolean on) = 1;

    // Must be called periodically while the panel is off: without heartbeats
    // the service turns the panel back on by itself. Returns false once the panel
    // is on again (e.g. turned on by the hardware Volume Up key).
    boolean heartbeat() = 2;

    // Confirms a pending Bluetooth pairing request (needs BLUETOOTH_PRIVILEGED, which the
    // shell has). Used to pair with the Switch, which gives up within a few seconds.
    boolean confirmPairing(String address) = 3;

    // Sets the Bluetooth class of device; returns the previous value, or -1 on failure.
    int setDeviceClass(int deviceClass) = 4;
}
