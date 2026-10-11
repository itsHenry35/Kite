# Kite

Android AirPlay sender. Mirrors your phone's screen — and plays its sound — on
a Mac, Apple TV, or any AirPlay receiver.

[English](README.md) | [简体中文](README.zh-CN.md)

## Releases

[![GitHub Release](https://img.shields.io/github/v/release/itsHenry35/Kite?style=flat-square&label=latest)](https://github.com/itsHenry35/Kite/releases)

## Screenshots

<p align="center">
  <img src="images/home.png" width="270" alt="Receiver discovery">
  <img src="images/settings.png" width="270" alt="Settings">
</p>

## Features

- Mirror your screen to a Mac, Apple TV, or any AirPlay receiver
- Sound included: long-press a receiver to choose screen with sound, screen
  without sound, or sound only
- One-time pairing on Mac and Apple TV — enter the PIN shown on screen once,
  then every connection just works
- Adjustable resolution, frame rate and bit rate (up to 200 Mbps)
- Finds receivers on your Wi-Fi automatically, including open receivers like
  UxPlay
- Quick Settings tile and one-tap log sharing

## Requirements

- Android 10 or later (minSdk 29)
- A real device on the same Wi-Fi network as the receiver
- An AirPlay receiver

## Building

```sh
./gradlew :app:assembleDebug
```

## Acknowledgements

Kite builds on years of open-source AirPlay work. Thanks to:

- [doubletake](https://github.com/omarroth/doubletake) — Go AirPlay sender;
  parts of Kite are Kotlin ports of it
- [UxPlay](https://github.com/FDH2/UxPlay) — open-source AirPlay receiver; a
  key reference during development
- [pyatv](https://github.com/postlund/pyatv) — AirPlay 2 protocol reference
- [shairport-sync](https://github.com/mikebrady/shairport-sync) — pairing and
  timing reference
- [openairplay](https://github.com/openairplay/airplay-spec) — early protocol
  documentation

## License

Kite is released under the GNU LGPL-3.0 — see [LICENSE](LICENSE). Ported files
carry doubletake's license. Open-source attributions are also listed in the app
under Settings → About.
