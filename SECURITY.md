# Security

This app runs on a Hubitat Elevation hub. It only uses devices you select: a UniFi Protect Doorbell and Viewports from [hubitat-unifi-protect](https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect), and LG TVs from [hubitat-lg-webos](https://github.com/9r5n76yjmzs-design/hubitat-lg-webos). The goal is that installing it does not widen what an attacker can reach through the hub.

## Reporting a problem

Please report security problems privately, not in a public issue: on GitHub, open this repository's **Security** tab and choose **Report a vulnerability**. The report goes only to the maintainer, Michael Dailous.

## What the app can reach

- **Only the devices you select.** The app makes no network requests of its own, holds no secrets, and opens no endpoints (no `mappings`, OAuth or cloud callbacks).
- **What it does:** on a doorbell ring it calls Set Liveview on the selected Viewports and Start Activity on their TVs (only the inputs and apps chosen in its settings, or what a TV showed before), then sets them back after the chosen time. It never turns a TV on. Repeated rings are ignored while the display is active, and the display time is limited to 5 to 3600 seconds.
- **On its settings page** it asks a selected TV for its input list (Get All Activities), at most once a minute per TV unless **Reload inputs** is clicked.
- **What it reads:** the selected devices' current values, and during a display the TVs' input, channel, power and screen changes. Device values are treated as untrusted text.

## Secure coding standard

Every change follows these rules. A line may opt out of one checked rule only with a reviewed marker that gives the reason, for example `// security-check: allow S1 (reason)`. Rules marked *(checked)* are enforced by [`.github/scripts/security-check.sh`](.github/scripts/security-check.sh), which runs on every pull request with the Groovy parse check.

1. **S1 (checked):** Secrets are never stored in `state`.
2. **S2 (checked):** Secret values are never written to the logs.
3. **S3 (checked):** Raw inbound messages are never logged.
4. **S4 (checked):** No credentials are hard-coded.
5. **S5 (checked):** Pages never load remote resources (images, scripts, styles).
6. **S6 (checked):** Links that open a new tab use `rel="noopener noreferrer"`.
7. **S7 (checked):** Debug logging is off by default, and turns itself off after 30 minutes when switched on.
8. **S8 (checked):** Any file that skips certificate checks explains why and points here.
9. **S9 (checked):** Continuity records, keys and `.env` files are never committed.
10. **Validate all input** from settings and devices before use; device values are treated as untrusted text.
11. **Least privilege:** use only the devices the user selects; no network access, no endpoints.
12. **Bound everything:** timers and retries have limits.
13. **No new attack surface:** no listening ports, OAuth/cloud endpoints, or remote code or content loading.
