# Hubitat Doorbell Display

A Hubitat Elevation app that shows the doorbell camera on UniFi Protect ViewPorts when someone rings, then puts back whatever each ViewPort was showing before.

It uses the devices from [hubitat-unifi-protect](https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect): a **UniFi Protect Doorbell** to detect the ring and one or more **UniFi Protect Viewport** devices to switch.

> **Status:** early development (0.1.0). Parse-checked, not yet tested on a hub.

## How it works

1. The doorbell rings. The Doorbell device reports button 1 pushed.
1. For each chosen ViewPort, the app remembers the liveview it's showing, then switches it to the chosen liveview (for example "Doorbell").
1. After the set time, each ViewPort goes back to the liveview it showed before the ring.

Details:
- **Rings while the doorbell is showing are ignored**, however many there are. They don't restart the timer, so the ViewPorts go back at the time set by the first ring. A ring after that starts a new display.
- **If someone changes a ViewPort to a different liveview in the meantime**, that ViewPort is left alone when the time is up.
- **If a ViewPort was already showing the chosen liveview**, or its liveview is `unknown` (Protect couldn't be reached), it isn't switched back.
- **Push on the Doorbell device** triggers the app too, so you can test it without going to the door.

## Requirements

- [hubitat-unifi-protect](https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect) controller 0.3.0 or later, with **Real-time updates** on, plus the Viewport, Doorbell and Event Stream drivers.
- A Protect liveview to show on a ring, such as one with only the doorbell camera.

## Setup

1. In Hubitat, go to **Apps Code** → **New App**, paste [`doorbell-display.groovy`](doorbell-display.groovy) and click **Save**. While this repository is private, paste the code directly instead of importing it from the raw URL.
1. Go to **Apps** → **Add User App** → **Doorbell Display**.
1. Choose:
   - **Doorbell**: the list shows only UniFi Protect Doorbell devices.
   - **ViewPorts to switch**: one or more UniFi Protect Viewport devices.
   - **Liveview to show**: read from the ViewPorts. A liveview named "Doorbell" is pre-selected if there is one.
   - **Put the previous liveview back after**: 5 to 3600 seconds (default 60).
1. Click **Done**.

Install the app again for another doorbell or a different set of ViewPorts.

## Known limitations (0.1.0)

- **Hubitat can't pre-select a device**, so the doorbell has to be chosen even when there's only one. The list shows only doorbells.
- **The liveview list comes from the ViewPorts.** If Protect can't be reached when the app is opened, the list is empty. A liveview already chosen stays selected.

## License

Released into the public domain under [The Unlicense](LICENSE).
