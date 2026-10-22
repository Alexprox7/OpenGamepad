# OpenGamepad Companion – Windows

Receives input from the OpenGamepad app and creates a **virtual Xbox 360 controller** for every
connected phone (ViGEmBus). It sends rumble and the player index back to the phone and turns the PC
keys of macros into real key presses (scan codes, read correctly by games).

## Requirements

- Windows 10/11 with the .NET 8 Desktop Runtime.
- [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases) driver.
- For USB: Android platform-tools (`adb.exe` in `PATH`, in `C:\platform-tools` or next to the exe).

## Use

- **Wi‑Fi**: phone and PC on the same network; scan the QR code from the app or type `IP:47800`.
  Allow private networks when Windows Firewall asks.
- **USB**: enable USB debugging and plug the phone in; the companion keeps
  `adb reverse tcp:47800 tcp:47800` in place by itself. In the app tap *Connetti via USB*.

The Remote Gamepad PC app ships its own `adb`: if both are open, the two ADB servers may disturb each
other. Close the one you are not using.

## Build

```bash
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
```

| File | Content |
|---|---|
| `CompanionServer.cs` | TCP listener, one `PhoneSession` per phone |
| `PhoneSession.cs` | Handshake, UDP/TCP states, virtual pad, feedback |
| `AdbBridge.cs` | USB: `adb reverse` for every authorized phone |
| `KeyboardInjector.cs` | Keyboard messages → `SendInput` |
| `MainForm.cs` | Window with QR code, devices and log |
