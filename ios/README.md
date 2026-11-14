# OpenGamepad for iOS

SwiftUI version of the controller for iPhone and iPad (iOS 16+): connects over Wi‑Fi to the
OpenGamepad Companion (Windows, Linux) or to the Switch sysmodule, with the same protocol as the
Android app.

- Touch controls with Xbox labels, or Nintendo labels when connected to a Switch.
- Physical controllers (Xbox, PlayStation, Switch Pro, MFi, Backbone, GameSir…) through Apple's
  GameController framework, merged with the touch controls.
- *Search the network* finds companions and the Switch on the local network; scanning the companion QR
  code with the Camera app opens OpenGamepad and connects.
- Rumble becomes haptic taps on the phone.
- Dark theme, or OLED black (pure black) under *Appearance* on the connection screen.
- English, Italian, Chinese (simplified and traditional) and Japanese, picked from the system language
  (English otherwise).

Not available on iOS: Bluetooth HID mode (iOS apps cannot act as a Bluetooth gamepad), panel off,
USB, macros.

## Install (sideload)

Releases include an **unsigned** `OpenGamepad-<version>.ipa`. Install it with
[SideStore](https://sidestore.io) or [AltStore](https://altstore.io), which sign it with your Apple ID;
with a free account the app has to be refreshed every 7 days.

The first time you tap *Search the network* or *Connect*, allow **Local Network** access.

## Build

On a Mac with Xcode 15 or later:

```sh
brew install xcodegen
cd ios && xcodegen generate
open OpenGamepad.xcodeproj    # pick your team under Signing to run it on a device
```

Unsigned IPA, as CI does it:

```sh
xcodebuild -project OpenGamepad.xcodeproj -scheme OpenGamepad -configuration Release \
  -sdk iphoneos -derivedDataPath build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
mkdir -p Payload && cp -R build/Build/Products/Release-iphoneos/OpenGamepad.app Payload/
zip -qr OpenGamepad.ipa Payload
```
