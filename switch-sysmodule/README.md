# OpenGamepad for Nintendo Switch (Atmosphère)

A background sysmodule that accepts OpenGamepad phones over Wi‑Fi and gives each one a virtual
**Pro Controller** through `hiddbg` HDLS, the interface Atmosphère tools such as sys-botbase use.
Up to four phones at once. It needs custom firmware (Atmosphère): stock consoles cannot load it.

## Install

Copy `atmosphere/contents/0100000000004750/` (from the release zip or `dist/`) to the SD card, then
start *OpenGamepad* from [ovl-sysmodules](https://github.com/WerWolv/ovl-sysmodules) (Tesla menu).
The package does not start at boot: once it works on your console, ovl-sysmodules can turn that on
(it creates `flags/boot2.flag`). If the console ever misbehaves, a normal restart stops it.

Then in the app tap *Cerca* and pick *Nintendo Switch*, or type `<Switch IP>:47800`.

## What it does

- TCP and UDP 47800: the OpenGamepad / Remote Gamepad protocol ([docs/PROTOCOL.md](../docs/PROTOCOL.md)), host code 4.
- UDP 47801: answers `OGP-DISCOVER` with `OGP-HERE 47800 Nintendo Switch`.
- Buttons by position (Xbox A, the bottom button, is Switch B), Guide → Home, analog triggers past
  half press ZL/ZR. Keyboard messages from macros are ignored.
- Motion (HOS 12.0.0+): with *Giroscopio reale per Switch* on in the app, the phone's accelerometer and
  gyroscope drive the controller's six-axis sensor (games with motion controls, e.g. Mario Kart 8
  Deluxe's tilt steering). Off in the app, the controller rests flat and still.
- A phone silent for 10 s is disconnected and its controller removed. After sleep mode the sockets
  are reopened.
- Sleep and shutdown: registered with PSC (module id 0x4750, after fs, hid and sockets). Before the
  console sleeps it disconnects the phones, removes the controllers, closes its sockets and stops
  writing the log; phones reconnect after wake-up. Without this, spsm times out and the console
  crashes (omm fatal 2165-0001).
- Memory: about 440 KiB in total (256 KiB heap, small socket buffers). The System pool shared by all
  sysmodules has only a few MiB free, and overrunning it crashes the console.

## Diagnostics

Events (sockets, connections, controller creation and the `hiddbg` result codes) are written to
`sdmc:/config/OpenGamepad/log.txt`. The same lines can be read over Wi‑Fi: send `OGP-STATUS` to
UDP 47801 of the console, for example

```sh
python3 -c "import socket;s=socket.socket(2,2);s.settimeout(2);s.sendto(b'OGP-STATUS',('192.168.1.x',47801));print(s.recv(2048).decode())"
```

## Build

Install [devkitPro](https://devkitpro.org/wiki/Getting_Started) with `switch-dev`, then:

```sh
make dist   # → dist/atmosphere/contents/0100000000004750/{exefs.nsp, flags/boot2.flag, toolbox.json}
```

devkitPro's makefiles do not support spaces in the path, so build from a folder without spaces.
CI builds it in the `devkitpro/devkita64` container.
