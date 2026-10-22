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
}
