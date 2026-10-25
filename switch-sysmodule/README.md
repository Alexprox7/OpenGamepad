# OpenGamepad for Nintendo Switch (Atmosphère)

A background sysmodule that accepts OpenGamepad phones over Wi‑Fi and gives each one a virtual
**Pro Controller** through `hiddbg` HDLS, the interface Atmosphère tools such as sys-botbase use.
Up to four phones at once. It needs custom firmware (Atmosphère): stock consoles cannot load it.

## Install

Copy `atmosphere/contents/0100000000004750/` (from the release zip or `dist/`) to the SD card and
restart. `flags/boot2.flag` starts it at boot; delete it, or use
[ovl-sysmodules](https://github.com/WerWolv/ovl-sysmodules), to keep it off.

Then in the app tap *Cerca* and pick *Nintendo Switch*, or type `<Switch IP>:47800`.

## What it does

- TCP and UDP 47800: the OpenGamepad / Remote Gamepad protocol ([docs/PROTOCOL.md](../docs/PROTOCOL.md)), host code 4.
- UDP 47801: answers `OGP-DISCOVER` with `OGP-HERE 47800 Nintendo Switch`.
- Buttons by position (Xbox A, the bottom button, is Switch B), Guide → Home, analog triggers past
  half press ZL/ZR. Keyboard messages from macros are ignored.
- A phone silent for 10 s is disconnected and its controller removed. After sleep mode the sockets
  are reopened.
- Memory: 3 MiB heap, 3 BSD sessions.

## Build

Install [devkitPro](https://devkitpro.org/wiki/Getting_Started) with `switch-dev`, then:

```sh
make dist   # → dist/atmosphere/contents/0100000000004750/{exefs.nsp, flags/boot2.flag, toolbox.json}
```

devkitPro's makefiles do not support spaces in the path, so build from a folder without spaces.
CI builds it in the `devkitpro/devkita64` container.
