# Changelog

## 1.0.0 – 2026-10-23

First public release.

### Android app
- Xbox-style touch controller with a pitch-black theme.
- Wi‑Fi (QR code or `IP:port`), USB (through the companion) and Bluetooth HID (no PC software).
- Physical controller passthrough, merged with the touch controls.
- Rumble and player index from the PC.
- Screen saver: OLED black mode and panel off through Shizuku, with watchdog and several ways back;
  the hardware Volume Up key is read at kernel level, so it always turns the panel back on.
- Macros and radial menus with a visual editor, combo recording, PC keys, turbo and combo window.
- Compatible with the Remote Gamepad PC app over Wi‑Fi.

### Windows companion
- Virtual Xbox 360 controller per phone through ViGEmBus, with rumble and player LED.
- Wi‑Fi with QR code, USB with automatic `adb reverse`.
- PC keys from macros via `SendInput` scan codes.
