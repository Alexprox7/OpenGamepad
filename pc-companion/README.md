# OpenGamepad Companion

Receives input from the OpenGamepad app and creates a **virtual Xbox 360 controller** for every
connected phone. It sends rumble (and on Windows the player index) back to the phone and turns the
PC keys of macros into real key presses.

| Folder | Content |
|---|---|
| `Core/` | Shared by every OS: protocol, `CompanionServer` (TCP listener, LAN discovery), `PhoneSession` (handshake, UDP/TCP states, feedback), `AdbBridge` (USB), backend interfaces in `Platform.cs` |
| `Windows/` | WinForms window with QR code; `ViGEmPad` (ViGEmBus) and `KeyboardInjector` (`SendInput` scan codes) |
| `Linux/` | Console app with QR code in the terminal; `UinputPad` and `UinputKeyboard` on `/dev/uinput` |

A new OS needs an `IPlatform` that creates an `IVirtualPad` and an `IKeyboardOutput`, plus a small
`Program.cs`.

## Windows

Requirements: Windows 10/11, .NET 8 Desktop Runtime, [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases);
for USB, Android platform-tools (`adb.exe` in `PATH`, in `C:\platform-tools` or next to the exe).

```bash
cd Windows
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o ../publish/win-x64
```

The Remote Gamepad PC app ships its own `adb`: if both are open, the two ADB servers may disturb each
other. Close the one you are not using.

## Linux

Requirements: a kernel with `uinput` (every mainstream distribution) and access to `/dev/uinput`
(see `70-opengamepad-uinput.rules`); for USB, `adb` in `PATH`. Release builds are self-contained.

```bash
cd Linux
dotnet publish -c Release -r linux-x64 --self-contained -p:PublishSingleFile=true -o ../publish/linux-x64
sudo modprobe uinput && sudo ../publish/linux-x64/opengamepad-companion   # quick test as root
python3 smoke_test.py   # in another terminal: acts as a phone and checks the devices
```

The pad reports itself as `Microsoft X-Box 360 pad` (045E:028E) with the event layout of the kernel
`xpad` driver, so SDL, Steam and Wine games map it as an Xbox controller. Rumble effects uploaded by
games are forwarded to the phone.

## Use

- **Wi‑Fi**: phone and host on the same network; in the app use *Cerca*, scan the QR code or type
  `IP:47800`. Firewall: TCP 47800, UDP 47800 (states) and 47801 (discovery).
- **USB**: enable USB debugging and plug the phone in; the companion keeps
  `adb reverse tcp:47800 tcp:47800` in place by itself. In the app tap *Connetti via USB*.
