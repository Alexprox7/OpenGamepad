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
- Motion (HOS 12.0.0+): with the motion sensor on in the app (⟲ on the controller screen), the phone's
  accelerometer and gyroscope drive the controller's six-axis sensor like a Joy-Con's: tilt steering in
  Mario Kart 8 Deluxe, cap throws in Super Mario Odyssey. Off in the app, the controller rests flat and
  still. The controller is created through `ApplyHdlsStateList`, the only way HID gives a virtual
  controller a sensor games read (see [docs/PROTOCOL.md](../docs/PROTOCOL.md)); a controller left behind
  when the module is stopped from ovl-sysmodules is removed at the next start, or by a reboot.
- `tools/motion-test`: homebrew app that shows what games read from every controller's motion sensor.
- A phone silent for 10 s is disconnected and its controller removed. After sleep mode the sockets
  are reopened.
- Sleep and shutdown: registered with PSC (module id 0x4750, depending on fs only, like sys-con).
  Before the console sleeps it removes the controllers, lets the amiibo thread finish and stops
  writing the log, with no socket calls (the network may already be asleep); the sockets are closed
  and reopened after wake-up, and phones reconnect. Without PSC handling spsm timed out (omm fatal
  2165-0001); with extra hid and socket dependencies the console froze going to sleep.
- Memory: about 500 KiB in total (256 KiB heap, small socket buffers). The System pool shared by all
  sysmodules has only a few MiB free, and overrunning it crashes the console.

## Amiibo

With emuiibo installed, the phone can list the virtual amiibo in `sdmc:/emuiibo/amiibo` (sub-folders
included) and make one active, or send the id and UID of a dump stored on the phone: the module creates
`emuiibo/amiibo/OpenGamepad/[group/]name/` once and activates it. It talks to emuiibo's `emuiibo` IPC
service (commands 2, 3, 5, 8 and 9) and checks first that the service exists, so a console without
emuiibo only gets an error message in the app. emuiibo only intercepts games started while its emulation
is on: the first activation turns it on (emuiibo remembers it), and if a game was already running
without interception the app asks to restart it once. Picking another amiibo lifts the previous one
off the reader for 250 ms first, so the game sees a new tap. No amiibo files are included or distributed.

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
