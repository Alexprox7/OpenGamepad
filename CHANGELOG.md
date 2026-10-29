# Changelog

## 1.1.0 – unreleased

### New hosts
- **Linux companion**: virtual Xbox 360 controller (uinput, same identity as `xpad`) with rumble,
  macro keyboard, QR code in the terminal, USB through `adb reverse`; x64 and arm64 builds.
- **Nintendo Switch with Atmosphère**: sysmodule that turns each phone into a Pro Controller over Wi‑Fi
  (up to four).
- **Android phones, tablets, Android TV and Google TV** over Bluetooth: buttons now use the
  generic-gamepad slots Android expects, so A/B/X/Y and shoulders map correctly.
- macOS over Bluetooth is documented (SDL/Steam games).
- **iPhone and iPad** (iOS 16+): new SwiftUI app, unsigned IPA for SideStore/AltStore. Wi‑Fi to
  companions and the Switch, LAN search, QR through the Camera app, physical controllers, haptics.

### Android app
- *Cerca* (search) finds companions and Switch consoles on the local network.
- Nintendo button labels when connected to a Switch.
- Experimental Bluetooth Pro Controller profile for stock Switch consoles, with automatic pairing
  confirmation through Shizuku. It needs a phone that can change its Bluetooth device class; Android 16
  removed that API, so on most phones the Switch drops the connection.
- **Motion** (all off by default, *Movimento* card): tilt steering on the left stick (max angle, dead
  zone, curve, re-centre, optional Y from leaning) for every host, a shake gesture that presses a chosen
  button, real gyro to the Switch sysmodule and the Bluetooth Pro Controller, and a DSU (cemuhook) server
  on UDP 26760 for Cemu, Ryujinx, Dolphin, Citra and other emulators. A ⎈ button on the controller
  screen toggles steering.
- **Radial menus**: each wheel opens either while the combo is held or stays open until a pick
  (A, a tap or the combo again confirms, B goes back); slices can be folders with sub-wheels, like reWASD.
- **Amiibo for the Switch** (needs emuiibo): an NFC button and a mappable *⚡ amiibo* wheel list the dumps
  imported in the app and the virtual amiibo on the console; the pick goes on emuiibo's virtual reader.
  No amiibo files are included.

### Companion
- Code split into a shared core and per-OS backends (`pc-companion/Core`, `Windows`, `Linux`).
- Answers LAN discovery on UDP 47801.

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
