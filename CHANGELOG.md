# Doorbell Display - Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-11-11
First packaged release (Hubitat Package Manager).

### Added
- Each ViewPort has a required LG TV and TV input. On a ring, a ViewPort/TV pair is switched only when its TV is on; the app never turns a TV on.
- While the doorbell is showing, the app watches each TV's input, channel, power and screen. When the time is up, a TV goes back only if nobody took control, it is still on, and it still shows the ViewPort input. Volume and mute don't count.
- The settings page asks each TV for its input list, with a **Reload inputs** button.
- Package manifest for Hubitat Package Manager.

## [0.1.1] - 2026-11-11
### Added
- `SECURITY.md` with the secure coding standard, and CI that runs a security check and a Groovy parse check on every pull request.

### Fixed
- Debug logging switches itself off after 30 minutes.

## [0.1.0] - 2026-11-05
### Added
- Shows a chosen liveview on UniFi Protect ViewPorts when the doorbell rings, then puts back what each ViewPort showed before, unless its liveview was changed meanwhile.
- Rings during the display are ignored and don't restart the timer.
