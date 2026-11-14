# Ideas for later

Not planned for a release yet. Each item says what works already, what would be new, and what it costs.

## Works today, only needs documenting

OpenGamepad is an input device. Anything that runs on a PC, a TV or an Android TV and reads a normal
controller already works with it:

| Service | How |
|---|---|
| GeForce NOW, Xbox Cloud Gaming, Steam Link, Moonlight / Artemis | On a PC: companion → virtual Xbox controller. On Android TV / Google TV / Shield: Bluetooth gamepad mode. |
| PlayStation Remote Play through [chiaki-ng](https://github.com/streetpea/chiaki-ng) | chiaki-ng on a PC reads any SDL controller, so companion → virtual Xbox controller → chiaki-ng → PS4/PS5. |

What is missing is a short guide per service in the README.

## Controlling a console directly from the phone (no PC)

PlayStation and Xbox consoles accept only their own authenticated controllers over Bluetooth or USB, so the
only way in without a PC is their **remote play protocol**: open a remote play session and send just the
controller input, ignoring the video.

- **PlayStation (PS4/PS5)**: feasible. The protocol is implemented in open source by chiaki-ng
  (`libchiaki`, C, already built for Android). It needs a one-time registration with the console (PSN
  account id + PIN shown on the console). The console treats the session as player 1. chiaki-ng is
  AGPL-3.0: the code cannot enter the MIT app, but a separate plugin under AGPL can use it.
  Closed apps such as PS Play/PXPlay cannot be reused.
- **Xbox**: possible but weaker. The console remote play protocol is less documented, changes with system
  updates, and the open implementations are less mature. Worth it only after the PlayStation plugin.
- **Terms of service**: both protocols are reverse-engineered. Users accept the risk; the plugins must say so
  and must never ship keys or account data.

## Plugins downloaded on request

Keep the core app small and MIT, and offer extra protocols as **separate APKs**, installed from a button:

1. The app lists the plugins published with **the same version tag** on GitHub Releases
   (`opengamepad-plugin-playstation-<version>.apk`).
2. On request it downloads the APK, checks its SHA-256 against the release notes and that it is signed with
   the **same certificate** as the app, then opens Android's installer (the user confirms).
3. Core and plugin talk through a bound service protected by a `signature` permission, so only plugins
   signed with the project key can attach.

Why separate APKs and not code loaded at runtime: Android's installer and signature checks do the security
work; a plugin can keep its own license (AGPL for chiaki-ng) without affecting the MIT core; a crash in a
plugin cannot take the controller down; users who never need it never download it.

Cost: one AIDL interface (input state in, status/rumble out), the download/verify/install flow, and one CI
job per plugin. The PlayStation plugin itself is the large part (protocol, registration screen, testing on
real PS4/PS5 hardware).
