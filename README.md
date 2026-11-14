<p align="center">
  <img src="docs/images/icon.png" width="96" alt="OpenGamepad icon">
</p>

<h1 align="center">OpenGamepad</h1>

<p align="center">
  Turn your Android phone into a low-latency game controller for Windows, Linux, macOS,
  Android phones, tablets and TVs, and Nintendo Switch.<br>
  Wi‑Fi · USB · Bluetooth · physical controller passthrough · macros and radial menus · dark and OLED themes
</p>

<p align="center">
  <a href="README.it.md">Italiano</a> ·
  <a href="#download">Download</a> ·
  <a href="docs/PROTOCOL.md">Protocol</a> ·
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

<p align="center">
  <img src="docs/images/controller.png" width="720" alt="Controller screen">
</p>

## Features

- **Works with many hosts**
  | Host | Wi‑Fi / USB | Bluetooth (no software on the host) |
  |---|---|---|
  | Windows 10/11 | Companion → Xbox 360 controller (XInput) | Generic gamepad + keyboard |
  | Linux | Companion → Xbox 360 controller (uinput) | Generic gamepad + keyboard |
  | macOS | – | Generic gamepad + keyboard (SDL/Steam games) |
  | Android phone, tablet, Android TV, Google TV | – | Gamepad + keyboard, recognised natively |
  | Nintendo Switch with Atmosphère (CFW) | Sysmodule → Pro Controller | – |
- **Finds hosts by itself** – *Search* lists companions and consoles on the local network;
  QR code and `IP:port` still work.
- **Physical controller passthrough** – plug a controller into the phone (e.g. GameSir G8 Galileo):
  its input is merged with the touch controls and sent to the PC.
- **Rumble and player index** forwarded from the game to the phone.
- **Landscape or vertical** – fixed landscape, reversed, Android's auto-rotate, or a Game Boy-style
  vertical layout (*Settings → Orientation*).
- **Dark and OLED black themes** – a dark anthracite theme by default; *OLED black* uses pure black,
  so OLED pixels stay off. Layouts adapt to phones, tablets and Android handhelds, in landscape and portrait.
- **Five languages** – English, Italian, Chinese (simplified and traditional) and Japanese, in the app
  and in the companions; the system language is used, English otherwise.
- **Screen power saving while you play**
  - *Black layer (OLED)*: black layer + minimum brightness, touch controls stay active.
  - *Panel off* (works on LCD too): turns the display panel off through
    [Shizuku](https://shizuku.rikka.app/) while the physical controller keeps working.
  - Safety: Volume up, holding Back+Start on the controller or leaving the app turns the screen
    back on; if the app stops responding a watchdog does it by itself within 2.5 s.
- **Macros and radial menus**, reWASD style, with a visual editor:
  - trigger combos recorded straight from the controller (e.g. **L3 + R3**);
  - output sequences of controller buttons, **PC keys** (Ctrl, Alt, Win, F1–F24…), stick moves
    and waits, with optional turbo;
  - radial menus with **icons and coloured slices**, chosen with a stick;
  - a combo window keeps the first button of a combo from leaking to the game.

<p align="center">
  <img src="docs/images/wheel.png" width="420" alt="Radial menu">
  <img src="docs/images/wheel-editor.png" width="420" alt="Radial menu editor">
</p>

## Screenshots

<p align="center">
  <img src="docs/images/connect.png" width="420" alt="Connection screen">
  <img src="docs/images/settings.png" width="420" alt="Settings: theme, orientation, screen saver">
  <img src="docs/images/macros.png" width="420" alt="Macros and radial menus">
  <img src="docs/images/motion.png" width="420" alt="Motion settings">
</p>
<p align="center">
  <img src="docs/images/portrait.png" width="260" alt="Vertical Game Boy-style layout">
</p>

## Download

> The app and the companions are available in English, Italian, Chinese and Japanese. Corrections
> from native speakers and new languages are welcome – see [CONTRIBUTING.md](CONTRIBUTING.md).

Get the latest builds from the [Releases](../../releases) page:

- `OpenGamepad-<version>.apk` – the Android app (Android 9 or later).
- `OpenGamepadCompanion-<version>.exe` – the Windows companion (Windows 10/11).
- `OpenGamepadCompanion-<version>-linux-x64.tar.gz` / `-linux-arm64.tar.gz` – the Linux companion.
- `OpenGamepad-Switch-<version>.zip` – the sysmodule for a Switch running Atmosphère.
- `OpenGamepad-<version>.ipa` – unsigned iOS/iPadOS app (16+) to sideload with SideStore or AltStore:
  Wi‑Fi to companions and the Switch, touch controls and physical controllers ([ios/README.md](ios/README.md)).

## Getting started

### Windows (Wi‑Fi and USB)

1. Install the [ViGEmBus driver](https://github.com/nefarius/ViGEmBus/releases) and the
   [.NET 8 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/8.0).
2. Run `OpenGamepadCompanion.exe`. Allow it on private networks when Windows Firewall asks.
3. **Wi‑Fi:** in the app tap *Scan QR* and frame the code shown by the companion
   (or type `IP:47800`).
   **USB:** enable USB debugging on the phone, plug it in and tap *Connect via USB*.
   The companion needs `adb` ([Android platform-tools](https://developer.android.com/tools/releases/platform-tools))
   in `PATH` or in `C:\platform-tools`.

### Linux (Wi‑Fi and USB)

1. Extract the archive for your CPU and let your user create virtual devices (once):
   ```sh
   sudo modprobe uinput
   sudo cp 70-opengamepad-uinput.rules /etc/udev/rules.d/
   sudo udevadm control --reload && sudo udevadm trigger
   sudo usermod -aG input $USER   # then log out and back in
   ```
2. Run `./opengamepad-companion`: it prints a QR code in the terminal. Connect as on Windows
   (QR, *Search* or `IP:47800`; USB needs `adb` in `PATH`). Open TCP 47800 and UDP 47800–47801
   if a firewall is active.

Games see a wired Xbox 360 controller (same identity as the kernel `xpad` driver), with rumble.

### Nintendo Switch (Atmosphère)

1. Copy the `atmosphere` folder from `OpenGamepad-Switch-<version>.zip` to the root of the SD card,
   then start *OpenGamepad* from ovl-sysmodules in the Tesla menu (it does not start at boot until
   you enable that there).
2. Connect the Switch to the same Wi‑Fi as the phone. In the app tap *Search* and pick
   *Nintendo Switch* (or type `<Switch IP>:47800`; the IP is in *System Settings → Internet*).
3. A black and blue Pro Controller appears; up to four phones can connect. The buttons follow the
   Nintendo layout, by position (the bottom button is B).

#### Motion

Tap **⟲** on the controller screen: the virtual controller gets a motion sensor like a Joy-Con, fed by
the phone's accelerometer and gyroscope, so games read shakes and rotation natively (tilt steering in
Mario Kart 8 Deluxe, cap throws in Super Mario Odyssey). **⎈** is a different thing: it turns tilting the
phone into left-stick input, for games without motion controls; the two exclude each other. While the
sensor is on, two buttons pick the axis convention per game: 🏎 for Mario Kart (tilt steering) and
🎩 for Super Mario Odyssey (Cappy throws). 📳 turns the phone's vibration for the game's rumble on
or off (companions and Bluetooth; the Switch does not report rumble for virtual controllers).

#### Amiibo

With [emuiibo](https://github.com/XorTroll/emuiibo) also installed on the Switch, the app can put an
amiibo on a virtual NFC reader: tap **NFC** on the controller screen (or map the **⚡ amiibo** wheel
to any combo in the macro editor) and pick one.

- *Phone*: `.bin` dumps imported in the app's *Amiibo* section (single files, or a folder
  whose sub-folders become groups). The first time one is used, the sysmodule creates a virtual amiibo
  for it in `emuiibo/amiibo/OpenGamepad/` (only its id and UID are sent; saves stay on the console).
- *Switch*: the virtual amiibo already in `sdmc:/emuiibo/amiibo`, with their folders.
- *Remove* takes the amiibo off the reader.

The first time, emuiibo emulation is switched on for you; a game that was already open has to be
restarted once (the app says so), because emuiibo only intercepts games started with emulation on.

> **No amiibo files are included or distributed.** OpenGamepad only reads dumps you make yourself
> from figures you own (or virtual amiibo you already set up in emuiibo). Do not ask for, share or
> publish amiibo dumps in this project's issues or discussions.

See [switch-sysmodule/README.md](switch-sysmodule/README.md) for sleep handling, memory use and logs.
It only runs on consoles with custom firmware: on a stock Switch use the Bluetooth route below,
which needs a phone that can change its Bluetooth device class (most cannot without root).

### Bluetooth (no software on the host)

1. In the app tap *Turn on Bluetooth*, then *Make visible*.
2. On the PC, Mac, phone or TV add a Bluetooth device and pick the phone.
3. Next time the phone reconnects by itself when the app starts.

On Android and Google TV the gamepad and keyboard work out of the box. On macOS the gamepad is
seen by SDL and Steam games; Apple's GameController framework (App Store games) only accepts
Xbox, PlayStation and Switch controllers, and macOS has no virtual Xbox driver for a companion.

While Bluetooth mode is on the app shows a "Gamepad Bluetooth attivo" notification: Android
drops Bluetooth gamepads of apps that go to the background. If the phone was paired before with
another gamepad app, remove it from the PC and pair again: hosts cache the HID descriptor at pairing.

### Panel off on LCD phones

Install and start [Shizuku](https://shizuku.rikka.app/) (wireless debugging or ADB), then tap
*Grant* in *Settings → Screen saver*.

### Emergency: turning the screen back on

From the safest to the last resort:

1. **Press Volume Up.** With the panel off, the privileged service reads the key straight from the
   kernel input device, so it works even if the app is frozen or Android does not deliver the key.
2. **Hold Back + Start for 1.5 s** on the physical controller.
3. **Leave the app** (Home or Recents gesture), or press **Power** twice: standby and wake-up
   restore the screen normally.
4. If the app crashes or is killed, the watchdog turns the panel on within **2.5 s**.
5. From a PC with ADB: `adb shell input keyevent KEYCODE_VOLUME_UP`, or
   `adb shell am force-stop org.opengamepad` (the watchdog does the rest).
6. Holding Power for ~10 s restarts the phone, which always restores the display.

## Compatibility with Remote Gamepad

Over Wi‑Fi the app also works with the PC app of *Remote Gamepad* by SmartFusionLabs: scan its QR
code. PC keys from macros are only delivered by the OpenGamepad Companion or over Bluetooth.
OpenGamepad is an independent project, not affiliated with SmartFusionLabs; compatibility comes from
an independent analysis of the network protocol, documented in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Building from source

| Part | Requirements | Command |
|---|---|---|
| Android app | JDK 17+, Android SDK (platform 36) | `cd android && ./gradlew assembleRelease` |
| Unit tests | same | `cd android && ./gradlew testDebugUnitTest` |
| Windows companion | .NET 8 SDK | `cd pc-companion/Windows && dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o ../publish/win-x64` |
| Linux companion | .NET 8 SDK | `cd pc-companion/Linux && dotnet publish -c Release -r linux-x64 --self-contained -p:PublishSingleFile=true -o ../publish/linux-x64` |
| iOS app | macOS, Xcode 15+, XcodeGen | `cd ios && xcodegen generate` (see [ios/README.md](ios/README.md)) |
| Switch sysmodule | [devkitPro](https://devkitpro.org/wiki/Getting_Started) `switch-dev` | `cd switch-sysmodule && make dist` |

Release APKs are signed with the key described in `android/keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the debug key is used.
See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## Project layout

```
android/          Android app (Kotlin, Jetpack Compose)
pc-companion/     PC companion (C#, .NET 8)
  Core/           shared protocol, sessions, discovery, ADB
  Windows/        WinForms app, ViGEmBus pad, SendInput keyboard
  Linux/          console app, uinput pad and keyboard
switch-sysmodule/ Atmosphère sysmodule (C, libnx): virtual Pro Controller over Wi‑Fi
ios/              iPhone/iPad app (SwiftUI), Wi‑Fi only
docs/             Protocol specification and images
tools/            Helper scripts
```

## Credits

- [ViGEmBus / ViGEm.Client](https://github.com/nefarius/ViGEmBus) (MIT/BSD‑3) – virtual Xbox controller.
- [Shizuku](https://github.com/RikkaApps/Shizuku-API) (Apache‑2.0) and
  [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (Apache‑2.0) – panel off.
- The panel-off technique follows [scrcpy](https://github.com/Genymobile/scrcpy) (Apache‑2.0).
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache‑2.0) – QR scanner.
- [QRCoder](https://github.com/codebude/QRCoder) (MIT) – QR code in the companion.
- [emuiibo](https://github.com/XorTroll/emuiibo) (used through its IPC service, not bundled) –
  amiibo emulation on the Switch.
- [libnx](https://github.com/switchbrew/libnx) (ISC) – Switch sysmodule; the HDLS virtual controller
  approach is the one used by [sys-botbase](https://github.com/olliz0r/sys-botbase) and
  [MissionControl](https://github.com/ndeadly/MissionControl).

OpenGamepad was developed by Alexprox7 with the help of AI tools, including Anthropic's Claude.

## License

[MIT](LICENSE) for the code. The license does not cover the OpenGamepad name and logo.
Nintendo Switch, Joy-Con and amiibo are trademarks of Nintendo; Xbox is a trademark of Microsoft;
other names belong to their owners. OpenGamepad is not affiliated with or endorsed by them.
