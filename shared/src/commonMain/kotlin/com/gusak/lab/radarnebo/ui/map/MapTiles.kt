package com.gusak.lab.radarnebo.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gusak.lab.radarnebo.data.MapBackground
import com.gusak.lab.radarnebo.geo.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import radarnebo.shared.generated.resources.Res
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.time.Clock

/**
 * A web map tile set (Web Mercator, 256 px tiles) and the credit its terms require. The street maps are Carto's
 * label-free basemaps, bundled with the app (see [bundled]) so they show offline and leave cities to the map's
 * own labels; satellite imagery comes from Esri's public service, which needs no key.
 */
enum class TileSource(val maxZoom: Int, val attribution: String, private val template: String) {
    DarkStreets(BUNDLED_MAX_ZOOM, "Map © CARTO, © OpenStreetMap contributors", "bundled:carto-dark"),
    LightStreets(BUNDLED_MAX_ZOOM, "Map © CARTO, © OpenStreetMap contributors", "bundled:carto-light"),
    Satellite(18, "Imagery © Esri, Maxar, Earthstar Geographics", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}");

    /** The resource folder (under files/map) holding this set's tiles as z/x/y.png, or null if downloaded. */
    val bundled: String? get() = template.removePrefix("bundled:").takeIf { it != template }

    fun url(z: Int, x: Int, y: Int) = template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")

    companion object {
        /** The tiles for [background]; street maps follow the theme. Null shows no map. */
        fun of(background: MapBackground, dark: Boolean): TileSource? = when (background) {
            MapBackground.None -> null
            MapBackground.Streets -> if (dark) DarkStreets else LightStreets
            MapBackground.Satellite -> Satellite
        }
    }
}

/** The deepest zoom tools/download-map-tiles.py stores (MAX_TILE_ZOOM there); closer views upscale it. */
private const val BUNDLED_MAX_ZOOM = 9

private data class TileKey(val source: TileSource, val z: Int, val x: Int, val y: Int)

/**
 * The tiles bundled for each street style, per zoom: the ranges in files/map/coverage.txt, which
 * tools/download-map-tiles.py writes from the map's visible bounds. Tiles outside it are never looked up.
 */
private class BundledCoverage(private val ranges: Map<Int, IntArray>) {
    operator fun contains(key: TileKey): Boolean {
        val r = ranges[key.z] ?: return false
        return key.x in r[0]..r[1] && key.y in r[2]..r[3]
    }

    companion object {
        fun parse(text: String) = BundledCoverage(
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line -> line.split(Regex("\\s+")).map(String::toInt) }
                .associate { it[0] to intArrayOf(it[1], it[2], it[3], it[4]) },
        )
    }
}

/**
 * Loads map tiles on demand, from the app's resources for [TileSource.bundled] sets and over the network for
 * the rest, and keeps the latest [capacity] in memory. [get] never waits: it returns what
 * is cached and starts loading what isn't; [loaded] changes when a tile arrives, so a canvas reading it redraws.
 * Used from the main thread only (drawing happens there), so the cache needs no locking.
 *
 * With [blocking], bundled tiles are read before [get] returns instead: for previews, which render only their
 * first frames. Without a [client], other tiles are never downloaded.
 */
class MapTiles(
    private val client: HttpClient?,
    private val capacity: Int = 400,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val blocking: Boolean = false,
) {
    private val cache = LinkedHashMap<TileKey, ImageBitmap>()
    private val loading = HashSet<TileKey>()
    private val failedAt = HashMap<TileKey, Long>()

    // Tile servers ask clients to keep parallel requests low.
    private val permits = Semaphore(6)

    // Read once, on the first bundled tile asked for; until then bundled tiles are reported missing.
    private var coverage: BundledCoverage? = null
    private var coverageRequested = false

    /** Counts arrivals; reading it in a draw block makes the canvas redraw as tiles come in. */
    var loaded by mutableIntStateOf(0)
        private set

    /** A tile already in memory, or null; never starts a download. */
    fun cached(source: TileSource, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = TileKey(source, z, x, y)
        return cache.remove(key)?.also { cache[key] = it }
    }

    fun get(source: TileSource, z: Int, x: Int, y: Int): ImageBitmap? {
        cached(source, z, x, y)?.let { return it }
        val key = TileKey(source, z, x, y)
        val bundled = source.bundled
        if (bundled != null && !isBundled(key)) return null
        if (bundled == null && client == null) return null
        if (blocking && bundled != null) {
            val image = runBlocking { readBundled(bundled, z, x, y) } ?: return null
            store(key, image)
            return image
        }
        val now = Clock.System.now().toEpochMilliseconds()
        if (key in loading || (failedAt[key]?.let { now - it < RETRY_MS } == true)) return null
        loading += key
        scope.launch {
            val image = withContext(Dispatchers.Default) {
                if (bundled != null) {
                    readBundled(bundled, z, x, y)
                } else {
                    permits.withPermit {
                        runCatching {
                            val response = client!!.get(source.url(z, x, y)) { header(HttpHeaders.UserAgent, USER_AGENT) }
                            if (response.status.isSuccess()) response.bodyAsBytes().decodeToImageBitmap() else null
                        }.getOrNull()
                    }
                }
            }
            loading -= key
            if (image == null) {
                failedAt[key] = Clock.System.now().toEpochMilliseconds()
            } else {
                failedAt -= key
                store(key, image)
                loaded++
            }
        }
        return null
    }

    private fun store(key: TileKey, image: ImageBitmap) {
        cache[key] = image
        while (cache.size > capacity) cache.remove(cache.keys.first())
    }

    private suspend fun readBundled(directory: String, z: Int, x: Int, y: Int): ImageBitmap? =
        runCatching { Res.readBytes("files/map/$directory/$z/$x/$y.png").decodeToImageBitmap() }.getOrNull()

    private fun isBundled(key: TileKey): Boolean {
        coverage?.let { return key in it }
        if (blocking) {
            val text = runBlocking { runCatching { Res.readBytes("files/map/coverage.txt").decodeToString() }.getOrNull() }
            return key in BundledCoverage.parse(text.orEmpty()).also { coverage = it }
        }
        if (!coverageRequested) {
            coverageRequested = true
            scope.launch {
                val text = withContext(Dispatchers.Default) {
                    runCatching { Res.readBytes("files/map/coverage.txt").decodeToString() }.getOrNull()
                }
                coverage = BundledCoverage.parse(text.orEmpty())
                loaded++
            }
        }
        return false
    }

    private companion object {
        const val RETRY_MS = 30_000L
        const val USER_AGENT = "RadarNebo map client"
    }
}

/** Map tiles for the map to draw under the oblasts; null (tests, most previews) draws none. */
val LocalMapTiles = staticCompositionLocalOf<MapTiles?> { null }

private const val TILE_PX = 256
private const val MAX_TILES = 80

// Web Mercator, which tiles use: x is linear in longitude, y is not in latitude.
private fun lonToTileX(lon: Double, n: Int) = (lon + 180.0) / 360.0 * n
private fun latToTileY(lat: Double, n: Int) = (1.0 - ln(tan(lat * PI / 180.0) + 1.0 / kotlin.math.cos(lat * PI / 180.0)) / PI) / 2.0 * n
private fun tileYToLat(y: Double, n: Int) = atan(sinh(PI * (1.0 - 2.0 * y / n))) * 180.0 / PI
private fun tileXToLon(x: Double, n: Int) = x / n * 360.0 - 180.0

/**
 * Draws the tiles covering the view. The map is equirectangular and tiles are Mercator, so each tile is drawn
 * as horizontal strips, each placed at its own latitude; this keeps coastlines on the oblast borders. A tile
 * still loading is stood in for by the nearest coarser one already cached.
 */
internal fun DrawScope.drawMapTiles(tiles: MapTiles, source: TileSource, camera: MapCamera) {
    val topLeft = MapProjection.toGeo(camera.toWorld(Offset.Zero, size))
    val bottomRight = MapProjection.toGeo(camera.toWorld(Offset(size.width, size.height), size))
    // Screen pixels per degree of longitude decide the zoom level whose tiles are at least that sharp.
    val pxPerDegree = size.width / (bottomRight.lon - topLeft.lon)
    var z = ceil(ln(pxPerDegree * 360.0 / TILE_PX) / ln(2.0)).toInt().coerceIn(3, source.maxZoom)
    while (true) {
        val n = 1 shl z
        val x0 = floor(lonToTileX(topLeft.lon, n)).toInt().coerceAtLeast(0)
        val x1 = floor(lonToTileX(bottomRight.lon, n)).toInt().coerceAtMost(n - 1)
        val y0 = floor(latToTileY(topLeft.lat.coerceIn(-85.0, 85.0), n)).toInt().coerceAtLeast(0)
        val y1 = floor(latToTileY(bottomRight.lat.coerceIn(-85.0, 85.0), n)).toInt().coerceAtMost(n - 1)
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > MAX_TILES && z > 3) {
            z--
            continue
        }
        for (x in x0..x1) for (y in y0..y1) {
            val image = tiles.get(source, z, x, y)
            if (image != null) {
                drawTile(image, IntOffset.Zero, TILE_PX, z, x, y, camera)
            } else {
                // A cached ancestor, cropped to this tile's part of it.
                for (up in 1..4) {
                    if (z - up < 0) break
                    val parent = tiles.cached(source, z - up, x shr up, y shr up) ?: continue
                    val part = TILE_PX shr up
                    val src = IntOffset((x and ((1 shl up) - 1)) * part, (y and ((1 shl up) - 1)) * part)
                    drawTile(parent, src, part, z, x, y, camera)
                    break
                }
            }
        }
        break
    }
}

private const val STRIPS = 8

private fun DrawScope.drawTile(image: ImageBitmap, src: IntOffset, srcSize: Int, z: Int, x: Int, y: Int, camera: MapCamera) {
    val n = 1 shl z
    val left = camera.toScreen(MapProjection.toWorld(GeoPoint(0.0, tileXToLon(x.toDouble(), n))), size).x
    val right = camera.toScreen(MapProjection.toWorld(GeoPoint(0.0, tileXToLon(x + 1.0, n))), size).x
    val l = left.roundToInt()
    val w = right.roundToInt() - l
    for (i in 0 until STRIPS) {
        val lat0 = tileYToLat(y + i.toDouble() / STRIPS, n)
        val lat1 = tileYToLat(y + (i + 1).toDouble() / STRIPS, n)
        val top = camera.toScreen(MapProjection.toWorld(GeoPoint(lat0, 0.0)), size).y.roundToInt()
        val bottom = camera.toScreen(MapProjection.toWorld(GeoPoint(lat1, 0.0)), size).y.roundToInt()
        if (bottom < 0 || top > size.height || w <= 0 || bottom <= top) continue
        val sy0 = src.y + srcSize * i / STRIPS
        val sy1 = src.y + srcSize * (i + 1) / STRIPS
        drawImage(
            image,
            srcOffset = IntOffset(src.x, sy0),
            srcSize = IntSize(srcSize, (sy1 - sy0).coerceAtLeast(1)),
            dstOffset = IntOffset(l, top),
            dstSize = IntSize(w, bottom - top),
            filterQuality = FilterQuality.Medium,
        )
    }
}
