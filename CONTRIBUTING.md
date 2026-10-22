# Contributing

Thanks for helping! Issues and pull requests are welcome, in English or Italian.

## Development setup

### Android app (`android/`)

- JDK 17 or newer and the Android SDK with platform 36 (Android Studio works out of the box).
- `./gradlew testDebugUnitTest` runs the unit tests (macro engine, configuration).
- `./gradlew installDebug` installs a debug build on a connected phone.

Debug builds accept a few extras that make testing over ADB easier:

```bash
adb shell am start -n org.opengamepad/.MainActivity --es target 192.168.1.10:47800
adb shell am start -n org.opengamepad/.MainActivity --ez usb true
adb shell am start -n org.opengamepad/.MainActivity --es macrosFile /data/local/tmp/macros.json
```

`adb shell input gamepad keyevent KEYCODE_BUTTON_A` simulates a physical controller.

### Windows companion (`pc-companion/`)

- .NET 8 SDK, ViGEmBus driver installed.
- `dotnet build` / `dotnet run`.

## Release signing

Create `android/keystore.properties` (it is ignored by git):

```properties
storeFile=release.jks
storePassword=...
keyAlias=opengamepad
keyPassword=...
```

A key can be generated with
`keytool -genkeypair -v -keystore android/release.jks -alias opengamepad -keyalg RSA -keysize 4096 -validity 10000`.

The release workflow reads the same values from the repository secrets `ANDROID_KEYSTORE_BASE64`
(the `.jks` file encoded in base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD`, and refuses to run without them.

Every update must be signed with the same key as the previous releases, otherwise Android refuses
to install it over the existing app: keep a backup of the keystore and its password.

Publishing a release: *Actions → Release → Run workflow* with the new tag (e.g. `v1.1.0`); the
workflow builds the APK and the companion and attaches them to a draft release. Releases can also
be built locally (`./gradlew assembleRelease` with `keystore.properties`) and uploaded by hand.

## Guidelines

- Keep the interface **pitch black**: no filled surfaces, controls as thin outlines.
- Anything that can turn the screen off must have a way back that does not depend on the app.
- Changes to the wire protocol go together with an update of [docs/PROTOCOL.md](docs/PROTOCOL.md) and
  must stay compatible with existing companions, or be negotiated through the extension version.
- Changing the Bluetooth HID descriptor forces users to pair again: avoid it unless necessary.
- Translations: the UI strings are currently in Italian inside the Compose code; moving them to
  `strings.xml` resources is a welcome contribution.
