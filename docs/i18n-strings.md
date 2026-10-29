# RadarNebo UI strings (i18n inventory)

Compose Multiplatform string resources for English (default), Ukrainian and Dutch:

- `shared/src/commonMain/composeResources/values/strings.xml` (en, default)
- `shared/src/commonMain/composeResources/values-uk/strings.xml` (uk)
- `shared/src/commonMain/composeResources/values-nl/strings.xml` (nl)

The Kotlin code is **not wired yet**. Each key below lists where the literal lives today and the exact
original text, so it can be replaced with `stringResource(Res.string.<key>, …)` /
`pluralStringResource(Res.plurals.<key>, count, …)` (or `getString` outside composition).

Line numbers were taken from the working tree on 2026-10-26 while UI files were being edited, so they may
drift; the original literal is the reliable anchor. `shared/…/` = `shared/src/commonMain/kotlin/com/gusak/lab/radarnebo/`,
`core/…/` = `core/src/commonMain/kotlin/com/gusak/lab/radarnebo/`.

## Conventions

- Every interpolated value is a positional placeholder: `%1$s` for text, `%1$d` for integers, identical in all three
  languages (order may differ inside the sentence). Placeholders are used even for a single argument.
- Separators that code inserts between parts (`" · "`, `", "`) are generally left in code; where a key is a
  fragment, the notes say what the code adds around it.
- Strings concatenated with `+` across lines in Kotlin are merged into one resource.
- Counted nouns use `<plurals>` (en/nl: one/other; uk: one/few/many/other).
- Apostrophes are escaped (`\'`) in the XML.
- Units: `unit_*` keys replace hardcoded suffixes. The settings sliders' `format = { "$it km" }` / `"$it s"` map to
  `unit_km` / `unit_seconds`. Times (`HH:mm`, `HH:mm:ss`) and coordinates (`formatCoord`) stay numeric.

## Data that is not translated here

- **Oblast names** come from `core/…/geo/Regions.kt` as data with both `nameUk` and `nameEn`; the UI currently
  shows `nameEn` (e.g. `Regions.oblast(uid)?.nameEn` in FavoritesScreen, TargetsPanel, MapScreen.describePlace).
  Pick `nameUk` for Ukrainian instead of adding keys; Dutch can use the English (transliterated) names.
- **City names** (`Regions.cities`, English `name` + Ukrainian match stems) and **raion names**
  (`core/…/geo/UkraineRaions.kt`, new file) are data; not translated. `place_in_city` / `place_near_city` /
  `place_offset_from_city` are worded in Ukrainian so the city name never needs declension.
- **Alert titles / oblast names on threats** (`threat.title`, `threat.oblastName`, `marker.message`) come from the
  server (alerts.in.ua, Ukrainian) and are shown verbatim.
- **Target model names** (`TargetProfile.model`: Shahed-136 / Geran-2, Kh-101, Iskander-M, …) are proper names and
  stay as-is, except the generic ones that have keys (`threat_air_defense`, `threat_unknown`,
  `profile_missile_unreported`). Make/role texts of every profile do have keys (`profile_*`).
- `UiState.error` may carry a raw exception message (`e.message`) or the server's `health.lastError`; only the
  fixed messages from `RadarNeboApi` are keyed (`error_*`).

## Deliberately skipped

- Glyph-only strings: tab/section icons (`◎`, `★`, `⚙`, `◐`, `⇄`, `{ }`, `✈`, `⚠`), `✕`, `↓`/`↑`, `–` placeholders, `" ★"`.
- `@Preview` names and everything in `ui/preview/` (preview/test data).
- Storage keys, JSON/API values (`"oblast"`, `"air_raid"`, `"AlertsInUa"`), URLs, paths, system properties, logger names.
- Identifiers: `targetName()` (`"T-" + id`), track ids, alert ids (`"#$it"`), HTTP method/path/host, header names.
- Pure punctuation composition: `"+ ${city.name} · ${oblast}"` suggestion rows, `"${lat}, ${lon}"`, `joinToString(" · ")`.
- `"alerts.in.ua"` shown as a source name (brand).
- Settings strings removed in the in-progress redesign ("Max drones shown on map", "Proximity radius around favorites",
  "Open request inspector", the old developer description) were replaced by their current equivalents.
- The `mission-control/` console UI (separate desktop app with its own module) and the server: out of scope for the
  app's `shared` resources.

## Strings

| Key | Source (file:line) | Original code (first occurrence) | Resource text (en) | Notes |
|---|---|---|---|---|
| `app_name` | app/desktop/src/main/kotlin/com/gusak/lab/radarnebo/desktop/Main.kt:50 | `` title = "RadarNebo", `` | RadarNebo | Brand; not translated. |
| `app_window_requests_title` | app/desktop/src/main/kotlin/com/gusak/lab/radarnebo/desktop/Main.kt:68 | `` title = "RadarNebo · Requests", `` | RadarNebo · Requests | Desktop window title of the request inspector. |
| `nav_map` | shared/…/App.kt:53 | `` Map("Map", "◎"), `` | Map |  |
| `nav_favorites` | shared/…/App.kt:54 | `` Favorites("Favorites", "★"), `` | Favorites |  |
| `nav_settings` | shared/…/App.kt:55 | `` Settings("Settings", "⚙"), `` | Settings |  |
| `common_cancel` | shared/…/ui/FavoritesScreen.kt:193<br>shared/…/ui/MapScreen.kt:866 | `` dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, `` | Cancel |  |
| `common_add` | shared/…/ui/FavoritesScreen.kt:192 | `` confirmButton = { Button(onClick = { point?.let { onConfirm(name, it) } }, enabled = point != null) { Text("Add") } }, `` | Add |  |
| `common_close` | shared/…/ui/MapScreen.kt:740<br>shared/…/ui/GlassDialog.kt:116 | `` TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") } `` | Close | Also the content description of the dialog close button (✕). |
| `common_done` | shared/…/ui/MapScreen.kt:776 | `` TextButton(onClick = onClose) { Text("Done") } `` | Done |  |
| `common_remove` | shared/…/ui/FavoritesScreen.kt:164 | `` TextButton(onClick = { onRemove(favorite.id) }) { Text("Remove") } `` | Remove |  |
| `common_name` | shared/…/ui/FavoritesScreen.kt:187<br>shared/…/ui/MapScreen.kt:846 | `` OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) `` | Name |  |
| `common_open` | shared/…/ui/SettingsScreen.kt:200 | `` OutlinedButton(onClick = onOpenRequests) { Text("Open") } `` | Open |  |
| `common_none` | shared/…/ui/debug/TrafficInspector.kt:239,267 | `` if (body.isEmpty()) Text("(none)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) `` | (none) |  |
| `common_error` | shared/…/ui/MapScreen.kt:684<br>shared/…/ui/debug/TrafficInspector.kt:233 | `` state.error?.let { StatusLine("Error", it, radarColors.danger) } `` | Error |  |
| `unit_km` | shared/…/ui/CinematicToggle.kt:184<br>shared/…/ui/MapScreen.kt:775<br>shared/…/ui/MapScreen.kt:802<br>shared/…/ui/SettingsScreen.kt:136<br>shared/…/ui/TargetsPanel.kt:353<br>shared/…/ui/Format.kt:32<br>shared/…/ui/MapScreen.kt:713 | `` Text("$radiusKm km", color = radarColors.mapBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold) `` | %1$d km |  |
| `unit_km_decimal` | shared/…/ui/Format.kt:33 | `` else -> "${meters / 1_000}.${meters % 1_000 / 100} km" `` | %1$s km | Arg is a preformatted decimal like "1.5" (uk should ideally use a decimal comma: "1,5"). |
| `unit_m` | shared/…/ui/Format.kt:31 | `` meters < 1_000 -> "$meters m" `` | %1$d m |  |
| `unit_seconds` | shared/…/ui/SettingsScreen.kt:161 | `` format = { "$it s" }, `` | %1$d s |  |
| `unit_minutes` | shared/…/ui/TargetsPanel.kt:354 | `` Stat("Tracked for", "$trackedMinutes min", Modifier.weight(1f)) `` | %1$d min |  |
| `unit_minutes_approx` | shared/…/ui/TargetsPanel.kt:676 | `` Text("~${stop.etaMinutes} min", fontWeight = FontWeight.Bold, color = radarColors.warning) `` | ~%1$d min |  |
| `unit_speed_approx` | shared/…/ui/TargetsPanel.kt:349 | `` Stat("Speed", position?.speedKmh?.let { "~${it.toInt()} km/h" } ?: "–", Modifier.weight(1f)) `` | ~%1$d km/h |  |
| `unit_speed_range` | shared/…/ui/TargetsPanel.kt:632<br>shared/…/ui/SettingsScreen.kt:383 | `` profile.cruiseSpeedKmh?.let { LabelValue("Typical speed", "${it.first}–${it.last} km/h") } `` | %1$d–%2$d km/h |  |
| `unit_motion` | shared/…/ui/TargetsPanel.kt:692 | `` return "~${speed.toInt()} km/h$heading" `` | ~%1$d km/h %2$s | %2$s = compass_* abbreviation. Without a heading use unit_speed_approx. |
| `unit_heading` | shared/…/ui/TargetsPanel.kt:350 | `` Stat("Direction", position?.headingDeg?.let { "${compass(it)} · ${it.toInt()}°" } ?: "–", Modifier.weight(1f)) `` | %1$s · %2$d° | %1$s = compass_* abbreviation, %2$d = degrees. |
| `unit_ms` | shared/…/ui/debug/TrafficInspector.kt:179,227 | `` listOfNotNull(exchange.host ?: exchange.remoteAddress, "${exchange.durationMs} ms", bodySize(exchange)).joinToString(" · "), `` | %1$d ms |  |
| `unit_bytes` | shared/…/ui/debug/TrafficInspector.kt:210 | `` if (chars < 1024) "$chars B" else "${(chars / 102.4).toInt() / 10.0} KB" `` | %1$d B |  |
| `unit_kilobytes` | shared/…/ui/debug/TrafficInspector.kt:210 | `` if (chars < 1024) "$chars B" else "${(chars / 102.4).toInt() / 10.0} KB" `` | %1$s KB |  |
| `time_just_now` | shared/…/ui/Format.kt:23 | `` minutes < 1 -> "just now" `` | just now |  |
| `time_minutes_ago` | shared/…/ui/Format.kt:24 | `` minutes < 60 -> "$minutes min ago" `` | %1$d min ago |  |
| `time_hours_minutes_ago` | shared/…/ui/Format.kt:25 | `` else -> "${minutes / 60} h ${minutes % 60} min ago" `` | %1$d h %2$d min ago |  |
| `coordinates_lat_lon` | shared/…/ui/TargetsPanel.kt:697 | `` private fun coordinates(lat: Double, lon: Double) = "${formatCoord(lat)}° N, ${formatCoord(lon)}° E" `` | %1$s° N, %2$s° E |  |
| `compass_n` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | N | List order in compass(): N, NE, E, SE, S, SW, W, NW. |
| `compass_ne` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | NE |  |
| `compass_e` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | E |  |
| `compass_se` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | SE |  |
| `compass_s` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | S |  |
| `compass_sw` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | SW |  |
| `compass_w` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | W |  |
| `compass_nw` | shared/…/ui/TargetsPanel.kt:699 | `` fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 382.5) / 45).toInt() % 8] `` | NW |  |
| `level_red` | shared/…/ui/map/CinematicDirector.kt:66<br>shared/…/ui/map/CinematicDirector.kt:91 | `` append(hit.threat.level.name.uppercase()) `` | Red | ThreatLevel.Red display name; cinematic mode shows it uppercased. |
| `level_yellow` | shared/…/ui/map/CinematicDirector.kt:91 | `` subtitle = "${it.type.label} · ${it.level.name.uppercase()} · ${it.oblastName}", `` | Yellow | ThreatLevel.Yellow display name. |
| `level_red_full` | shared/…/ui/TargetsPanel.kt:328<br>shared/…/ui/TargetsPanel.kt:436 | `` "${track.level.name} level", `` | Red level |  |
| `level_yellow_full` | shared/…/ui/TargetsPanel.kt:328 | `` "${track.level.name} level", `` | Yellow level |  |
| `threat_drones` | core/…/data/AlertsModels.kt:71 | `` Drones("drones", "Drones (Shahed/UAV)"), `` | Drones (Shahed/UAV) | ThreatType.label (core). Shown in favorites, banners, cinematic, filters. |
| `threat_ballistic_missiles` | core/…/data/AlertsModels.kt:72 | `` BallisticMissiles("ballistic_missiles", "Ballistic missiles"), `` | Ballistic missiles |  |
| `threat_cruise_missiles` | core/…/data/AlertsModels.kt:73 | `` CruiseMissiles("cruise_missiles", "Cruise missiles"), `` | Cruise missiles |  |
| `threat_missiles` | core/…/data/AlertsModels.kt:74 | `` UnspecifiedMissiles("unspecified_missiles", "Missiles"), `` | Missiles |  |
| `threat_guided_bombs` | core/…/data/AlertsModels.kt:75 | `` GuidedBombs("guided_aerial_bombs", "Guided aerial bombs"), `` | Guided aerial bombs |  |
| `threat_tactical_aviation` | core/…/data/AlertsModels.kt:76 | `` TacticalAviation("tactic_aircraft_activity", "Tactical aviation"), `` | Tactical aviation |  |
| `threat_strategic_aviation` | core/…/data/AlertsModels.kt:77 | `` StrategicAviation("strategic_aircraft_activity", "Strategic aviation"), `` | Strategic aviation |  |
| `threat_mig31k_departure` | core/…/data/AlertsModels.kt:78 | `` Mig31k("mig31k_departure", "MiG-31K departure"), `` | MiG-31K departure |  |
| `threat_air_defense` | core/…/data/AlertsModels.kt:79<br>core/…/tracking/TargetProfile.kt:148 | `` AirDefense("air_defense", "Air defense active"), `` | Air defense active | Also TargetClassifier.AirDefense.model. |
| `threat_unknown` | core/…/data/AlertsModels.kt:80<br>core/…/tracking/TargetProfile.kt:156 | `` Unknown("unknown", "Unknown threat"); `` | Unknown threat | Also TargetKind.Unknown.label and TargetClassifier.Unknown.model. |
| `location_type_oblast` | shared/…/ui/TargetsPanel.kt:469 | `` listOf(marker.oblastName, marker.locationType).distinct().joinToString(" · "), `` | oblast | Raw alert locationType is shown as-is in the threat detail; map these 4 API values to text. |
| `location_type_raion` | shared/…/ui/TargetsPanel.kt:469 | `` listOf(marker.oblastName, marker.locationType).distinct().joinToString(" · "), `` | district |  |
| `location_type_city` | shared/…/ui/TargetsPanel.kt:469 | `` listOf(marker.oblastName, marker.locationType).distinct().joinToString(" · "), `` | city |  |
| `location_type_hromada` | shared/…/ui/TargetsPanel.kt:469 | `` listOf(marker.oblastName, marker.locationType).distinct().joinToString(" · "), `` | community |  |
| `kind_attack_drone` | core/…/tracking/TargetProfile.kt:15 | `` AttackDrone("Attack drone", 2_000), `` | Attack drone | TargetKind.label (core). |
| `kind_jet_drone` | core/…/tracking/TargetProfile.kt:16 | `` JetDrone("Jet attack drone", 3_000), `` | Jet attack drone |  |
| `kind_decoy_drone` | core/…/tracking/TargetProfile.kt:17 | `` DecoyDrone("Decoy drone", 1_500), `` | Decoy drone |  |
| `kind_recon_drone` | core/…/tracking/TargetProfile.kt:18 | `` ReconDrone("Reconnaissance drone", 2_000), `` | Reconnaissance drone |  |
| `kind_cruise_missile` | core/…/tracking/TargetProfile.kt:20 | `` CruiseMissile("Cruise missile", 50), `` | Cruise missile |  |
| `kind_ballistic_missile` | core/…/tracking/TargetProfile.kt:22 | `` BallisticMissile("Ballistic missile", 40_000), `` | Ballistic missile |  |
| `kind_aeroballistic_missile` | core/…/tracking/TargetProfile.kt:23 | `` AeroballisticMissile("Aeroballistic missile", 20_000), `` | Aeroballistic missile |  |
| `kind_guided_bomb` | core/…/tracking/TargetProfile.kt:25 | `` GuidedBomb("Guided aerial bomb", 5_000), `` | Guided aerial bomb |  |
| `kind_tactical_aircraft` | core/…/tracking/TargetProfile.kt:26 | `` TacticalAircraft("Tactical aviation", 8_000), `` | Tactical aviation |  |
| `kind_strategic_bomber` | core/…/tracking/TargetProfile.kt:27 | `` StrategicBomber("Strategic aviation", 10_000), `` | Strategic aviation |  |
| `kind_mig31k` | core/…/tracking/TargetProfile.kt:28 | `` MiG31K("MiG-31K", 15_000), `` | MiG-31K |  |
| `kind_air_defense` | core/…/tracking/TargetProfile.kt:29 | `` AirDefense("Air defense", null), `` | Air defense |  |
| `profile_missile_unreported` | core/…/tracking/TargetProfile.kt:200 | `` ?: probable(CruiseMissile.copy(model = "Missile, type not reported")) `` | Missile, type not reported | TargetProfile.model override for unspecified missiles. Other model names (Shahed-136, Kh-101...) are proper names and stay untranslated. |
| `profile_shahed_make` | core/…/tracking/TargetProfile.kt:61 | `` make = "HESA (Iran); licence-built as Geran-2 at Alabuga, Russia", `` | HESA (Iran); licence-built as Geran-2 at Alabuga, Russia |  |
| `profile_shahed_role` | core/…/tracking/TargetProfile.kt:62 | `` role = "One-way attack loitering munition, ~50 kg warhead", `` | One-way attack loitering munition, ~50 kg warhead |  |
| `profile_gerbera_role` | core/…/tracking/TargetProfile.kt:70 | `` role = "Decoy to saturate air defence; sometimes reconnaissance or a small warhead", `` | Decoy to saturate air defence; sometimes reconnaissance or a small warhead |  |
| `profile_jet_shahed_make` | core/…/tracking/TargetProfile.kt:77 | `` make = "HESA (Iran); built in Russia as Geran-3", `` | HESA (Iran); built in Russia as Geran-3 |  |
| `profile_jet_shahed_role` | core/…/tracking/TargetProfile.kt:78 | `` role = "Jet-powered one-way attack drone", `` | Jet-powered one-way attack drone |  |
| `profile_recon_drone_role` | core/…/tracking/TargetProfile.kt:86 | `` role = "Reconnaissance and artillery spotting near the front", `` | Reconnaissance and artillery spotting near the front |  |
| `profile_cruise_missile_make` | core/…/tracking/TargetProfile.kt:93 | `` make = "Russia; air-launched from Tu-95MS, Kalibr from ships and submarines", `` | Russia; air-launched from Tu-95MS, Kalibr from ships and submarines |  |
| `profile_cruise_missile_role` | core/…/tracking/TargetProfile.kt:94 | `` role = "Subsonic cruise missile flying low along a winding route", `` | Subsonic cruise missile flying low along a winding route |  |
| `profile_ballistic_missile_make` | core/…/tracking/TargetProfile.kt:101 | `` make = "Russia; KN-23 supplied by North Korea", `` | Russia; KN-23 supplied by North Korea |  |
| `profile_ballistic_missile_role` | core/…/tracking/TargetProfile.kt:102 | `` role = "Short-range ballistic missile, minutes from launch to impact", `` | Short-range ballistic missile, minutes from launch to impact |  |
| `profile_kinzhal_role` | core/…/tracking/TargetProfile.kt:110 | `` role = "Air-launched aeroballistic missile carried by the MiG-31K", `` | Air-launched aeroballistic missile carried by the MiG-31K |  |
| `profile_guided_bomb_role` | core/…/tracking/TargetProfile.kt:118 | `` role = "Glide bomb released by aircraft 40–70 km from its target", `` | Glide bomb released by aircraft 40–70 km from its target |  |
| `profile_tactical_aircraft_role` | core/…/tracking/TargetProfile.kt:126 | `` role = "Fighter-bombers launching guided bombs and missiles near the front", `` | Fighter-bombers launching guided bombs and missiles near the front |  |
| `profile_strategic_bomber_role` | core/…/tracking/TargetProfile.kt:134 | `` role = "Long-range bombers that launch Kh-101 and Kh-22 cruise missiles", `` | Long-range bombers that launch Kh-101 and Kh-22 cruise missiles |  |
| `profile_mig31k_role` | core/…/tracking/TargetProfile.kt:142 | `` role = "Interceptor modified to carry the Kinzhal; its take-off triggers a nationwide alert", `` | Interceptor modified to carry the Kinzhal; its take-off triggers a nationwide alert |  |
| `profile_air_defense_make` | core/…/tracking/TargetProfile.kt:149 | `` make = "Ukrainian Air Force", `` | Ukrainian Air Force |  |
| `profile_air_defense_role` | core/…/tracking/TargetProfile.kt:150 | `` role = "Air defense is engaging targets nearby; stay in shelter", `` | Air defense is engaging targets nearby; stay in shelter |  |
| `profile_unknown_role` | core/…/tracking/TargetProfile.kt:158 | `` role = "Threat type not reported", `` | Threat type not reported |  |
| `profile_make_russia` | core/…/tracking/TargetProfile.kt:69,85,109,117,125,133,141 | `` make = "Russia", `` | Russia | Shared by every profile whose make is plain "Russia" (Gerbera, Orlan, Kinzhal, KAB, Su-34, Tu-95, MiG-31K). The "–" make of Unknown is a glyph, skipped. |
| `favorites_title` | shared/…/App.kt:111 | `` title = "Favorite places", `` | Favorite places | GlassDialog title. |
| `favorites_subtitle` | shared/…/App.kt:112 | `` subtitle = "Threats within ${state.settings.proximityRadiusKm} km, or region-wide threats in the same oblast, are flagged.", `` | Threats within %1$d km, or region-wide threats in the same oblast, are flagged. | GlassDialog subtitle. |
| `favorites_tip` | shared/…/ui/FavoritesScreen.kt:99 | `` "Tip: long-press the map to add any point.", `` | Tip: long-press the map to add any point. |  |
| `favorites_saved_places` | shared/…/ui/FavoritesScreen.kt:109 | `` "SAVED PLACES", `` | SAVED PLACES | Section label, uppercase in source. |
| `favorites_search_placeholder` | shared/…/ui/FavoritesScreen.kt:72 | `` placeholder = { Text("e.g. Kyiv, Lviv, Odesa") }, `` | e.g. Kyiv, Lviv, Odesa | City search matches the English Regions.cities names only, so the uk example keeps Latin spelling; switch to Київ, Львів, Одеса once search also matches Ukrainian names. |
| `favorites_search_city` | shared/…/ui/FavoritesScreen.kt:71 | `` label = { Text("Search city") }, `` | Search city |  |
| `favorites_add_by_coordinates` | shared/…/ui/FavoritesScreen.kt:104,184<br>shared/…/ui/FavoritesScreen.kt:184 | `` TextButton(onClick = { showCustom = true }) { Text("Add by coordinates") } `` | Add by coordinates |  |
| `favorites_empty_title` | shared/…/ui/FavoritesScreen.kt:121 | `` Text("No favorites yet", style = MaterialTheme.typography.titleSmall) `` | No favorites yet |  |
| `favorites_empty_description` | shared/…/ui/FavoritesScreen.kt:123 | `` "Add your home, family or work locations to be warned when a threat comes near.", `` | Add your home, family or work locations to be warned when a threat comes near. |  |
| `favorites_status_clear` | shared/…/ui/FavoritesScreen.kt:158 | `` if (hit == null) "Clear" else "${hit.threat.type.label} · " + `` | Clear | All-clear status of a favorite, NOT the verb "clear" (see inspector_clear). |
| `favorites_status_region_wide` | shared/…/ui/FavoritesScreen.kt:159 | `` if (hit.insideRegion) "region-wide in ${hit.threat.oblastName}" else "${hit.distanceKm.toInt()} km – ${hit.threat.title}", `` | %1$s · region-wide in %2$s | %1$s = threat type, %2$s = oblast name (from data). |
| `favorites_status_distance` | shared/…/ui/FavoritesScreen.kt:159 | `` if (hit.insideRegion) "region-wide in ${hit.threat.oblastName}" else "${hit.distanceKm.toInt()} km – ${hit.threat.title}", `` | %1$s · %2$d km – %3$s | %1$s = threat type, %2$d = km, %3$s = alert location title (data). |
| `favorites_quick_add` | shared/…/ui/FavoritesScreen.kt:128 | `` Text("Quick add", style = MaterialTheme.typography.labelLarge) `` | Quick add |  |
| `favorites_latitude` | shared/…/ui/FavoritesScreen.kt:188 | `` OutlinedTextField(lat, { lat = it }, label = { Text("Latitude (44–53)") }, singleLine = true) `` | Latitude (44–53) |  |
| `favorites_longitude` | shared/…/ui/FavoritesScreen.kt:189 | `` OutlinedTextField(lon, { lon = it }, label = { Text("Longitude (22–41)") }, singleLine = true) `` | Longitude (22–41) |  |
| `favorites_default_name` | shared/…/domain/usecase/FavoriteUseCases.kt:15 | `` val trimmed = name.trim().ifBlank { "Point ${point.lat.format()}, ${point.lon.format()}" } `` | Point %1$s, %2$s | Fallback name stored with a nameless favorite (persisted, so it stays in the language it was created in). |
| `settings_title` | shared/…/App.kt:122 | `` title = "Settings", `` | Settings | GlassDialog title. |
| `settings_category_appearance` | shared/…/ui/SettingsScreen.kt:83 | `` SettingsSection("Appearance", "◐") { `` | Appearance |  |
| `settings_category_map` | shared/…/ui/SettingsScreen.kt:104 | `` SettingsSection("Map", "◎") { `` | Map |  |
| `settings_category_targets` | _(new, not in code yet)_ |  | Targets | Requested for the redesign; not in code yet. |
| `settings_category_alerts` | _(new, not in code yet)_ |  | Alerts | Requested for the redesign; not in code yet. |
| `settings_category_alerts_tracking` | shared/…/ui/SettingsScreen.kt:130 | `` SettingsSection("Alerts & tracking", "⚠") { `` | Alerts & tracking |  |
| `settings_category_server` | shared/…/ui/SettingsScreen.kt:166<br>shared/…/ui/MapScreen.kt:663 | `` SettingsSection("Server", "⇄") { `` | Server |  |
| `settings_category_developer` | shared/…/ui/SettingsScreen.kt:198 | `` SettingsSection("Developer", "{ }") { `` | Developer |  |
| `settings_category_about` | _(new, not in code yet)_ |  | About | Requested for the redesign; not in code yet. |
| `settings_category_target_types` | shared/…/ui/SettingsScreen.kt:205 | `` SettingsSection("Target types", "✈") { `` | Target types |  |
| `settings_theme` | shared/…/ui/SettingsScreen.kt:84 | `` SettingBlock("Theme", "System follows your device's light or dark setting.") { `` | Theme |  |
| `settings_theme_description` | shared/…/ui/SettingsScreen.kt:84 | `` SettingBlock("Theme", "System follows your device's light or dark setting.") { `` | System follows your device's light or dark setting. |  |
| `theme_system` | shared/…/ui/SettingsScreen.kt:90 | `` ThemeMode.System -> "System" `` | System |  |
| `theme_light` | shared/…/ui/SettingsScreen.kt:91 | `` ThemeMode.Light -> "Light" `` | Light |  |
| `theme_dark` | shared/…/ui/SettingsScreen.kt:92 | `` ThemeMode.Dark -> "Dark" `` | Dark |  |
| `settings_color_scheme` | shared/…/ui/SettingsScreen.kt:99 | `` SettingBlock("Color scheme", "Colours of the whole app and the map. Every scheme has a light and a dark variant.") { `` | Color scheme |  |
| `settings_color_scheme_description` | shared/…/ui/SettingsScreen.kt:99 | `` SettingBlock("Color scheme", "Colours of the whole app and the map. Every scheme has a light and a dark variant.") { `` | Colours of the whole app and the map. Every scheme has a light and a dark variant. | Source mixes "Color scheme" (US) and "Colours" (UK); English kept verbatim, consider unifying. |
| `palette_radar` | shared/…/ui/SettingsScreen.kt:324 | `` ColorPalette.Radar -> "Radar" `` | Radar |  |
| `palette_nord` | shared/…/ui/SettingsScreen.kt:325 | `` ColorPalette.Nord -> "Nord" `` | Nord | Name of a popular theme; untranslated. |
| `palette_dracula` | shared/…/ui/SettingsScreen.kt:326 | `` ColorPalette.Dracula -> "Dracula" `` | Dracula | Untranslated. |
| `palette_solarized` | shared/…/ui/SettingsScreen.kt:327 | `` ColorPalette.Solarized -> "Solarized" `` | Solarized | Untranslated. |
| `palette_catppuccin` | shared/…/ui/SettingsScreen.kt:328 | `` ColorPalette.Catppuccin -> "Catppuccin" `` | Catppuccin | Untranslated. |
| `palette_obsidian_plum` | shared/…/ui/SettingsScreen.kt:329 | `` ColorPalette.ObsidianPlum -> "Obsidian Plum" `` | Obsidian Plum |  |
| `palette_smoked_copper` | shared/…/ui/SettingsScreen.kt:330 | `` ColorPalette.SmokedCopper -> "Smoked Copper" `` | Smoked Copper |  |
| `palette_blue_honor` | shared/…/ui/SettingsScreen.kt:331 | `` ColorPalette.BlueHonor -> "Blue Honor" `` | Blue Honor | Kept untranslated: unclear whether it is a brand reference (Honor) or descriptive. |
| `settings_map_background` | shared/…/ui/SettingsScreen.kt:105 | `` SettingBlock("Background", "What the map shows under the oblasts. Map and satellite tiles are downloaded from Esri.") { `` | Background |  |
| `settings_map_background_description` | shared/…/ui/SettingsScreen.kt:105 | `` SettingBlock("Background", "What the map shows under the oblasts. Map and satellite tiles are downloaded from Esri.") { `` | What the map shows under the oblasts. Map and satellite tiles are downloaded from Esri. |  |
| `map_background_none` | shared/…/ui/SettingsScreen.kt:111 | `` MapBackground.None -> "No map" `` | No map |  |
| `map_background_streets` | shared/…/ui/SettingsScreen.kt:112 | `` MapBackground.Streets -> "Map" `` | Map |  |
| `map_background_satellite` | shared/…/ui/SettingsScreen.kt:113 | `` MapBackground.Satellite -> "Satellite" `` | Satellite |  |
| `settings_max_drones` | shared/…/ui/SettingsScreen.kt:121 | `` title = "Drones on the map", `` | Drones on the map |  |
| `settings_max_drones_description` | shared/…/ui/SettingsScreen.kt:122 | `` description = "The most drones drawn at once; fewer keeps the map readable and fast.", `` | The most drones drawn at once; fewer keeps the map readable and fast. |  |
| `settings_warning_radius` | shared/…/ui/SettingsScreen.kt:132<br>shared/…/ui/MapScreen.kt:774 | `` title = "Warning radius", `` | Warning radius |  |
| `settings_warning_radius_description` | shared/…/ui/SettingsScreen.kt:133 | `` description = "Threats this close to a favorite place raise a warning.", `` | Threats this close to a favorite place raise a warning. |  |
| `settings_drones_only` | shared/…/ui/SettingsScreen.kt:141 | `` title = "Drones only", `` | Drones only |  |
| `settings_drones_only_description` | shared/…/ui/SettingsScreen.kt:142 | `` description = "Hide missiles, aviation and other threat types.", `` | Hide missiles, aviation and other threat types. |  |
| `settings_refresh_interval` | shared/…/ui/SettingsScreen.kt:157 | `` title = "Refresh interval", `` | Refresh interval |  |
| `settings_refresh_interval_description` | shared/…/ui/SettingsScreen.kt:158 | `` description = "The server refreshes its data every 10–15 s; polling faster only uses more battery.", `` | The server refreshes its data every 10–15 s; polling faster only uses more battery. |  |
| `settings_connection` | shared/…/ui/SettingsScreen.kt:168 | `` "Connection", `` | Connection |  |
| `settings_server_description` | shared/…/ui/SettingsScreen.kt:169 | `` "RadarNebo gets alerts and tracked targets from a RadarNebo server, which keeps the history and does the " + `` | RadarNebo gets alerts and tracked targets from a RadarNebo server, which keeps the history and does the tracking. Run your own and enter its address; ask its operator for an access key if it needs one. | Concatenated literals in code. |
| `settings_server_url` | shared/…/ui/SettingsScreen.kt:175 | `` label = { Text("Server URL") }, `` | Server URL |  |
| `settings_access_key` | shared/…/ui/SettingsScreen.kt:183 | `` label = { Text("Access key (optional)") }, `` | Access key (optional) |  |
| `settings_save_reconnect` | shared/…/ui/SettingsScreen.kt:193 | `` ) { Text("Save & reconnect") } `` | Save & reconnect |  |
| `settings_request_inspector` | shared/…/ui/SettingsScreen.kt:199 | `` SettingRow("Request inspector", "Every request the app sends to its server and the JSON it gets back.") { `` | Request inspector |  |
| `settings_request_inspector_description` | shared/…/ui/SettingsScreen.kt:199 | `` SettingRow("Request inspector", "Every request the app sends to its server and the JSON it gets back.") { `` | Every request the app sends to its server and the JSON it gets back. |  |
| `settings_target_types_description` | shared/…/ui/SettingsScreen.kt:207 | `` "How each threat is drawn on the map. Red is a red-level alert, amber a yellow-level one.", `` | How each threat is drawn on the map. Red is a red-level alert, amber a yellow-level one. |  |
| `map_cinematic_shot` | shared/…/ui/MapScreen.kt:548 | `` "SHOT ${director.shotIndex + 1}/${director.shotCount} · ${if (state.proximity.isEmpty()) "PATROL" else "${state.proximity.size} POI ALERT(S)"}", `` | SHOT %1$d/%2$d · %3$s | %3$s = map_cinematic_patrol or the map_cinematic_poi_alerts plural. |
| `map_cinematic_patrol` | shared/…/ui/MapScreen.kt:548 | `` "SHOT ${director.shotIndex + 1}/${director.shotCount} · ${if (state.proximity.isEmpty()) "PATROL" else "${state.proximity.size} POI ALERT(S)"}", `` | PATROL |  |
| `status_simulated` | shared/…/ui/MapScreen.kt:562 | `` if (state.simulated) append("SIMULATED DATA · not the real situation · ") `` | SIMULATED DATA · not the real situation | Code appends " · " after it; the separator is not part of the resource. |
| `status_showing` | shared/…/ui/MapScreen.kt:564 | `` if (state.threats.size > state.mapThreats.size) append(" (showing ${state.mapThreats.size})") `` | (showing %1$d) | Code prepends a space. |
| `status_updated_at` | shared/…/ui/MapScreen.kt:565 | `` state.lastUpdated?.let { append(" · updated ${formatTime(it)}") } `` | updated %1$s | %1$s = HH:mm. |
| `status_reconnecting` | shared/…/ui/MapScreen.kt:603 | `` trying -> "Reconnecting…" `` | Reconnecting… |  |
| `status_retrying_in` | shared/…/ui/MapScreen.kt:604 | `` secondsLeft != null -> "Retrying in ${secondsLeft}s" `` | Retrying in %1$ds |  |
| `status_not_connected` | shared/…/ui/MapScreen.kt:605 | `` else -> "Not connected" `` | Not connected |  |
| `status_reconnect` | shared/…/ui/MapScreen.kt:612 | `` "Reconnect", `` | Reconnect |  |
| `server_state_connecting` | shared/…/ui/MapScreen.kt:627 | `` Connecting("connecting…"), `` | connecting… | ServerState.label; the server card capitalises the first letter. |
| `server_state_live` | shared/…/ui/MapScreen.kt:628 | `` Live("server live"), `` | server live |  |
| `server_state_degraded` | shared/…/ui/MapScreen.kt:630 | `` Degraded("server up, its data source is failing"), `` | server up, its data source is failing |  |
| `server_state_offline` | shared/…/ui/MapScreen.kt:631 | `` Offline("no data from the server"); `` | no data from the server |  |
| `status_address` | shared/…/ui/MapScreen.kt:665 | `` StatusLine("Address", state.settings.serverUrl) `` | Address |  |
| `status_status` | shared/…/ui/MapScreen.kt:666 | `` StatusLine("Status", server.label.replaceFirstChar { it.uppercase() }) `` | Status |  |
| `status_last_data` | shared/…/ui/MapScreen.kt:667 | `` state.lastUpdated?.let { StatusLine("Last data", formatClock(it.toEpochMilliseconds())) } `` | Last data |  |
| `status_source` | shared/…/ui/MapScreen.kt:670 | `` "Source", `` | Source |  |
| `status_source_simulation` | shared/…/ui/MapScreen.kt:672 | `` health.simulated -> "Simulation (not the real situation)" `` | Simulation (not the real situation) |  |
| `status_upstream` | shared/…/ui/MapScreen.kt:678 | `` StatusLine("Upstream", "${health.upstreamRequestsLastMinute} of $budget requests in the last minute") `` | Upstream |  |
| `status_upstream_value` | shared/…/ui/MapScreen.kt:678 | `` StatusLine("Upstream", "${health.upstreamRequestsLastMinute} of $budget requests in the last minute") `` | %1$d of %2$d requests in the last minute |  |
| `status_polled` | shared/…/ui/MapScreen.kt:680 | `` if (health.pollIntervalSeconds > 0) StatusLine("Polled", "every ${health.pollIntervalSeconds} s") `` | Polled |  |
| `status_polled_value` | shared/…/ui/MapScreen.kt:680 | `` if (health.pollIntervalSeconds > 0) StatusLine("Polled", "every ${health.pollIntervalSeconds} s") `` | every %1$d s |  |
| `status_last_poll` | shared/…/ui/MapScreen.kt:681 | `` health.lastAttemptAt?.let { StatusLine("Last poll", formatClock(it)) } `` | Last poll |  |
| `status_source_error` | shared/…/ui/MapScreen.kt:682 | `` health.lastError?.let { StatusLine("Source error", it, radarColors.danger) } `` | Source error |  |
| `status_server_hint` | shared/…/ui/MapScreen.kt:687 | `` "Is the server running? Start it with ./gradlew :mission-control:server:run, or change its address in Settings.", `` | Is the server running? Start it with ./gradlew :mission-control:server:run, or change its address in Settings. |  |
| `status_show_requests` | shared/…/ui/MapScreen.kt:692 | `` if (onOpenRequests != null) TextButton(onClick = onOpenRequests) { Text("Show requests") } `` | Show requests |  |
| `error_unauthorized` | core/…/data/RadarNeboApi.kt:80 | `` HttpStatusCode.Unauthorized -> FetchResult.Failure("The server needs a valid access key (401)", backOff = true) `` | The server needs a valid access key (401) | FetchResult.Failure messages (core) end up in UiState.error and are shown in the status strip/card. |
| `error_rate_limited` | core/…/data/RadarNeboApi.kt:81 | `` HttpStatusCode.TooManyRequests -> FetchResult.Failure("Too many requests, slowing down (429)", backOff = true) `` | Too many requests, slowing down (429) |  |
| `error_server_starting` | core/…/data/RadarNeboApi.kt:82 | `` HttpStatusCode.ServiceUnavailable -> FetchResult.Failure("The server is starting up, no data yet (503)") `` | The server is starting up, no data yet (503) |  |
| `error_unexpected_response` | core/…/data/RadarNeboApi.kt:83 | `` else -> FetchResult.Failure("Unexpected response ${response.status.value}") `` | Unexpected response %1$d |  |
| `error_network` | core/…/data/RadarNeboApi.kt:88 | `` FetchResult.Failure(e.message ?: "Network error") `` | Network error | Fallback only; e.message (raw exception text) is shown when present and is not translatable. |
| `map_threats_near_you` | shared/…/ui/MapScreen.kt:709 | `` Text("Threats near your locations", fontWeight = FontWeight.Bold, color = Color.White) `` | Threats near your locations |  |
| `map_proximity_region_wide` | shared/…/ui/MapScreen.kt:713 | `` if (hit.insideRegion) "region-wide alert" else "${hit.distanceKm.toInt()} km", `` | %1$s: %2$s – region-wide alert | %1$s = favorite name, %2$s = threat type. |
| `map_proximity_distance` | shared/…/ui/MapScreen.kt:712 | `` "${hit.favorite.name}: ${hit.threat.type.label} – " + `` | %1$s: %2$s – %3$d km |  |
| `map_favorite_clear_within` | shared/…/ui/MapScreen.kt:734 | `` if (hit == null) "Clear within ${state.settings.proximityRadiusKm} km" `` | Clear within %1$d km |  |
| `map_favorite_hit_region_wide` | shared/…/ui/MapScreen.kt:735 | `` else "${hit.threat.type.label} – ${if (hit.insideRegion) "region-wide" else "${hit.distanceKm.toInt()} km away"}", `` | %1$s – region-wide | %1$s = threat type. |
| `map_favorite_hit_distance` | shared/…/ui/MapScreen.kt:735 | `` else "${hit.threat.type.label} – ${if (hit.insideRegion) "region-wide" else "${hit.distanceKm.toInt()} km away"}", `` | %1$s – %2$d km away |  |
| `place_in_city` | shared/…/ui/MapScreen.kt:753 | `` city.name to "In ${city.name}" `` | In %1$s | %1$s = city name (English, from Regions data). |
| `place_near_city` | shared/…/ui/MapScreen.kt:755 | `` "Near ${city.name}" to "${km.roundToInt()} km ${compass(GeoMath.bearingDeg(cityPoint, point))} of ${city.name}" `` | Near %1$s | Becomes the suggested favorite name. uk uses a colon to avoid declining the city name. |
| `place_offset_from_city` | shared/…/ui/MapScreen.kt:755 | `` "Near ${city.name}" to "${km.roundToInt()} km ${compass(GeoMath.bearingDeg(cityPoint, point))} of ${city.name}" `` | %1$d km %2$s of %3$s | %2$s = compass_*; uk reads "12 км на ПнСх від: Умань" to avoid case inflection. |
| `map_warning_radius_hint_none` | shared/…/ui/MapScreen.kt:781 | `` 0 -> "Threats this close to a favorite place raise a warning. Long-press the map to add one." `` | Threats this close to a favorite place raise a warning. Long-press the map to add one. |  |
| `map_warning_radius_hint_one` | shared/…/ui/MapScreen.kt:782 | `` 1 -> "You're warned when a threat comes this close to your favorite place." `` | You're warned when a threat comes this close to your favorite place. | Used when favoriteCount == 1. |
| `map_add_favorite_title` | shared/…/ui/MapScreen.kt:828 | `` Text("Add this place to favorites?", style = MaterialTheme.typography.titleMedium) `` | Add this place to favorites? |  |
| `map_outside_ukraine` | shared/…/ui/MapScreen.kt:837 | `` "Outside Ukraine: no alerts are tracked here.", `` | Outside Ukraine: no alerts are tracked here. |  |
| `map_add_favorite_hint` | shared/…/ui/MapScreen.kt:861 | `` "You'll be warned when a threat comes within $proximityRadiusKm km.", `` | You'll be warned when a threat comes within %1$d km. |  |
| `map_add_to_favorites` | shared/…/ui/MapScreen.kt:867 | `` TextButton(onClick = confirm, enabled = canAdd) { Text("Add to favorites") } `` | Add to favorites |  |
| `map_route_stop_label` | shared/…/ui/map/UkraineMap.kt:725 | `` "${index + 1}. ${stop.place.name} · ~${stop.etaMinutes} min", `` | %1$d. %2$s · ~%3$d min | Drawn on the map canvas. |
| `map_prediction_label` | shared/…/ui/map/UkraineMap.kt:775 | `` val text = "${prediction.place.name} · ~${prediction.etaMinutes} min" `` | %1$s · ~%2$d min | Drawn on the map canvas. |
| `map_attribution_carto` | shared/…/ui/map/MapTiles.kt:56,57 | `` DarkStreets(BUNDLED_MAX_ZOOM, "Map © CARTO, © OpenStreetMap contributors", "bundled:carto-dark"), `` | Map © CARTO, © OpenStreetMap contributors | TileSource attribution shown on the map. |
| `map_attribution_esri` | shared/…/ui/map/MapTiles.kt:58 | `` Satellite(18, "Imagery © Esri, Maxar, Earthstar Geographics", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", isImager... `` | Imagery © Esri, Maxar, Earthstar Geographics |  |
| `map_attribution_nasa` | shared/…/ui/map/MapTiles.kt:60 | `` NightSatellite(8, "Imagery: NASA Black Marble, via NASA GIBS", "bundled:nasa-night", extension = "jpg", isImagery = true); `` | Imagery: NASA Black Marble, via NASA GIBS |  |
| `cd_exit_cinematic` | shared/…/ui/CinematicToggle.kt:70 | `` .semantics { contentDescription = if (cinematic) "Exit cinematic mode" else "Enter cinematic mode" }, `` | Exit cinematic mode |  |
| `cd_enter_cinematic` | shared/…/ui/CinematicToggle.kt:70 | `` .semantics { contentDescription = if (cinematic) "Exit cinematic mode" else "Enter cinematic mode" }, `` | Enter cinematic mode |  |
| `cd_hide_targets` | shared/…/ui/CinematicToggle.kt:145 | `` .semantics { contentDescription = if (open) "Hide targets" else "Show tracked targets" }, `` | Hide targets |  |
| `cd_show_targets` | shared/…/ui/CinematicToggle.kt:145 | `` .semantics { contentDescription = if (open) "Hide targets" else "Show tracked targets" }, `` | Show tracked targets |  |
| `cd_warning_radius` | shared/…/ui/CinematicToggle.kt:170 | `` .semantics { contentDescription = "Warning radius, $radiusKm km" }, `` | Warning radius, %1$d km |  |
| `cd_server_requests` | shared/…/ui/CinematicToggle.kt:200 | `` .semantics { contentDescription = "Requests to the server" }, `` | Requests to the server |  |
| `cinematic_threat_near` | shared/…/ui/map/CinematicDirector.kt:62 | `` title = "THREAT NEAR ${hit.favorite.name.uppercase()}", `` | THREAT NEAR %1$s | %1$s = favorite name, uppercased by code. |
| `cinematic_region_wide` | shared/…/ui/map/CinematicDirector.kt:68 | `` append(if (hit.insideRegion) "region-wide (${hit.threat.oblastName})" else "${hit.distanceKm.toInt()} km – ${hit.threat.title}") `` | region-wide (%1$s) |  |
| `cinematic_distance` | shared/…/ui/map/CinematicDirector.kt:68 | `` append(if (hit.insideRegion) "region-wide (${hit.threat.oblastName})" else "${hit.distanceKm.toInt()} km – ${hit.threat.title}") `` | %1$d km – %2$s |  |
| `cinematic_favorite_clear` | shared/…/ui/map/CinematicDirector.kt:80 | `` title = "${it.name.uppercase()} · CLEAR", `` | %1$s · CLEAR |  |
| `cinematic_no_threats_within` | shared/…/ui/map/CinematicDirector.kt:81 | `` subtitle = "No threats within $radiusKm km", `` | No threats within %1$d km |  |
| `cinematic_tracking` | shared/…/ui/map/CinematicDirector.kt:90 | `` title = "TRACKING · ${it.title.uppercase()}", `` | TRACKING · %1$s |  |
| `cinematic_overview` | shared/…/ui/map/CinematicDirector.kt:100 | `` title = "UKRAINE · OVERVIEW", `` | UKRAINE · OVERVIEW |  |
| `targets_title` | shared/…/ui/TargetsPanel.kt:185 | `` PanelHeader("Targets", "$active active · ${allowed.size} tracked" + if (hidden > 0) " · $hidden hidden" else "", onClose) `` | Targets |  |
| `targets_summary` | shared/…/ui/TargetsPanel.kt:185 | `` PanelHeader("Targets", "$active active · ${allowed.size} tracked" + if (hidden > 0) " · $hidden hidden" else "", onClose) `` | %1$d active · %2$d tracked | uk: plural agreement is approximate for counts ending in 1; see note in md. |
| `targets_summary_hidden` | shared/…/ui/TargetsPanel.kt:185 | `` PanelHeader("Targets", "$active active · ${allowed.size} tracked" + if (hidden > 0) " · $hidden hidden" else "", onClose) `` | %1$d hidden | Code joins with " · ". |
| `targets_filter_active` | shared/…/ui/TargetsPanel.kt:187 | `` FilterChip(activeOnly, onClick = { activeOnly = true }, label = { Text("Active ($active)") }) `` | Active (%1$d) |  |
| `targets_filter_all` | shared/…/ui/TargetsPanel.kt:188<br>shared/…/ui/debug/TrafficInspector.kt:104 | `` FilterChip(!activeOnly, onClick = { activeOnly = false }, label = { Text("All (${allowed.size})") }) `` | All (%1$d) |  |
| `targets_filter` | shared/…/ui/TargetsPanel.kt:192 | `` label = { Text(if (filter.isActive) "Filter · $hidden" else "Filter") }, `` | Filter |  |
| `targets_filter_count` | shared/…/ui/TargetsPanel.kt:192 | `` label = { Text(if (filter.isActive) "Filter · $hidden" else "Filter") }, `` | Filter · %1$d |  |
| `targets_all_hidden` | shared/…/ui/TargetsPanel.kt:206 | `` "Every target here is hidden by the filter." `` | Every target here is hidden by the filter. |  |
| `targets_empty` | shared/…/ui/TargetsPanel.kt:208 | `` "No drone targets tracked yet. Targets appear when a drone alert is followed by another one nearby." `` | No drone targets tracked yet. Targets appear when a drone alert is followed by another one nearby. |  |
| `targets_filter_probable` | shared/…/ui/TargetsPanel.kt:235 | `` label = { Text("Probable types ($probable)") }, `` | Probable types (%1$d) |  |
| `targets_filter_kind` | shared/…/ui/TargetsPanel.kt:242 | `` label = { Text("${kind.label} (${counts[kind] ?: 0})") }, `` | %1$s (%2$d) | %1$s = kind_*. |
| `targets_show_all` | shared/…/ui/TargetsPanel.kt:247 | `` TextButton(onClick = { onChange(TargetFilter()) }) { Text("Show all") } `` | Show all |  |
| `targets_model_probable` | shared/…/ui/TargetsPanel.kt:296 | `` listOfNotNull(profile.model + if (profile.source == TargetProfile.Source.Probable) " (probable)" else "", motionLabel(motion), reportsLabel(track.samples.size)).joinTo... `` | %1$s (probable) |  |
| `targets_row_prediction` | shared/…/ui/TargetsPanel.kt:302 | `` "→ ${it.place.name} in ~${it.etaMinutes} min (${it.distanceKm.toInt()} km)", `` | → %1$s in ~%2$d min (%3$d km) |  |
| `targets_detail_title` | shared/…/ui/TargetsPanel.kt:325 | `` title = "Target ${targetName(track.id)}", `` | Target %1$s | %1$s = targetName() like "T-42" (identifier, untranslated). |
| `targets_state_active` | shared/…/ui/TargetsPanel.kt:329<br>shared/…/ui/TargetsPanel.kt:436 | `` if (track.active) "active" else "cleared", `` | active |  |
| `targets_state_cleared` | shared/…/ui/TargetsPanel.kt:329 | `` if (track.active) "active" else "cleared", `` | cleared |  |
| `targets_back_all` | shared/…/ui/TargetsPanel.kt:332,438 | `` leading = { TextButton(onClick = onBack) { Text("‹ All targets") } }, `` | ‹ All targets |  |
| `targets_track_id` | shared/…/ui/TargetsPanel.kt:342 | `` id = "${targetName(track.id)} · track ${track.id}", `` | %1$s · track %2$s |  |
| `targets_stat_speed` | shared/…/ui/TargetsPanel.kt:349 | `` Stat("Speed", position?.speedKmh?.let { "~${it.toInt()} km/h" } ?: "–", Modifier.weight(1f)) `` | Speed |  |
| `targets_stat_direction` | shared/…/ui/TargetsPanel.kt:350 | `` Stat("Direction", position?.headingDeg?.let { "${compass(it)} · ${it.toInt()}°" } ?: "–", Modifier.weight(1f)) `` | Direction |  |
| `targets_stat_distance_flown` | shared/…/ui/TargetsPanel.kt:353 | `` Stat("Distance flown", "${flownKm.toInt()} km", Modifier.weight(1f)) `` | Distance flown |  |
| `targets_stat_tracked_for` | shared/…/ui/TargetsPanel.kt:354 | `` Stat("Tracked for", "$trackedMinutes min", Modifier.weight(1f)) `` | Tracked for | Value is a duration (unit_minutes); nl "Gevolgd gedurende" is more literal but long. |
| `targets_stat_altitude` | shared/…/ui/TargetsPanel.kt:357 | `` Stat("Altitude (typical for its class)", "~${formatAltitude(altitude)}", Modifier.fillMaxWidth()) `` | Altitude (typical for its class) |  |
| `targets_altitude_approx` | shared/…/ui/TargetsPanel.kt:357 | `` Stat("Altitude (typical for its class)", "~${formatAltitude(altitude)}", Modifier.fillMaxWidth()) `` | ~%1$s | %1$s = unit_m / unit_km / unit_km_decimal. |
| `targets_prediction` | shared/…/ui/TargetsPanel.kt:372 | `` Text("Prediction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) `` | Prediction |  |
| `targets_prediction_eta` | shared/…/ui/TargetsPanel.kt:375 | `` "${prediction.place.name} in ~${prediction.etaMinutes} min", `` | %1$s in ~%2$d min |  |
| `targets_prediction_detail` | shared/…/ui/TargetsPanel.kt:380 | `` "${prediction.distanceKm.toInt()} km ahead at ~${prediction.speedKmh.toInt()} km/h" + `` | %1$d km ahead at ~%2$d km/h |  |
| `targets_prediction_favorite` | shared/…/ui/TargetsPanel.kt:381 | `` if (prediction.place.isFavorite) " · one of your favorites" else "", `` | one of your favorites | Code prepends " · ". |
| `targets_no_settlement` | shared/…/ui/TargetsPanel.kt:386 | `` if (track.active) "No settlement within ${TrackPredictor.DEFAULT_RANGE_KM.toInt()} km on its current heading" `` | No settlement within %1$d km on its current heading |  |
| `targets_cleared` | shared/…/ui/TargetsPanel.kt:387 | `` else "Target cleared", `` | Target cleared |  |
| `targets_predicted_route` | shared/…/ui/TargetsPanel.kt:396 | `` "Predicted route", `` | Predicted route |  |
| `targets_timeline` | shared/…/ui/TargetsPanel.kt:405 | `` "Timeline", `` | Timeline |  |
| `targets_disclaimer` | shared/…/ui/TargetsPanel.kt:419 | `` "Positions are inferred from area alerts, not radar. Predictions assume the target keeps its current heading and speed.", `` | Positions are inferred from area alerts, not radar. Predictions assume the target keeps its current heading and speed. |  |
| `targets_stat_since` | shared/…/ui/TargetsPanel.kt:454 | `` Stat("Since", marker.startedAt?.let(::formatTime) ?: "–", Modifier.weight(1f)) `` | Since |  |
| `targets_stat_reported` | shared/…/ui/TargetsPanel.kt:455 | `` Stat("Reported", marker.startedAt?.let(::formatAgo) ?: "–", Modifier.weight(1f)) `` | Reported |  |
| `targets_location` | shared/…/ui/TargetsPanel.kt:466 | `` Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) `` | Location |  |
| `targets_region_wide_position` | shared/…/ui/TargetsPanel.kt:474 | `` Text("Region-wide: position shown at the oblast center", style = MaterialTheme.typography.labelSmall) `` | Region-wide: position shown at the oblast center |  |
| `targets_message` | shared/…/ui/TargetsPanel.kt:481 | `` "Message", `` | Message |  |
| `targets_not_tracked_yet` | shared/…/ui/TargetsPanel.kt:490 | `` "Not tracked yet: a target gets a path and predictions once another alert follows nearby.", `` | Not tracked yet: a target gets a path and predictions once another alert follows nearby. |  |
| `targets_now` | shared/…/ui/TargetsPanel.kt:507 | `` Text("Now · ${formatTime(Instant.fromEpochMilliseconds(now))}", fontWeight = FontWeight.SemiBold) `` | Now · %1$s |  |
| `targets_position_interpolated` | shared/…/ui/TargetsPanel.kt:510 | `` PositionMode.Interpolated -> "Estimated between reports" `` | Estimated between reports |  |
| `targets_position_extrapolated` | shared/…/ui/TargetsPanel.kt:511 | `` PositionMode.Extrapolated -> "Estimated past the last report" `` | Estimated past the last report |  |
| `targets_position_held` | shared/…/ui/TargetsPanel.kt:512 | `` PositionMode.Held -> "At the last reported area" `` | At the last reported area |  |
| `targets_latest_report` | shared/…/ui/TargetsPanel.kt:534 | `` Text("latest report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) `` | latest report |  |
| `targets_badge_active` | shared/…/ui/TargetsPanel.kt:585 | `` if (active) "ACTIVE" else "CLEARED", `` | ACTIVE |  |
| `targets_badge_cleared` | shared/…/ui/TargetsPanel.kt:585 | `` if (active) "ACTIVE" else "CLEARED", `` | CLEARED |  |
| `targets_badge_reported` | shared/…/ui/TargetsPanel.kt:620 | `` if (reported) "REPORTED" else "PROBABLE", `` | REPORTED | Means the alert text named the model; uk "ПІДТВЕРДЖЕНО" (confirmed) reads more naturally than "ПОВІДОМЛЕНО". |
| `targets_badge_probable` | shared/…/ui/TargetsPanel.kt:620 | `` if (reported) "REPORTED" else "PROBABLE", `` | PROBABLE |  |
| `targets_label_class` | shared/…/ui/TargetsPanel.kt:629 | `` LabelValue("Class", profile.kind.label) `` | Class |  |
| `targets_label_make` | shared/…/ui/TargetsPanel.kt:630 | `` LabelValue("Make", profile.make) `` | Make |  |
| `targets_label_role` | shared/…/ui/TargetsPanel.kt:631 | `` LabelValue("Role", profile.role) `` | Role |  |
| `targets_label_typical_speed` | shared/…/ui/TargetsPanel.kt:632 | `` profile.cruiseSpeedKmh?.let { LabelValue("Typical speed", "${it.first}–${it.last} km/h") } `` | Typical speed |  |
| `targets_label_id` | shared/…/ui/TargetsPanel.kt:633 | `` id?.let { LabelValue("ID", it) } `` | ID |  |
| `targets_label_alerts` | shared/…/ui/TargetsPanel.kt:634 | `` if (alertIds.isNotEmpty()) LabelValue("Alerts", alertIds.joinToString(", ") { "#$it" }) `` | Alerts |  |
| `targets_probable_note` | shared/…/ui/TargetsPanel.kt:637 | `` "alerts.in.ua doesn't identify models; inferred from the threat class and speed.", `` | alerts.in.ua doesn't identify models; inferred from the threat class and speed. |  |
| `targets_km_ahead` | shared/…/ui/TargetsPanel.kt:673 | `` Text("${stop.distanceKm.toInt()} km ahead", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) `` | %1$d km ahead |  |
| `targets_at_time` | shared/…/ui/TargetsPanel.kt:678 | `` "at " + formatTime(Instant.fromEpochMilliseconds(now + stop.etaMinutes * 60_000L)), `` | at %1$s | %1$s = HH:mm. |
| `inspector_title` | shared/…/ui/debug/TrafficInspector.kt:329 | `` Text("Requests", style = MaterialTheme.typography.headlineSmall) `` | Requests |  |
| `inspector_description` | shared/…/ui/debug/TrafficInspector.kt:331 | `` "Every request this app made to its server, newest first, with the response.", `` | Every request this app made to its server, newest first, with the response. |  |
| `inspector_filter_by_url` | shared/…/ui/debug/TrafficInspector.kt:93 | `` placeholder = { Text("Filter by URL") }, `` | Filter by URL |  |
| `inspector_clear` | shared/…/ui/debug/TrafficInspector.kt:100 | `` }) { Text("Clear") } `` | Clear | Verb: clears the log. |
| `inspector_from_clients` | shared/…/ui/debug/TrafficInspector.kt:108 | `` label = { Text("From clients (${exchanges.count { it.direction == HttpExchange.Direction.Incoming }})") }, `` | From clients (%1$d) |  |
| `inspector_upstream` | shared/…/ui/debug/TrafficInspector.kt:113 | `` label = { Text("Upstream (${exchanges.count { it.direction == HttpExchange.Direction.Outgoing }})") }, `` | Upstream (%1$d) |  |
| `inspector_empty` | shared/…/ui/debug/TrafficInspector.kt:119 | `` if (exchanges.isEmpty()) "No requests yet." else "No requests match.", `` | No requests yet. |  |
| `inspector_no_match` | shared/…/ui/debug/TrafficInspector.kt:119 | `` if (exchanges.isEmpty()) "No requests yet." else "No requests match.", `` | No requests match. |  |
| `inspector_select_hint` | shared/…/ui/debug/TrafficInspector.kt:140 | `` Text("Select a request to see its details.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) `` | Select a request to see its details. |  |
| `inspector_status_error` | shared/…/ui/debug/TrafficInspector.kt:200 | `` status?.toString() ?: "ERR", `` | ERR | Badge shown instead of an HTTP status code; keep it short. |
| `inspector_back` | shared/…/ui/debug/TrafficInspector.kt:220 | `` if (onBack != null) TextButton(onClick = onBack) { Text("← Requests") } `` | ← Requests |  |
| `inspector_time` | shared/…/ui/debug/TrafficInspector.kt:226 | `` Fact("Time", formatClock(exchange.startedAt)) `` | Time |  |
| `inspector_duration` | shared/…/ui/debug/TrafficInspector.kt:227 | `` Fact("Duration", "${exchange.durationMs} ms") `` | Duration |  |
| `inspector_direction` | shared/…/ui/debug/TrafficInspector.kt:228 | `` Fact("Direction", if (exchange.direction == HttpExchange.Direction.Incoming) "Incoming (a client called)" else "Outgoing") `` | Direction |  |
| `inspector_direction_incoming` | shared/…/ui/debug/TrafficInspector.kt:228 | `` Fact("Direction", if (exchange.direction == HttpExchange.Direction.Incoming) "Incoming (a client called)" else "Outgoing") `` | Incoming (a client called) |  |
| `inspector_direction_outgoing` | shared/…/ui/debug/TrafficInspector.kt:228 | `` Fact("Direction", if (exchange.direction == HttpExchange.Direction.Incoming) "Incoming (a client called)" else "Outgoing") `` | Outgoing |  |
| `inspector_client` | shared/…/ui/debug/TrafficInspector.kt:229 | `` exchange.remoteAddress?.let { Fact("Client", it) } `` | Client |  |
| `inspector_user_agent` | shared/…/ui/debug/TrafficInspector.kt:230 | `` exchange.userAgent?.let { Fact("User agent", it) } `` | User agent |  |
| `inspector_content_type` | shared/…/ui/debug/TrafficInspector.kt:231 | `` exchange.contentType?.let { Fact("Content type", it) } `` | Content type |  |
| `inspector_body` | shared/…/ui/debug/TrafficInspector.kt:232 | `` bodySize(exchange)?.let { Fact("Body", it) } `` | Body |  |
| `inspector_request_headers` | shared/…/ui/debug/TrafficInspector.kt:234 | `` Section("Request headers") `` | Request headers |  |
| `inspector_response_headers` | shared/…/ui/debug/TrafficInspector.kt:236 | `` Section("Response headers") `` | Response headers |  |
| `inspector_response_body` | shared/…/ui/debug/TrafficInspector.kt:238 | `` Section("Response body") `` | Response body |  |

## Plurals

| Key | Source (file:line) | Original code | Forms (en) | Notes |
|---|---|---|---|---|
| `targets_reports` | shared/…/ui/TargetsPanel.kt:695 | `` private fun reportsLabel(count: Int) = if (count == 1) "1 report" else "$count reports" `` | one: %1$d report<br>other: %1$d reports |  |
| `status_threat_count` | shared/…/ui/MapScreen.kt:563 | `` append("${state.threats.size} ${if (state.settings.dronesOnly) "drone threats" else "threats"}") `` | one: %1$d threat<br>other: %1$d threats | Status strip; used when "Drones only" is off. |
| `status_drone_threat_count` | shared/…/ui/MapScreen.kt:563 | `` append("${state.threats.size} ${if (state.settings.dronesOnly) "drone threats" else "threats"}") `` | one: %1$d drone threat<br>other: %1$d drone threats | Status strip; used when "Drones only" is on. |
| `map_cinematic_poi_alerts` | shared/…/ui/MapScreen.kt:548 | `` "SHOT ${director.shotIndex + 1}/${director.shotCount} · ${if (state.proximity.isEmpty()) "PATROL" else "${state.proximity.size} POI ALERT(S)"}", `` | one: %1$d POI ALERT<br>other: %1$d POI ALERTS | Replaces the "ALERT(S)" hack. |
| `cinematic_active_threats` | shared/…/ui/map/CinematicDirector.kt:101 | `` subtitle = "${threats.size} active threats", `` | one: %1$d active threat<br>other: %1$d active threats |  |
| `map_warning_radius_hint_many` | shared/…/ui/MapScreen.kt:783 | `` else -> "You're warned when a threat comes this close to any of your $favoriteCount favorite places." `` | one: You're warned when a threat comes this close to any of your %1$d favorite places.<br>other: You're warned when a threat comes this close to any of your %1$d favorite places. | Only for favoriteCount >= 2 (0 → map_warning_radius_hint_none, 1 → map_warning_radius_hint_one). uk genitive "з ваших N обраних місць" works for every count. |
