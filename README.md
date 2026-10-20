# RadarNebo

A Kotlin Multiplatform (Android + iOS, Compose Multiplatform) drone threat monitor for Ukraine, using the
[alerts.in.ua open API](https://devs.alerts.in.ua/).

## Features

- **Map of Ukraine**: drawn natively in Compose from simplified OpenStreetMap oblast boundaries. Oblasts are shaded by
  alert status (red: whole oblast, amber: partial). Drone threats are pulsing markers.
- **Simulation** without a token (app demo mode and the proxy's demo mode): `DroneSimulator` flies drone groups along
  realistic routes (e.g. Hlukhiv → Shostka → Konotop → Bakhmach → Nizhyn → Brovary → Kyiv) in real time, at real speeds
  (150–180 km/h). It raises an alert in each settlement as a group reaches it, and names the drone type in the alert
  text. The last 90 minutes are replayed on start, so tracks have a history immediately. The app always labels this
  as demo data.
- **Drones only** (Settings): show only `drones` threats from `/v1/alerts/active.json`, or include missiles, KABs and
  aviation as well.
- **Favorite locations**: search a city, add by coordinates, or long-press anywhere on the map. A favorite is flagged
  when a threat is within the proximity radius, or when its oblast has a region-wide drone threat.
- **Two map modes**
  - *Static map*: a fixed overview of Ukraine. Pinch to zoom, double-tap to reset, tap a marker for details.
  - *Cinematic*: an automatic camera that flies between your points of interest that have nearby threats, using
    fly-to arcs, a slow push-in, letterboxing and a HUD. Toggle it with the camera button in the top-right corner. With nothing nearby, it patrols your favorites
    and the main hotspots.
- **Target tracking**: the Targets button (crosshair under the camera button) lists every tracked drone group, each
  with a drone silhouette pointing along its heading (hollow for decoys), its model, latest location, time, speed and
  heading. Selecting a target shows:
  - **Drone**: model, maker, role and typical speed. alerts.in.ua doesn't identify drone types, so the model comes
    from the alert text when it names one (*reported*); otherwise it's inferred from the threat class and speed and
    marked *probable*.
  - **ID** (target and alert IDs), **speed**, **direction**, **distance flown** and **time tracked**.
  - **Predicted route**: the settlements ahead on the current heading (up to 150 km), each with minutes to arrival
    and clock time, drawn on the map as a dashed course with numbered stops.
  - **Timeline**: every reported position with its time, place, oblast and coordinates, plus the current estimate.

  Drones move continuously on the map: every frame their position is dead-reckoned from the last report (up to 30 min
  ahead), and when a new report corrects it the marker glides there instead of jumping.
- **Arrival prediction**: for each active target the app projects its current heading and speed to find the next
  settlement (or favorite) on its way within 50 km. It shows that place and an estimated arrival time in minutes on
  the map, in the target list and in the target details (`core/.../tracking/Prediction.kt`). The settlement list holds
  about 100 cities and towns. Predictions assume constant heading and speed and come from area alerts, so they are
  rough indications only.
- **Light and dark themes**: Settings → Appearance offers System (the default, follows the OS), Light and Dark. The
  map, overlays and system bars all follow the chosen theme.
- **Floating liquid-glass navigation bar**: a pill-shaped bar that floats over the content. It shows a blurred,
  saturated and slightly magnified copy of whatever is behind it (`ui/LiquidGlass.kt`), and a glass lens slides
  between tabs.
- **Map max amount**: Settings → "Max drones shown on map" (5–300) caps how many markers are drawn. Proximity
  detection always uses every threat.

## Previews

Every screen and component has Compose `@Preview`s in light and dark, fed by the drone simulation (`ui/preview/PreviewData.kt`).
`PreviewsRenderTest` renders them all headlessly in CI (`./gradlew :app:desktop:test`).

## Modules

| Module | What it is |
|---|---|
| `core` | Pure Kotlin (Android, iOS, JVM): alerts.in.ua client and models, geography, threat mapping, drone tracking and interpolation |
| `shared` | Compose Multiplatform UI (Android, iOS, desktop JVM) |
| `app/android` (`:app:android`), `app/desktop` (`:app:desktop`), `app/ios` (Xcode project) | Platform entry points |
| `server` | Ktor caching proxy for alerts.in.ua with drone position history |

## Desktop app

```
./gradlew :app:desktop:run                      # settings in ~/.radarnebo/settings.properties
./gradlew :app:desktop:packageDistributionForCurrentOS   # .dmg / .msi / .deb
```

Map controls on desktop:
- **Two-finger swipe** (trackpad) or **mouse wheel**: pan the map. Shift+wheel pans horizontally.
- **Pinch** (macOS trackpad): zoom around the cursor. Compose Desktop has no pinch support, so `MacTrackpad.java`
  hooks the JDK's native magnify gesture. Pinch needs `--add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED`,
  which the Gradle run and packaging config already pass.
- **Ctrl/⌘ + wheel**: zoom (mouse). **Drag**: pan. **Double-click**: reset. **Long-press**: add a favorite.

## Proxy server (`:server`)

The proxy is the only thing that talks to alerts.in.ua. Apps read from its cache, so the number of clients never
affects the upstream rate.

- **Rate limits:** alerts.in.ua allows 8–10 requests/min per IP (soft) and 12 (hard). The proxy polls
  every 15 s (4/min) by default. A sliding-window limiter caps it at `UPSTREAM_MAX_PER_MINUTE` (default 6, never above
  8) and spaces requests evenly. It sends `If-Modified-Since`, honours `Retry-After` on 429 and backs off
  exponentially (up to 10 min) on errors. The limit is per IP, so don't run other alerts.in.ua clients from the
  same IP.
- **Cache:** `/v1/alerts/active.json` returns the upstream payload byte-for-byte, with an `ETag`/`Last-Modified`
  (clients get 304s) and `X-Data-Age-Seconds`.
- **Drone tracks:** every poll feeds a tracker. When a drone threat appears within 120 km of a track seen in the last
  30 min, it continues that track. Otherwise it starts a new one. Each track keeps a timestamped position history.
  - `GET /v1/drones/tracks.json`: tracks with their samples plus `serverTime`. The app interpolates positions locally
    every frame, so drones move smoothly between polls.
  - `GET /v1/drones/positions.json?at=<epoch ms | ISO-8601>`: positions computed on the server. Between samples it
    uses linear interpolation. Past the newest sample, an active track continues along its last segment (dead
    reckoning) at no more than 220 km/h, for at most 10 min and never further than that segment.
  - Positions are inferred from area alerts, not from radar. Raions have no coordinates in the API, so their points
    are approximate.
- `GET /health`: upstream budget, requests in the last minute, data age, last error.

```
ALERTS_API_TOKEN=... PORT=8080 PROXY_CLIENT_KEYS=key1,key2 ./gradlew :server:run
./gradlew :server:buildFatJar        # build/libs/server-all.jar
```

| Env var | Default | Meaning |
|---|---|---|
| `ALERTS_API_TOKEN` | – | Upstream token. If unset, the proxy serves the drone simulation. |
| `UPSTREAM_MAX_PER_MINUTE` | 6 | Upstream budget, clamped to 1–8 |
| `POLL_INTERVAL_SECONDS` | 15 | Never faster than the budget allows |
| `PROXY_CLIENT_KEYS` | – | Comma-separated client keys. Clients send `Authorization: Bearer <key>` or `?token=`. |
| `CLIENT_REQUESTS_PER_MINUTE` | 120 | Per-IP limit for clients |
| `PORT` | 8080 | |

In the app, set **API base URL** to the proxy and put the client key in the token field.

## API token

Request a token at https://devs.alerts.in.ua and enter it in **Settings**. Without a token, the app runs on clearly
labelled **synthetic demo data**. The API recommends that public apps call it through your own proxy. Set the proxy URL
in Settings ("API base URL"). Polling defaults to 30 s, and the API limit is about 8–10 requests/min per IP. The app
sends `If-Modified-Since` and backs off after errors.

The API gives no coordinates for raions or hromadas. Known cities are placed exactly, oblast-wide alerts sit at the
oblast centroid, and other locations get a stable pseudo-random spot inside their oblast.


* [/app/ios](./app/ios/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:android:assembleDebug`
- Desktop app: `./gradlew :app:desktop:run`
- iOS app: open the [/app/ios](./app/ios) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…