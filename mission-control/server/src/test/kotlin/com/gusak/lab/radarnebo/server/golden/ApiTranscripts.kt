package com.gusak.lab.radarnebo.server.golden

/** The expected transcripts of [ApiContractGoldenTest], recorded from the server as it was when they were added. */
internal object ApiTranscripts {
    /** Before the first report: health, and a 503 with `Retry-After` from every data endpoint. */
    val WARMING_UP = """
        GET /health
        200
        Content-Type: application/json
        {"status":"warming_up","source":"AlertsInUa","simulated":false,"upstreamBudgetPerMinute":6,"upstreamRequestsLastMinute":0,"upstreamRequestsTotal":0,"upstreamRateLimitedTotal":0,"upstreamRejectedTotal":0,"upstreamOtherFailuresTotal":0,"upstreamLastSuccessAt":null,"pollIntervalSeconds":15,"lastAttemptAt":null,"nextPollAt":null,"dataVersion":null,"dataConfirmedAt":null,"dataAgeSeconds":null,"alerts":0,"tracks":0,"activeTracks":0,"lastError":null}

        GET /v1/alerts/active.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v1/iot/active_air_raid_alerts_by_oblast.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v2/iot/active_air_raid_alerts.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v1/iot/active_air_raid_alerts/22.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v1/regions/14/alerts/month_ago.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v1/drones/tracks.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v1/drones/positions.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}

        GET /v1/drones/archive.json
        503
        Content-Type: application/json
        Retry-After: 5
        {"message":"The server is warming up, no data yet"}
    """.trimIndent()

    /** Every data endpoint over a scripted situation that changes twice, then a failing source. */
    val SERVING = """
        GET /health
        200
        Content-Type: application/json
        {"status":"ok","source":"AlertsInUa","simulated":false,"upstreamBudgetPerMinute":6,"upstreamRequestsLastMinute":0,"upstreamRequestsTotal":0,"upstreamRateLimitedTotal":0,"upstreamRejectedTotal":0,"upstreamOtherFailuresTotal":0,"upstreamLastSuccessAt":null,"pollIntervalSeconds":15,"lastAttemptAt":1792405200000,"nextPollAt":null,"dataVersion":2,"dataConfirmedAt":1792405200000,"dataAgeSeconds":45,"alerts":3,"tracks":2,"activeTracks":2,"lastError":null}

        GET /v1/alerts/active.json [Origin: https://example.org]
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        Access-Control-Allow-Origin: *
        Access-Control-Expose-Headers: X-Data-Age-Seconds
        {"alerts":[{"id":1,"location_title":"Харківська область","location_type":"oblast","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"22","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv Oblast","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":22},{"id":3,"location_title":"м. Харків та Харківська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"1293","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv","threats":[{"threat_type":"cruise_missiles","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Крилаті ракети"}],"location_oblast_uid":22},{"id":4,"location_title":"м. Київ","location_type":"city","started_at":"2026-10-19T10:20:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:20:00.000Z","alert_type":"air_raid","location_oblast":"м. Київ","location_uid":"31","notes":null,"country":null,"alert_level":"red","location_title_en":"Kyiv City","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:20:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":31}],"meta":{"last_updated_at":"2026/10/19 10:20:00 +0000","type":"full"}}

        GET /v1/alerts/active.json [If-None-Match: "v2"]
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        {"alerts":[{"id":1,"location_title":"Харківська область","location_type":"oblast","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"22","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv Oblast","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":22},{"id":3,"location_title":"м. Харків та Харківська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"1293","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv","threats":[{"threat_type":"cruise_missiles","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Крилаті ракети"}],"location_oblast_uid":22},{"id":4,"location_title":"м. Київ","location_type":"city","started_at":"2026-10-19T10:20:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:20:00.000Z","alert_type":"air_raid","location_oblast":"м. Київ","location_uid":"31","notes":null,"country":null,"alert_level":"red","location_title_en":"Kyiv City","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:20:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":31}],"meta":{"last_updated_at":"2026/10/19 10:20:00 +0000","type":"full"}}

        GET /v1/alerts/active.json [If-Modified-Since: Mon, 19 Oct 2026 10:20:00 GMT]
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        {"alerts":[{"id":1,"location_title":"Харківська область","location_type":"oblast","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"22","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv Oblast","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":22},{"id":3,"location_title":"м. Харків та Харківська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"1293","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv","threats":[{"threat_type":"cruise_missiles","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Крилаті ракети"}],"location_oblast_uid":22},{"id":4,"location_title":"м. Київ","location_type":"city","started_at":"2026-10-19T10:20:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:20:00.000Z","alert_type":"air_raid","location_oblast":"м. Київ","location_uid":"31","notes":null,"country":null,"alert_level":"red","location_title_en":"Kyiv City","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:20:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":31}],"meta":{"last_updated_at":"2026/10/19 10:20:00 +0000","type":"full"}}

        GET /v1/iot/active_air_raid_alerts_by_oblast.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "NNNNNNNNNANNNNNNNNNNNANNNNN"

        GET /v1/iot/active_air_raid_alerts.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        <1296 chars, sha256 99f4aacb491bcc7ec3d2466ad0071eea4e52fcc9c58672a29c0287b9b5d4f094>

        GET /v1/iot/active_air_raid_alerts/22.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "A"

        GET /v1/iot/active_air_raid_alerts/1293.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "A"

        GET /v1/iot/active_air_raid_alerts/14.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "N"

        GET /v1/iot/active_air_raid_alerts/1100.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "N"

        GET /v1/iot/active_air_raid_alerts/31.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "A"

        GET /v1/iot/active_air_raid_alerts/7.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "N"

        GET /v2/iot/active_air_raid_alerts_by_oblast.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "NNNNNNNNNRNNNNNNNNNNNRNNNNN"

        GET /v2/iot/active_air_raid_alerts.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        <1296 chars, sha256 0d328ad15c5f11047f7fe0f7ca054ae0639bb20e65028eb98f19f6d33b62eb20>

        GET /v2/iot/active_air_raid_alerts/22.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "R"

        GET /v2/iot/active_air_raid_alerts/1293.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "R"

        GET /v2/iot/active_air_raid_alerts/14.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "N"

        GET /v2/iot/active_air_raid_alerts/1100.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "N"

        GET /v2/iot/active_air_raid_alerts/31.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "R"

        GET /v2/iot/active_air_raid_alerts/7.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 45
        "N"

        GET /v1/regions/14/alerts/month_ago.json
        200
        Content-Type: application/json
        {"alerts":[{"id":2,"location_title":"Броварська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":"2026-10-19T10:20:00.000Z","updated_at":"2026-10-19T10:20:00.000Z","alert_type":"air_raid","location_oblast":"Київська область","location_uid":"1100","notes":null,"country":null,"alert_level":"yellow","location_title_en":"Brovary","threats":[{"threat_type":"drones","level":"yellow","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":14}]}

        GET /v1/regions/22/alerts/week_ago.json
        200
        Content-Type: application/json
        {"alerts":[{"id":1,"location_title":"Харківська область","location_type":"oblast","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"22","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv Oblast","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":22},{"id":3,"location_title":"м. Харків та Харківська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"1293","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv","threats":[{"threat_type":"cruise_missiles","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Крилаті ракети"}],"location_oblast_uid":22}]}

        GET /v1/regions/1293/alerts/day_ago.json
        200
        Content-Type: application/json
        {"alerts":[{"id":3,"location_title":"м. Харків та Харківська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"1293","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv","threats":[{"threat_type":"cruise_missiles","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Крилаті ракети"}],"location_oblast_uid":22}]}

        GET /v1/drones/tracks.json
        200
        Content-Type: application/json
        {"serverTime":1792405245000,"pollIntervalMs":15000,"lastUpstreamFetch":1792405200000,"tracks":[{"id":"trk-tn5fs0-1","type":"CruiseMissiles","level":"Red","oblastUid":22,"active":true,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":["3:cruise_missiles"],"samples":[{"t":1792404000000,"lat":49.9935,"lon":36.2304,"title":"Kharkiv","oblastUid":22,"message":"Крилаті ракети"}],"outcome":null},{"id":"trk-tn5fs0-2","type":"Drones","level":"Red","oblastUid":31,"active":true,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":["4:drones"],"samples":[{"t":1792404000000,"lat":50.511,"lon":30.7909,"title":"Brovary","oblastUid":14,"message":"Ударні БпЛА типу «Шахед»"},{"t":1792405200000,"lat":50.4501,"lon":30.5234,"title":"Kyiv City","oblastUid":31,"message":"Ударні БпЛА типу «Шахед»"}],"outcome":null}],"simulated":false}

        GET /v1/drones/tracks.json?active=true
        200
        Content-Type: application/json
        {"serverTime":1792405245000,"pollIntervalMs":15000,"lastUpstreamFetch":1792405200000,"tracks":[{"id":"trk-tn5fs0-1","type":"CruiseMissiles","level":"Red","oblastUid":22,"active":true,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":["3:cruise_missiles"],"samples":[{"t":1792404000000,"lat":49.9935,"lon":36.2304,"title":"Kharkiv","oblastUid":22,"message":"Крилаті ракети"}],"outcome":null},{"id":"trk-tn5fs0-2","type":"Drones","level":"Red","oblastUid":31,"active":true,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":["4:drones"],"samples":[{"t":1792404000000,"lat":50.511,"lon":30.7909,"title":"Brovary","oblastUid":14,"message":"Ударні БпЛА типу «Шахед»"},{"t":1792405200000,"lat":50.4501,"lon":30.5234,"title":"Kyiv City","oblastUid":31,"message":"Ударні БпЛА типу «Шахед»"}],"outcome":null}],"simulated":false}

        GET /v1/drones/positions.json
        200
        Content-Type: application/json
        {"at":1792405245000,"positions":[{"trackId":"trk-tn5fs0-1","lat":49.9935,"lon":36.2304,"headingDeg":null,"speedKmh":null,"mode":"Held","level":"Red"},{"trackId":"trk-tn5fs0-2","lat":50.447816,"lon":30.513369,"headingDeg":250.326001,"speedKmh":60.345033,"mode":"Extrapolated","level":"Red"}]}

        GET /v1/drones/positions.json?at=2026-10-19T10:10:00Z
        200
        Content-Type: application/json
        {"at":1792404600000,"positions":[{"trackId":"trk-tn5fs0-1","lat":49.9935,"lon":36.2304,"headingDeg":null,"speedKmh":null,"mode":"Held","level":"Red"},{"trackId":"trk-tn5fs0-2","lat":50.48055,"lon":30.65715,"headingDeg":250.302586,"speedKmh":60.307799,"mode":"Interpolated","level":"Red"}]}

        GET /v1/drones/tracks.json
        200
        Content-Type: application/json
        {"serverTime":1792407645000,"pollIntervalMs":15000,"lastUpstreamFetch":1792407645000,"tracks":[{"id":"trk-tn5fs0-1","type":"CruiseMissiles","level":"Red","oblastUid":22,"active":false,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":[],"samples":[{"t":1792404000000,"lat":49.9935,"lon":36.2304,"title":"Kharkiv","oblastUid":22,"message":"Крилаті ракети"}],"outcome":null},{"id":"trk-tn5fs0-2","type":"Drones","level":"Red","oblastUid":31,"active":false,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":[],"samples":[{"t":1792404000000,"lat":50.511,"lon":30.7909,"title":"Brovary","oblastUid":14,"message":"Ударні БпЛА типу «Шахед»"},{"t":1792405200000,"lat":50.4501,"lon":30.5234,"title":"Kyiv City","oblastUid":31,"message":"Ударні БпЛА типу «Шахед»"}],"outcome":null}],"simulated":false}

        GET /v1/drones/archive.json?from=1792404000000
        200
        Content-Type: application/json
        {"from":1792404000000,"to":1792407645000,"tracks":[{"id":"trk-tn5fs0-1","type":"CruiseMissiles","level":"Red","oblastUid":22,"active":false,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":[],"samples":[{"t":1792404000000,"lat":49.9935,"lon":36.2304,"title":"Kharkiv","oblastUid":22,"message":"Крилаті ракети"}],"outcome":null},{"id":"trk-tn5fs0-2","type":"Drones","level":"Red","oblastUid":31,"active":false,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":[],"samples":[{"t":1792404000000,"lat":50.511,"lon":30.7909,"title":"Brovary","oblastUid":14,"message":"Ударні БпЛА типу «Шахед»"},{"t":1792405200000,"lat":50.4501,"lon":30.5234,"title":"Kyiv City","oblastUid":31,"message":"Ударні БпЛА типу «Шахед»"}],"outcome":null}],"simulated":false}

        GET /v1/drones/archive.json?from=2026-10-19T09:00:00Z&to=2026-10-19T12:00:00Z&type=drones&limit=5
        200
        Content-Type: application/json
        {"from":1792400400000,"to":1792411200000,"tracks":[{"id":"trk-tn5fs0-2","type":"Drones","level":"Red","oblastUid":31,"active":false,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":[],"samples":[{"t":1792404000000,"lat":50.511,"lon":30.7909,"title":"Brovary","oblastUid":14,"message":"Ударні БпЛА типу «Шахед»"},{"t":1792405200000,"lat":50.4501,"lon":30.5234,"title":"Kyiv City","oblastUid":31,"message":"Ударні БпЛА типу «Шахед»"}],"outcome":null}],"simulated":false}

        GET /v1/drones/archive.json?type=cruise_missiles
        200
        Content-Type: application/json
        {"from":1792321245000,"to":1792407645000,"tracks":[{"id":"trk-tn5fs0-1","type":"CruiseMissiles","level":"Red","oblastUid":22,"active":false,"firstSeen":1792404000000,"lastSeen":1792405200000,"markerIds":[],"samples":[{"t":1792404000000,"lat":49.9935,"lon":36.2304,"title":"Kharkiv","oblastUid":22,"message":"Крилаті ракети"}],"outcome":null}],"simulated":false}

        GET /v1/regions/31/alerts/day_ago.json
        200
        Content-Type: application/json
        {"alerts":[{"id":4,"location_title":"м. Київ","location_type":"city","started_at":"2026-10-19T10:20:00.000Z","finished_at":"2026-10-19T11:00:45.000Z","updated_at":"2026-10-19T11:00:45.000Z","alert_type":"air_raid","location_oblast":"м. Київ","location_uid":"31","notes":null,"country":null,"alert_level":"red","location_title_en":"Kyiv City","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:20:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":31}]}

        GET /v1/alerts/active.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 0
        {"alerts":[{"id":1,"location_title":"Харківська область","location_type":"oblast","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"22","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv Oblast","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":22}],"meta":{"last_updated_at":"2026/10/19 11:00:45 +0000","type":"full"}}

        GET /health
        200
        Content-Type: application/json
        {"status":"degraded","source":"AlertsInUa","simulated":false,"upstreamBudgetPerMinute":6,"upstreamRequestsLastMinute":0,"upstreamRequestsTotal":0,"upstreamRateLimitedTotal":0,"upstreamRejectedTotal":0,"upstreamOtherFailuresTotal":0,"upstreamLastSuccessAt":null,"pollIntervalSeconds":15,"lastAttemptAt":1792407660000,"nextPollAt":null,"dataVersion":3,"dataConfirmedAt":1792407645000,"dataAgeSeconds":15,"alerts":1,"tracks":2,"activeTracks":0,"lastError":"HTTP 502 from upstream"}

        GET /v1/alerts/active.json
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 15
        {"alerts":[{"id":1,"location_title":"Харківська область","location_type":"oblast","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Харківська область","location_uid":"22","notes":null,"country":null,"alert_level":"red","location_title_en":"Kharkiv Oblast","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":22}],"meta":{"last_updated_at":"2026/10/19 11:00:45 +0000","type":"full"}}
    """.trimIndent()

    /** 400s for bad parameters and 404s for unknown paths, each with its `{"message": ...}` body. */
    val ERRORS = """
        GET /v1/iot/active_air_raid_alerts/kyiv.json
        400
        Content-Type: application/json
        {"message":"Location UID must be a number"}

        GET /v2/iot/active_air_raid_alerts/22
        404
        Content-Type: application/json
        {"message":"Not found"}

        GET /v1/regions/kyiv/alerts/month_ago.json
        400
        Content-Type: application/json
        {"message":"Location UID must be a number"}

        GET /v1/regions/14/alerts/year_ago.json
        400
        Content-Type: application/json
        {"message":"Unknown period; use month_ago, week_ago, day_ago"}

        GET /v1/regions/14/alerts/month_ago
        404
        Content-Type: application/json
        {"message":"Not found"}

        GET /v1/drones/positions.json?at=yesterday
        400
        Content-Type: application/json
        {"message":"'at' must be epoch milliseconds or ISO-8601"}

        GET /v1/drones/archive.json?type=balloons
        400
        Content-Type: application/json
        {"message":"Unknown type 'balloons'"}

        GET /v1/drones/archive.json?from=2&to=1
        400
        Content-Type: application/json
        {"message":"'from' must not be after 'to'"}

        GET /v1/drones/archive.json?limit=many
        400
        Content-Type: application/json
        {"message":"'limit' must be a number"}

        GET /v1/drones/archive.json?limit=0
        400
        Content-Type: application/json
        {"message":"'limit' must be between 1 and 1000"}

        GET /v1/drones/archive.json?to=soon
        400
        Content-Type: application/json
        {"message":"'to' must be epoch milliseconds or ISO-8601"}

        GET /v1/nope
        404
        Content-Type: application/json
        {"message":"Not found"}

        GET /v3/iot/active_air_raid_alerts.json
        404
        Content-Type: application/json
        {"message":"Not found"}
    """.trimIndent()

    /** With client keys: 401s, a key in the header or `?token=`, the stricter history rate limit. */
    val AUTH = """
        GET /v1/alerts/active.json
        401
        Content-Type: application/json
        WWW-Authenticate: Bearer
        {"message":"Missing or invalid client key"}

        GET /v1/alerts/active.json?token=wrong
        401
        Content-Type: application/json
        WWW-Authenticate: Bearer
        {"message":"Missing or invalid client key"}

        GET /v1/alerts/active.json [Authorization: Bearer wrong]
        401
        Content-Type: application/json
        WWW-Authenticate: Bearer
        {"message":"Missing or invalid client key"}

        GET /v1/drones/tracks.json
        401
        Content-Type: application/json
        WWW-Authenticate: Bearer
        {"message":"Missing or invalid client key"}

        GET /v1/nope
        404
        Content-Type: application/json
        {"message":"Not found"}

        GET /v1/iot/active_air_raid_alerts/14.json [Authorization: Bearer app-key]
        200
        Content-Type: application/json
        X-Data-Age-Seconds: 0
        "P"

        GET /v1/regions/14/alerts/day_ago.json?token=app-key
        200
        Content-Type: application/json
        {"alerts":[{"id":1,"location_title":"Броварська територіальна громада","location_type":"hromada","started_at":"2026-10-19T10:00:00.000Z","finished_at":null,"updated_at":"2026-10-19T10:00:00.000Z","alert_type":"air_raid","location_oblast":"Київська область","location_uid":"1100","notes":null,"country":null,"alert_level":"red","location_title_en":"Brovary","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z","source_message":"Ударні БпЛА типу «Шахед»"}],"location_oblast_uid":14}]}

        GET /v1/regions/14/alerts/day_ago.json?token=app-key
        429
        Content-Type: application/json
        Retry-After: <1..60>
        {"message":"Too many requests"}

        GET /health
        200
        Content-Type: application/json
        {"status":"ok","source":"AlertsInUa","simulated":false,"upstreamBudgetPerMinute":6,"upstreamRequestsLastMinute":0,"upstreamRequestsTotal":0,"upstreamRateLimitedTotal":0,"upstreamRejectedTotal":0,"upstreamOtherFailuresTotal":0,"upstreamLastSuccessAt":null,"pollIntervalSeconds":15,"lastAttemptAt":1792404000000,"nextPollAt":null,"dataVersion":1,"dataConfirmedAt":1792404000000,"dataAgeSeconds":0,"alerts":1,"tracks":1,"activeTracks":1,"lastError":null}
    """.trimIndent()
}
