# OpenGamepad wire protocol

The protocol between the phone and the PC. The base messages are compatible with the Remote Gamepad
PC app (phone protocol 1.9, PC protocol 1.22); OpenGamepad adds a negotiated extension.

All integers are **little‑endian**, except the 64‑bit device id in the hello message (big‑endian).

## Transports

| Mode | Control channel | Controller state |
|---|---|---|
| Wi‑Fi | TCP, phone connects to the PC | UDP, phone → PC port announced in the hello reply |
| USB | TCP to `127.0.0.1:47800` on the phone, forwarded to the PC with `adb reverse` | TCP (same connection) |

The OpenGamepad Companion listens on TCP port **47800** (or a random port if busy).

### QR code

- OpenGamepad Companion: `opengamepad://connect?c=<host1>_<host2>_..._<port>`
- Remote Gamepad PC app: `https://c.remotegamepad.com?c=<host>_<port>`

Hosts are tried in order.

## Framing

Phone → PC (7‑byte header):

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | total length, header included |
| 2 | u8 | major version (1) |
| 3 | u8 | minor version (9) |
| 4 | u16 | sequence number (mod 65535) |
| 6 | u8 | message type |
| 7 | … | payload |

PC → phone (5‑byte header, no sequence):

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | total length, header included |
| 2 | u8 | major version (1) |
| 3 | u8 | minor version (22) |
| 4 | u8 | message type |
| 5 | … | payload |

## Messages

### 0 – Hello

Phone → PC, first message on the TCP connection:

| Type | Field |
|---|---|
| u64 (big‑endian) | random device id, stable across launches |
| `"OGP"` + u8 | *optional* OpenGamepad extension marker and version (1) |

PC → phone reply:

| Type | Field |
|---|---|
| u16 | UDP port for controller states (0 = send states over TCP) |
| u8 | PC operating system: 1 Windows, 2 Linux, 3 macOS |
| `"OGP"` + u8 | *optional*, present only when the phone sent the marker |

The PC may send feedback messages before the reply; the phone skips them until it receives type 0.

### 1 – Controller state

Phone → PC, over UDP (Wi‑Fi) or TCP (USB). Sent on every change and repeated every 200 ms as a keep‑alive.

| Type | Field |
|---|---|
| u16 | buttons (bits below) |
| i16 | left stick X |
| i16 | left stick Y (up is positive) |
| i16 | right stick X |
| i16 | right stick Y (up is positive) |
| u8 | left trigger 0–255 |
| u8 | right trigger 0–255 |

Button bits match `XINPUT_GAMEPAD_*`:

| Bit | Button | Bit | Button |
|---|---|---|---|
| 0x0001 | D‑pad up | 0x0100 | LB |
| 0x0002 | D‑pad down | 0x0200 | RB |
| 0x0004 | D‑pad left | 0x0400 | Guide |
| 0x0008 | D‑pad right | 0x1000 | A |
| 0x0010 | Start | 0x2000 | B |
| 0x0020 | Back | 0x4000 | X |
| 0x0040 | Left stick click | 0x8000 | Y |
| 0x0080 | Right stick click | | |

### 2 – Feedback

PC → phone, over TCP:

| Type | Field |
|---|---|
| u8 | large motor 0–255 |
| u8 | small motor 0–255 |
| u8 | player LED index |

### 16 – Keyboard (OpenGamepad extension)

Phone → PC over TCP, only when both sides sent the `"OGP"` marker. It carries the full current state,
like a USB HID keyboard report:

| Type | Field |
|---|---|
| u8 | HID modifier bits (LCtrl, LShift, LAlt, LGui, RCtrl, RShift, RAlt, RGui) |
| u8 | count *n* (≤ 6) |
| n × u8 | HID keyboard usages of the keys held down |

The companion presses and releases keys with `SendInput` (scan codes) according to the difference with
the previous report.

## Bluetooth HID

Without a companion the phone registers as a Bluetooth HID device (Android `BluetoothHidDevice`) with a
composite descriptor:

- **Report 1 – gamepad** (13 bytes): 16 buttons (A, B, X, Y, LB, RB, Back, Start, L3, R3, Guide), hat
  switch (0 = up, clockwise, 8 = released) + 4 bits padding, X/Y/Z/Rz as signed 16‑bit (Y axes grow
  downwards), Brake/Accelerator 0–255 as triggers.
- **Report 2 – keyboard** (8 bytes): modifiers, reserved byte, six key usages (0x00–0xE7).

Hosts cache the descriptor at pairing time: after changing it the phone must be paired again.
