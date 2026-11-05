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
| u8 | host: 1 Windows, 2 Linux, 3 macOS, 4 Nintendo Switch (OpenGamepad sysmodule) |
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
| u8 | player LED index (0–3; 255 = the host has no player numbers, e.g. Linux) |

### 16 – Keyboard (OpenGamepad extension)

Phone → PC over TCP, only when both sides sent the `"OGP"` marker. It carries the full current state,
like a USB HID keyboard report:

| Type | Field |
|---|---|
| u8 | HID modifier bits (LCtrl, LShift, LAlt, LGui, RCtrl, RShift, RAlt, RGui) |
| u8 | count *n* (≤ 6) |
| n × u8 | HID keyboard usages of the keys held down |

The companion presses and releases keys according to the difference with the previous report
(`SendInput` scan codes on Windows, a uinput keyboard on Linux). The Switch sysmodule ignores it.

### 17 – Motion (OpenGamepad extension)

Phone → host over **UDP**, only to hosts that answered the hello with the `"OGP"` marker and only while
the motion sensor is on in the app (⟲ on the controller screen), at up to ~200 Hz (one packet per
gyroscope sample, at least 4.5 ms apart). Not sent over USB. 16‑byte payload:

| Type | Field |
|---|---|
| u32 | sensor timestamp in µs (phone clock, wraps) |
| i16 ×3 | acceleration x, y, z in mg (1/1000 g) |
| i16 ×3 | angular velocity x, y, z in 0.1 °/s |

Axes are the **controller frame**: the phone held in landscape like a gamepad, x towards the right edge,
y towards the top edge (shoulder buttons), z out of the screen towards the player — Android's sensor axes
rotated to the current screen orientation. Acceleration is proper acceleration as Android reports it
(lying flat face up: z = +1000). Rotations are counter‑clockwise positive around each axis (right‑hand
rule): +x tilts the top edge towards the player, +y moves the right edge away, +z turns the phone like a
steering wheel to the left.

The Windows and Linux companions ignore it (Xbox 360 and uinput pads have no gyro).

### 18–22 – Amiibo (OpenGamepad extension, Switch sysmodule)

Over TCP, only with the `"OGP"` marker. The sysmodule drives
[emuiibo](https://github.com/XorTroll/emuiibo) through its IPC service; the companions ignore these types.

| Type | Direction | Payload |
|---|---|---|
| 18 list request | phone → console | u16 offset |
| 19 list | console → phone | u16 total, u16 offset, u8 flags (bit 0: emuiibo running), u8 count, count × (u8 length, UTF‑8 path relative to `sdmc:/emuiibo/amiibo`) |
| 20 activate | phone → console | u8 source, then: source 0 = UTF‑8 path from the list; source 1 = u8 id[8], u8 uid[7], u8 name length, name, u8 group length, group |
| 21 status | console → phone | u8 result (0 active, 1 removed, 2 emuiibo missing, 3 error, 4 not found, 5 active but the running game must be restarted), u32 result code, UTF‑8 text |
| 22 clear | phone → console | — |

The list is paged: the phone asks again with the next offset until it has `total` entries (offset 0
rescans the SD card). For source 1, `id` is the 8 bytes at 0x54 of the dump and `uid` the tag UID
(bytes 0–2 and 4–7); names are at most 63 bytes. The sysmodule creates
`emuiibo/amiibo/OpenGamepad/[group/]name/` with `amiibo.json` and `amiibo.flag` the first time
(`game_character_id` = id[0] | id[1] << 8, `model_number` = id[4] << 8 | id[5], as emuiibo's own
conversion), then sets it active, turns emulation on and connects it. Type 23 is reserved.

## LAN discovery

The phone sends the ASCII datagram `OGP-DISCOVER` to UDP port **47801**, both to the subnet broadcast
addresses and to every host of the local /22–/30 IPv4 subnets (many phones do not route broadcasts to
Wi‑Fi). Companions and the Switch sysmodule answer to the sender:

```
OGP-HERE <tcp port> <name>
```

for example `OGP-HERE 47800 DESKTOP-1234` or `OGP-HERE 47800 Nintendo Switch`. At the same time the
phone tries a bare TCP connection to port 47800 on every subnet host, for networks that drop UDP; hosts
found only this way are listed by address. That probe sends no hello, so no controller is created.

## Nintendo Switch sysmodule

The sysmodule (`switch-sysmodule/`, program id `0100000000004750`) speaks the same protocol on TCP and
UDP 47800 and answers with host code 4. Each phone that completes the hello gets a virtual Pro
Controller (hiddbg HDLS); states are accepted over UDP only from the address of a greeted TCP client.
Xbox buttons map by position (A → B, B → A, X → Y, Y → X, Guide → Home), triggers above 128 press
ZL/ZR. A phone silent for 10 s is dropped.

### Motion on the Switch

On HOS 12.0.0+ the virtual Pro Controller gets a motion sensor that games read natively (verified on
HOS 21.1.0 with Mario Kart 8 Deluxe and Super Mario Odyssey). HID only gives a virtual controller a
sensor when `HasVirtualSixAxisSensorAcceleration | HasVirtualSixAxisSensorAngle` are already in its state
**when it is created**, and only `ApplyHdlsStateList` creates a device that way: one made with
`AttachHdlsVirtualDevice` keeps the values written later, but games read zeros. The sysmodule therefore
dumps the HDLS state list, appends its device with a handle of its own (prefix `0x4F4750…`, unique per
device) and applies it. Devices created like this outlive the process, so every start removes the ones
with that prefix left by an earlier run (stopping the module from ovl-sysmodules).

| HDLS field | From the type‑17 sample |
|---|---|
| `six_axis_sensor_acceleration` (g) | −(x, y, z) / 1000 |
| `six_axis_sensor_angle` (revolutions, 1.0 = 360°) | integral of −(x, y, z) × 0.1 °/s ÷ 360 over the sample timestamps |

The axes are the phone's (x right, y top edge, z out of the screen); the Switch uses the opposite sign
convention for both vectors: acceleration is the gravity vector (measured with the console's own
sensor: flat 0, 0, −1; upright 0, −1, 0; right side down +1, 0, 0), and rotation follows it, otherwise
HID's orientation fuses a gyro that disagrees with gravity and tilt steering drifts. The angle follows
`HidSixAxisSensorState.angle`: rotation integrated per axis. With no sample for 0.5 s the acceleration returns to the resting (0, 0, −1) and the
angle stays where it is. Late UDP packets (older timestamp) are dropped; gaps are not extrapolated beyond
0.1 s.

## DSU server (emulators)

With "Server DSU per emulatori" on, the app listens on UDP **26760** and speaks the cemuhook DSU
protocol, version 1001, as one controller in slot 0 (model "full gyro", Bluetooth, battery full). In the
emulator set the DSU/cemuhook server to the phone's address and port 26760. Clients must repeat their
pad‑data request: after 5 s without one they stop receiving. The server runs only while the option is on
and the app is in the foreground.

Every packet: `"DSUS"`, u16 version 1001, u16 length after the 16‑byte header, u32 CRC32 (zlib) of the
whole packet with this field zeroed, u32 server id, u32 message type, data. Requests (`"DSUC"`) are
checked the same way. Supported: 0x100000 version, 0x100001 port info, 0x100002 pad data (100 bytes).

Pad data carries buttons (by position, as on the Switch: Xbox A → B, B → A, X → Y, Y → X), sticks
(0–255, up positive), triggers, and motion converted from the controller frame:

| DSU field | Value |
|---|---|
| accel X, Y, Z (g) | x, −z, −y (lying flat face up: Y = −1) |
| gyro pitch, yaw, roll (°/s) | x, z, y |
| timestamp | sensor time in µs |

## Bluetooth HID

Without a companion the phone registers as a Bluetooth HID device (Android `BluetoothHidDevice`) with a
composite descriptor:

- **Report 1 – gamepad** (13 bytes): 16 buttons, hat switch (0 = up, clockwise, 8 = released) + 4 bits
  padding, X/Y/Z/Rz as signed 16‑bit (Y axes grow downwards), Brake/Accelerator 0–255 as triggers.
  Buttons use the slots Android and Linux expect from generic gamepads, so they map correctly without
  a per-device layout: 1 A, 2 B, 4 X, 5 Y, 7 LB, 8 RB, 9 L2, 10 R2 (digital, trigger > 128),
  11 Back, 12 Start, 13 Guide, 14 L3, 15 R3.
- **Report 2 – keyboard** (8 bytes): modifiers, reserved byte, six key usages (0x00–0xE7).

Hosts cache the descriptor at pairing time: after changing it the phone must be paired again.

The Switch Pro Controller profile fills the three IMU samples of report 0x30 (bytes 13–48 after the
report id) with the latest motion sample while gyro passthrough is on, otherwise they stay at zero. Pro
Controller sensor axes: x towards the top edge, y towards the left, z out of the face; raw values invert
the factory calibration the profile serves from SPI 0x6020 (accel ±4 g span over sensitivity − origin,
gyro 936 °/s over sensitivity − origin).
