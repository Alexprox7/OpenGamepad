# OpenGamepad – Android app

Kotlin + Jetpack Compose, minSdk 28, targetSdk 36. User documentation is in the
[main README](../README.md); the wire protocol in [docs/PROTOCOL.md](../docs/PROTOCOL.md).

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew installDebug        # debug build on a connected phone
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Code map

| Package | Content |
|---|---|
| `protocol` | Wire format: framing, controller state, feedback |
| `net` | Wi‑Fi/USB connection (TCP control + UDP/TCP states), QR parsing |
| `bt` | Bluetooth HID gamepad + keyboard, descriptor, keep-alive foreground service |
| `input` | Touch + physical controller merging |
| `macro` | Macro/radial configuration (JSON), engine, key names |
| `screen` | OLED black mode, panel off via Shizuku (`DisplayPower`, `ScreenControlService` with watchdog) |
| `ui` | Compose screens: connection, controller, radial overlay, editors |

## Macro configuration

The visual editor stores the configuration as JSON (also editable from *Avanzate (JSON)*):

```json
{
  "comboWindowMs": 50,
  "macros": [
    { "name": "Quick wheel", "trigger": { "buttons": ["L3", "R3"] }, "radial": "quick" },
    { "name": "Screenshot", "trigger": { "buttons": ["BACK", "Y"], "holdMs": 300 },
      "actions": [ { "keys": ["WIN", "PRINTSCREEN"], "ms": 80 } ] },
    { "name": "Turbo A", "trigger": { "buttons": ["LB", "A"] }, "repeat": true,
      "actions": [ { "press": ["A"], "ms": 40 }, { "wait": 40 } ] }
  ],
  "radials": [
    { "id": "quick", "stick": "RIGHT", "slices": [
      { "label": "Heal", "icon": "❤️", "color": "#C2373D", "actions": [ { "keys": ["1"] } ] }
    ] }
  ]
}
```

- **trigger**: buttons held together (`A B X Y LB RB LT RT BACK START GUIDE L3 R3 UP DOWN LEFT RIGHT`),
  `holdMs`, `consume` (default `true`: the combo buttons do not reach the game). The longest active
  combo wins.
- **actions**: `press` (controller buttons), `keys` (PC keys, up to 6 plus `CTRL SHIFT ALT WIN`),
  `stick` (`LEFT`/`RIGHT` with `x`/`y` in −1…1), `wait`; `ms` is the duration.
- **repeat**: repeats the actions while the trigger is held.
- **radial**: hold the trigger, choose with the stick, release to run the slice; releasing without
  moving the stick cancels.
