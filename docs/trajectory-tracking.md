# Target trajectories: analysis, mapping algorithm and improvement plan

Goal: the server builds a believable, predictable trajectory for every airborne target from every data
source it has. The KMP app only draws what the server sends: the target's icon, its past trajectory as a
smooth curve, and the forecast.

## 1. What the data says today

### 1.1 alerts.in.ua `/v1/alerts/active.json`

Recorded sample: `mission-control/server/src/test/resources/alerts-in-ua-active.json`.

| Observation | Consequence for tracking |
|---|---|
| An alert is an **area**, never a point: `location_type` is `oblast`, `raion`, `hromada` or `city`; no coordinates. | Every position is a guess with an error of 5 km (city) to 100+ km (oblast). It has to be treated as a measurement with an uncertainty, not as a fix. |
| One alert carries several `threats[]`, each with its own `threat_type`, `level` and `started_at` (e.g. raion 311662: `drones` yellow since 23:27, `unspecified_missiles` red since 23:39). | The **threat** is the event, not the alert. `started_at` of the threat is the only time stamp tied to the target. |
| Alerts stay open for days or weeks (hromada 257385: missiles since 2026-09-07; city 309660 since 2026-10-23). | "Still listed" is not a sighting. Only a newly started threat is new information (handled since `staleAfterMs`). |
| `location_oblast_uid` repeats the location's own UID; the oblast comes from `location_oblast` (the name). | Already handled in `ThreatMapper.oblastUid`. |
| `level`: `red` = target over or entering the area, `yellow` = preliminary, target heading for it or next door. | A yellow report says where the target is **going**, not where it is: it is a weaker, forward-biased measurement. |
| `source_message` is generic for drones ("Дронова загроза (жовтий рівень)"); it can name the type (Shahed, Gerbera, jet). | Type comes from the text when present (`TargetClassifier`), otherwise from the threat class. No heading, no count. |
| Oblast alerts may carry no threats at all (8757, Luhansk) or a threat for the whole oblast. | An oblast-level drone threat says "somewhere in this oblast"; see §3. |
| Alerts are raised **ahead** of the target (the warning system covers its way). | Report positions lead the true position; timestamps are early rather than late. |

### 1.2 Where positions come from now (`ThreatMapper.locate`)

- `city`, and `hromada` whose title names a city: the city's coordinates. Good.
- `raion`, and every other hromada: **a pseudo-random spot in the oblast** (`GeoMath.scatterInOblast`). The
  generated raion table (`geo/UkraineRaions.kt`, 136 raions with former names) and `Regions.raionForTitle`
  exist but `locate` does not use them. A drone crossing three raions of one oblast is placed at three random
  points up to ~90 km apart: this is the main source of zig-zag tracks and wrong headings.
- `oblast`: the oblast centre; correctly excluded from tracking, but then ignored entirely.

### 1.3 What the server serves (`/v1/drones/tracks.json`)

`DroneTrack` = raw `samples` (one per accepted alert) + `active`/`lastSeen` + `markerIds`. No velocity, no
uncertainty, no forecast, no kind. Tracks are linked by `DroneTracker`: nearest track of the same type and
kind within the hop distance and reachable at top speed, one new alert per track per poll, greedy in time order.

### 1.4 What the client computes (should move to the server)

| Client code | Does |
|---|---|
| `TrackInterpolator.positionAt` (map, targets panel, filter) | interpolation, least-squares trend, dead reckoning |
| `TrackPredictor.predict/route/courseEnd` (map, targets panel, cinematic camera) | next settlement, ETA, route line |
| `TargetClassifier.classify(track, speed)` (filter, panel) | kind and model |
| `BuildThreatPictureUseCase` | re-checks `active` against `staleAfterMs` |
| `TrackSmoother` | glides the icon on corrections (cosmetic, stays) |

Every app version therefore carries its own copy of the tracking rules, and fixing them needs an app release.

## 2. Mapping algorithm (server)

Runs once per upstream poll (15 s). Pure functions in `core` (testable), state in `ObservationTracking` /
`AlertDerivedTracking` on the server.

### 2.1 Step 1: alert → observation

```
for each alert, for each threat of a tracked type (drones, cruise/unspecified missiles):
    key   = alertId:threatType
    t     = threat.started_at ?: alert.started_at ?: now        (clamped to now)
    skip unless key is new, or t is newer than the last t seen for key   // "still listed" is not a sighting
    area  = resolve(alert):
        city / hromada named after a city -> point = city,            sigmaKm = 7
        hromada (from a hromada table)    -> point = hromada centre,  sigmaKm = hromada radius (8-20)
        raion                             -> point = raion centre,    sigmaKm = raion radius (20-40)
        oblast                            -> polygon only,            sigmaKm = oblast radius (60-120), point = null
        unresolved                        -> polygon of its oblast as for oblast
    if level == yellow: sigmaKm *= 1.5, mark as "ahead"               // target is approaching the area
    emit Observation(key, type, kind = classify(message), t, area, level, source)
```

`resolve` never invents a point: the scatter is only for drawing area markers, never for tracks.

### 2.2 Step 2: per-track motion model

Each track keeps a constant-velocity Kalman filter in a local east/north frame (km):
state `[x, y, vx, vy]`, covariance `P`. Kept small on purpose; four states, no matrix library.

- **Predict** to the observation time `t` with process noise `q` from the kind's manoeuvrability
  (Shahed `q` low, cruise missile higher at waypoints, Gerbera decoys highest).
- **Update** with the area centre as the measurement and `R = sigmaKm² · I`. A yellow ("ahead") report is
  shifted back along the current velocity by about one warning lead time before the update.
- **Constrain** after every update (this is what keeps trajectories realistic, see §4): clamp speed into the
  kind's band and limit the heading change since the last update to the kind's turn rate.

### 2.3 Step 3: association (which track does an observation belong to)

For each new point observation (oldest first), against each open track of the same type and kind:

```
predicted = track.filter.predictedAt(obs.t)
gate:     distance(predicted, obs.point) <= 3 * sqrt(predicted.sigma² + obs.sigma²)
          and distance(track.last, obs.point) <= reachableKm(kind.maxSpeed, obs.t - track.lastT)
cost      = normalised distance (Mahalanobis) + turn penalty (heading change / kind.maxTurn)
```

Assign by lowest cost, globally per poll (greedy over the sorted cost list is enough: few tracks per oblast),
one observation per track per poll, simultaneous observations never merge into one track. No match: start
a track, unless a lost track of the same kind is reachable within the re-acquire window (re-acquire first:
a drone does not appear in the middle of the country, see §4).

### 2.4 Step 4: oblast-only reports

An oblast observation has no point, only "the target is in this polygon at `t`". It is never a sample and
never puts an icon at the oblast centre.

```
candidates = open tracks of the same type/kind whose predicted position at t is inside the oblast,
             or whose reachable circle (maxSpeed · Δt) intersects it
case 1 candidate (or several, take the lowest cost):
    keep it alive (lastSeen = t) — the target is still flying
    if its predicted position is outside the polygon:
        project it onto the polygon edge along its course (the entry point) and apply it as a
        weak measurement (sigma = oblast radius / 2); otherwise no position update
case none:
    keep a PendingRegionThreat(type, kind, oblast, t) for the re-acquire window, shown as the oblast area
    marker (as today); when the first raion/hromada/city observation in or next to that oblast arrives,
    the new track starts there with firstSeen = the oblast report's t
case it expires unexplained:
    nothing is drawn but the area marker
```

The same rule handles any coarse report from another source (e.g. "drones over Sumy oblast" in a text feed).

### 2.5 Step 5: what the track publishes

After the updates of a poll, per track:

- **estimate**: position, heading, speed and `sigmaKm` at the last update time, plus `estimateAt`; the
  client moves the icon along the velocity until the next poll (one multiplication, no rules).
- **path**: the past trajectory, smoothed backwards (Rauch-Tung-Striebel pass over the stored filter states,
  or, simpler and good enough, a speed-capped moving average over the samples), resampled every 2 km for
  drones / 10 km for missiles, at most ~100 points.
- **forecast**: points every 5 min up to the kind's horizon (drones 30 min, missiles 5 min), each with
  growing `sigmaKm`, plus the next settlements and favourites-independent ETAs (today's `TrackPredictor`,
  moved to the server; favourites stay on the client: the client only checks the forecast cone against them).
- **kind/model**, as classified on the server.
- raw `samples` stay, now with `precision` (`city`/`hromada`/`raion`/`oblast`) and `source`.

### 2.6 Multiple sources

Each source turns its data into the same `Observation` (point or area, time, sigma, optional heading hint):
alerts.in.ua (areas), the simulation (exact points, sigma ≈ 1 km), later ukrainealarm.com or monitoring
channels whose text says "курсом на <місто>" (a heading hint, applied as a weak velocity measurement).
Before association, observations from different sources for the same area within 2 min are merged into
one (the most precise wins), so a target reported twice does not split into two tracks.

## 3. Client after the change

The app draws, per track: the icon at `estimate + velocity · (now − estimateAt)` (capped at the kind's
dead-reckoning limit), `path` as a centripetal Catmull-Rom curve (purely cosmetic: through the server's
points, so it cannot invent a turn), the forecast line with its widening cone, and the icon glide from
`TrackSmoother`. `TrackInterpolator`, `TrackPredictor`, `TargetClassifier.classify(track, ...)` and the
`active` re-check leave the client; it keeps them only as a fallback for servers that do not send the new
fields yet.

## 4. Realism rules per kind

Enforced by the filter's constraints and checked by tests on every served track.

| Kind | Speed band (km/h) | Max turn | Altitude shown | Other rules |
|---|---|---|---|---|
| Shahed-136 / Geran-2 | 120–220 | ~60° per 10 min | 2 km | range ≤ 2 500 km; tracks start near the border or frontline, else re-acquire |
| Gerbera (decoy) | 100–170 | ~90° per 10 min | 1.5 km | loiters; low confidence forecast |
| Geran-3 (jet) | 350–600 | ~45° per 5 min | 3 km | |
| Kh-101 / Kalibr | 650–1 000 | waypoint turns ≤ 90° | 50 m | forecast horizon 5 min; alerts lead the missile a lot |
| Ballistic, bombs | not tracked from alerts | | | area markers only |

Checks: consecutive `path` points never imply a speed outside the band (with 25 % slack); the smoothed path
stays within 2σ of every city/hromada/raion sample it used; a track's flown distance stays under the
kind's range; a track never reverses (> 150°) between two consecutive samples unless the kind loiters.

## 5. Improvements, in order

1. **Place raions at their centre.** `ThreatMapper.locate` uses `Regions.raionForTitle` for `raion` alerts and
   keeps the scatter only for area markers that have no table entry. Biggest accuracy gain for one small change.
2. **Add raion and hromada extents.** Extend `tools/download-raions.py` to write each raion's radius (half the
   bounding-box diagonal); add a hromada table (OSM `admin_level=7`, ~1 470 entries) the same way, so hromada
   alerts stop falling back to the scatter.
3. **Observation model in `core`.** `Observation(t, point?, polygonOblastUid?, sigmaKm, level, source)`, built
   once from alerts and from the simulation; the tracker consumes only observations.
4. **Per-track constant-velocity Kalman filter** with kind-specific noise, speed band and turn limit (§2.2),
   replacing the least-squares `trend` for heading and speed.
5. **Gated, cost-based association** (§2.3) instead of nearest-within-hop; re-acquire before starting a track.
6. **Oblast-only reports** (§2.4): keep-alive, entry-point constraint, pending region threats.
7. **Yellow-level handling**: larger sigma and a backward shift along the course.
8. **Server-side smoothing and forecast**: publish `estimate`, `path`, `forecast`, `kind` on `DroneTrack`
   (new optional fields; old clients ignore them) and in `/v1/drones/positions.json`.
9. **Thin client**: draw from the new fields, Catmull-Rom the `path`, drop the client-side tracking rules
   behind a "server sends estimates" check.
10. **Truth-scored replay harness.** The simulation knows the true flights (`Flight.positionAt`) and raises the
    alerts they cause (`WarningSystem`). A test feeds only those alerts to `AlertDerivedTracking` and scores the
    result against the truth: position error, heading error, wrongly merged / split tracks, forecast error at
    +10 / +30 min. Every change above is tuned against this score instead of by eye.
11. **Replay of real data.** Record live alerts.in.ua responses (the archive already keeps 45 days) and replay
    them through the tracker, checking the realism rules of §4 on every resulting track.
12. **Further sources** (§2.6): merge step and heading hints from text feeds.
