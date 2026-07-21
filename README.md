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

## Intercom (voice between tablets)

Any two tablets in the house can call each other, hands-free. **The only thing
you configure is a name.**

1. Hidden settings → **«Интерком»** → type the panel's name (`1-этаж`, `Кухня`…)
   → tick **«Включить интерком»**. Repeat on every tablet.
2. Each tablet now shows an **«Интерком»** button on the plan. Tap it: the other
   panels are already listed. Tap one to ring it.
3. On the panel being called the screen wakes by itself and shows
   **Ответить / Отклонить**, even from standby.

Adding a fourth tablet later needs no visit to the first three — it appears in
their lists by itself.

**How it finds the others.** Each tablet writes one entity into Home Assistant,
`sensor.bms_intercom_<id>`, carrying its name, LAN address and intercom key, and
refreshes it every minute. Every tablet reads that same list back. HA is used
because all the panels already talk to it — no broker, no mDNS, no static IPs,
and a tablet that changes address fixes itself within a minute. Those states do
not survive an HA restart, which the same heartbeat repairs.

**How the voice travels.** Ringing and hang-up ride the existing HTTP control
endpoint (port 2323). The audio itself is **plain 16 kHz PCM over UDP, tablet to
tablet** — no WebRTC, no SIP, no video, nothing leaves the LAN and nothing passes
through Home Assistant. On one switch, WebRTC's NAT traversal and congestion
control buy nothing while costing more code than the rest of this app.

**Security.** An invite is signed with the callee's own intercom key, taken from
the directory; its answers are signed with a one-shot key minted for that call.
Neither is the remote-control password, so enabling the intercom never grants
anyone the ability to drive the kiosk. Audio packets are accepted only from the
peer's address and only for the call in progress. Note the intercom key is
readable by anyone with access to your Home Assistant — the same people who can
already control the house.

> If you can hear the other side but they can't hear you, or the call is silent
> on one panel, tick **«Звук через медиа-канал»** in settings — some cheap
> tablets have no voice-call audio path.

## Kiosk lock

On by default: the app pins itself (Android **Lock Task Mode**), blocking the
Home & Recents buttons and the status-bar pull-down, and it **relaunches after a
reboot** — so the panel is always up. **To exit:** long-press the bottom-left
corner → settings → **"Выйти из приложения"** (or untick *Режим киоска* there).

**Make it the home screen (recommended, no ADB).** The app also registers as a
launcher, so you can set it as the tablet's default desktop — then the **Home
button returns to the kiosk** instead of leaving it. Easiest: in the app's hidden
settings tap **«Сделать домашним экраном»** (opens the «Рабочий стол» picker) →
choose **BMS 3D Floor Plan** → set as default / Always. Combined with the pin +
boot-relaunch, this keeps the panel locked without any ADB.

**Fully tamper-proof (device owner).** Without device-owner, Android still lets
someone unpin by holding **Back + Overview**. To block that too — no toast, no
escape — provision the app as **device owner** *once*:

1. On the tablet: remove every Google/other account (Settings → Accounts). Device
   owner can only be set when no accounts exist.
2. Enable USB debugging (Settings → Developer options), connect over USB, then:

   ```
   adb shell dpm set-device-owner uz.bms.floorplan3d/.AdminReceiver
   ```

3. Relaunch the app. Lock Task is now un-escapable; it also survives reboots.

To undo: exit via the hidden settings, then
`adb shell dpm remove-active-admin uz.bms.floorplan3d/.AdminReceiver` (or factory
reset). This step is optional — the default pin is fine for a normal wall panel.

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
certificate and the in-app updater can upgrade in place. The keystore is
committed **AES-encrypted** (`app/signing.p12.enc` — safe to be public); CI
decrypts it with the short `SIGNING_STORE_KEY` repository secret at build time.
(Add it under *Settings → Secrets and variables → Actions*. Without it, builds
fall back to a throwaway key that installs fresh but can't update in place.)

> Upgrading from a build made **before** the fixed key (each earlier one had a
> random per-CI key) fails with *"conflicts with an existing package"* — that's
> a signature mismatch. **Uninstall once, then install the new build.** After
> that, "Обновить приложение" upgrades in place forever.
