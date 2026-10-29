# RadarNebo

<img src="docs/screenshots/desktop-slide.png" width="100%" alt="The mission-control console with the client app embedded, next to the desktop app">

## Screenshots

Rendered from the app's Compose previews (see [Previews](#previews)); the map and targets use hand-placed demo data.

| Map | Target selected | Cinematic mode | Targets |
|---|---|---|---|
| <img src="docs/screenshots/map-overview.png" width="200"> | <img src="docs/screenshots/target-selected.png" width="200"> | <img src="docs/screenshots/cinematic.png" width="200"> | <img src="docs/screenshots/targets-list.png" width="200"> |
| **Light theme** | **Threat card** | **Add a favorite (long-press)** | **Warning radius** |
| <img src="docs/screenshots/app-phone-light.png" width="200"> | <img src="docs/screenshots/threat-card.png" width="200"> | <img src="docs/screenshots/add-favorite.png" width="200"> | <img src="docs/screenshots/warning-radius.png" width="200"> |
| **Cities, zoomed out** | **City names, zoomed in** | **Every threat type** | **Target states** |
| <img src="docs/screenshots/cities-dots.png" width="200"> | <img src="docs/screenshots/cities-zoomed.png" width="200"> | <img src="docs/screenshots/every-threat-type.png" width="200"> | <img src="docs/screenshots/target-states-map.png" width="200"> |
| **Favorites** | **Settings** | **Target types legend** | **Live demo (app)** |
| <img src="docs/screenshots/favorites.png" width="200"> | <img src="docs/screenshots/settings.png" width="200"> | <img src="docs/screenshots/target-types-legend.png" width="200"> | <img src="docs/screenshots/app-phone.png" width="200"> |

**Tablet:** overview, a cruise missile's track and prediction, and cinematic mode.

<img src="docs/screenshots/map-overview-tablet.png" width="820">
<img src="docs/screenshots/missile-selected-tablet.png" width="820">
<img src="docs/screenshots/cinematic-tablet.png" width="820">

**Target icons**, red and yellow alert levels:

<img src="docs/screenshots/target-icons.png" width="440">

### Running the apps

Android Studio picks up the shared run configurations in [`.run/`](./.run) and lists them in the toolbar's run widget:

| Configuration | Runs |
|---|---|
| `app:android` | The Android app on the selected device or emulator |
| `app:ios` | The iOS app on the selected simulator (needs the Kotlin Multiplatform plugin and Xcode) |
| `app:desktop` / `app:desktop [hot]` | `./gradlew :app:desktop:run` / `hotRun` (Compose Hot Reload) |
| `mission-control:ui` / `mission-control:ui [hot]` | `./gradlew :mission-control:ui:run` / `hotRun` |
| `mission-control:server` | `./gradlew :mission-control:server:run`, the server without a window |
| `mission-control:ui + app:desktop` | Both of the above at once |

The desktop and mission-control configurations run `./gradlew` as shell scripts, so each is a Gradle build of its own
and they run side by side, alongside the Android or iOS app. Android Studio's own Gradle runs would queue behind a
running app instead. Stop ends the build and the app. The one pair that doesn't run together is `mission-control:ui`
and `mission-control:server`: there is one server per machine, and the one started later takes over.

From a terminal:

- Android app: `./gradlew :app:android:assembleDebug`
- Android release build: `./gradlew :app:android:assembleRelease` (APK) or `bundleRelease` (AAB). Signing needs a
  keystore and `keystore.properties`; see [Android release build](./docs/android-release-build.md).
- Desktop app: `./gradlew :app:desktop:run`
- iOS app: open the [/app/ios](./app/ios) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…