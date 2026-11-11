# Hubitat Doorbell Display

A Hubitat Elevation app that shows the doorbell camera when someone rings: each UniFi Protect ViewPort switches to a doorbell liveview, and the LG TV it is connected to switches to the ViewPort's input. Afterwards both go back to what they were showing, unless someone took control in the meantime.

It uses a **UniFi Protect Doorbell** and **UniFi Protect Viewport** devices from [hubitat-unifi-protect](https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect), and **LGTV with webOS** devices from [hubitat-lg-webos](https://github.com/9r5n76yjmzs-design/hubitat-lg-webos).

> **Status:** early development (0.2.0). Tested on a hub with UniFi Protect 7.3.74 and an LG webOS TV.

## How it works

1. The doorbell rings. The Doorbell device reports button 1 pushed.
1. For each ViewPort/TV pair whose TV is on, the app remembers what the ViewPort and the TV are showing, then switches the ViewPort to the chosen liveview (for example "Doorbell") and the TV to the ViewPort's input. A pair whose TV is off is left alone: the app never turns a TV on.
1. After the set time, each ViewPort and TV goes back to what it showed before the ring.

Details:
- **Rings while the doorbell is showing are ignored**, however many there are. They don't restart the timer, so the ViewPorts go back at the time set by the first ring. A ring after that starts a new display.
- **The TV counts as on** when its `switch` is `on` and its `screen` isn't `off` (sound only) or `standby`. A screen saver counts as on.
- **A TV goes back only if all of these hold:** it had something to go back to (not off, unknown or already the ViewPort input), it is still on, it still shows the ViewPort input, and nobody changed its input, channel, power or screen during the display. Volume and mute changes don't count, so turning the sound up to hear the doorbell camera is fine. The app's own switch to the ViewPort input is expected however long the TV takes to report it.
- **If someone changes a ViewPort to a different liveview in the meantime**, that ViewPort is left alone when the time is up.
- **If a ViewPort was already showing the chosen liveview**, or its liveview is `unknown` (Protect couldn't be reached), it isn't switched back.
- **Push on the Doorbell device** triggers the app too, so you can test it without going to the door.

## Requirements

- [hubitat-unifi-protect](https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect) controller 0.3.0 or later, with **Real-time updates** on, plus the Viewport, Doorbell and Event Stream drivers.
- [hubitat-lg-webos](https://github.com/9r5n76yjmzs-design/hubitat-lg-webos) for each TV a ViewPort is connected to. Each TV must be on while you set the app up, so it can list its inputs.
- A Protect liveview to show on a ring, such as one with only the doorbell camera.

## Setup

1. Install the app:
   - **With Hubitat Package Manager (recommended):** in HPM, choose **Install** → **From a URL**, enter `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-doorbell-display/main/packageManifest.json`, and follow the steps. Install the **UniFi Protect** and **LGTV with webOS** packages the same way; HPM doesn't install them for you. HPM can only install while the repositories are public.
   - **Manually:** go to **Apps Code** → **New App**, paste [`doorbell-display.groovy`](doorbell-display.groovy) and click **Save**.
1. Go to **Apps** → **Add User App** → **Doorbell Display**.
1. Choose:
   - **Doorbell**: the list shows only UniFi Protect Doorbell devices.
   - **ViewPorts to switch**: one or more UniFi Protect Viewport devices.
   - For each ViewPort, its **TV** and the **TV input** the ViewPort is connected to. Both are required. The app asks the TV for its inputs and apps; if the list is empty, wait a few seconds and click **Reload inputs**.
   - **Liveview to show**: read from the ViewPorts. A liveview named "Doorbell" is pre-selected if there is one.
   - **Put everything back after**: 5 to 3600 seconds (default 30).
1. Click **Done**.

Install the app again for another doorbell or a different set of ViewPorts.

## Known limitations (0.2.0)

- **Hubitat can't pre-select a device**, so the doorbell has to be chosen even when there's only one. The list shows only doorbells.
- **The liveview list comes from the ViewPorts.** If Protect can't be reached when the app is opened, the list is empty. A liveview already chosen stays selected.
- **The TV input list comes from the TV**, which can only answer while it is on. The settings page asks for it automatically (at most once a minute per TV) and with **Reload inputs**. An input already chosen stays selected.
- **Switching back relaunches what the TV showed** (an app, an input or Live TV). An app such as Netflix may open on its home screen rather than resuming where it was.

## Security

See [SECURITY.md](SECURITY.md) for what the app can reach and the secure coding rules every change follows.

## License

Released into the public domain under [The Unlicense](LICENSE).
