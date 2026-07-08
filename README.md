# BMS 3D Floor Plan — Android kiosk app

A thin, fullscreen **WebView kiosk** that shows the 3D floor-plan panel on a wall
tablet and controls the home live — the same panel the Home Assistant
integration renders, connected over the local network with a token.

The panel itself lives in the integration repo
**[Abdunazar7/bms-3d-floorplan](https://github.com/Abdunazar7/bms-3d-floorplan)**;
this repo is just the Android wrapper. CI pulls the latest kiosk page + card from
there at build time, so the APK always bundles the current panel.

- **Authoring stays in Home Assistant.** You build the house model, place
  furniture, and bind lights / climate / covers etc. in HA. The tablet only
  **views and controls** the saved plan (pulled live).
- **Connection:** the app hands the bundled kiosk page your HA URL + a
  **long-lived access token**; the page opens a WebSocket to
  `ws://<HA>:8123/api/websocket` and drives devices — no cloud, no HA login flow.

## Install & configure

1. Download `bms-3d-floorplan.apk` from the latest
   [Release](https://github.com/nurillaevich/bms-3d-floorplan-android/releases)
   (or the **Build APK** workflow artifact).
2. Sideload it on the tablet (allow "install unknown apps").
3. On first launch, enter the HA address (e.g. `http://192.168.1.50:8123`) and a
   long-lived token (HA profile → Long-lived access tokens). Tablet and HA must
   be on the same Wi-Fi.
4. To re-open settings later, **long-press the bottom-left corner**.
5. In settings, **"Обновить приложение"** pulls the newest signed APK straight
   from this repo's [Releases](https://github.com/nurillaevich/bms-3d-floorplan-android/releases)
   and launches the installer — no need to re-download by hand.

> Tip: set an edit PIN in the card (in HA) so the kiosk stays view/control-only.

## Build

CI (`Build APK`) builds it on every push, version tag, and on demand. Locally you
need the Android SDK + JDK 17, then bundle the web assets and build:

```
mkdir -p app/src/main/assets/kiosk
curl -fL https://raw.githubusercontent.com/Abdunazar7/bms-3d-floorplan/main/standalone/index.html       -o app/src/main/assets/kiosk/index.html
curl -fL https://raw.githubusercontent.com/Abdunazar7/bms-3d-floorplan/main/dist/ha-3d-floorplan-card.js -o app/src/main/assets/kiosk/ha-3d-floorplan-card.js
gradle assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

The APK is signed with a **fixed keystore** so every build shares one
certificate and the in-app updater can upgrade in place. The keystore is **not**
in the repo — CI base64-decodes the `SIGNING_P12_BASE64` repository secret into
`app/signing.p12` at build time. (Add it under *Settings → Secrets and variables
→ Actions*. Without it, builds fall back to a throwaway key that installs fresh
but can't update in place.)

> Upgrading from a build made **before** the fixed key (each earlier one had a
> random per-CI key) fails with *"conflicts with an existing package"* — that's
> a signature mismatch. **Uninstall once, then install the new build.** After
> that, "Обновить приложение" upgrades in place forever.
