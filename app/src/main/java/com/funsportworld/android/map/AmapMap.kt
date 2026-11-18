package com.funsportworld.android.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.funsportworld.android.data.Checkpoint
import com.funsportworld.android.data.LatLon
import com.funsportworld.android.data.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

private const val TILE_SIZE = 256.0
/** Supported zoom range, also used by the on-screen +/- controls. */
const val MIN_ZOOM = 3.0
const val MAX_ZOOM = 19.0

data class MapCamera(val center: LatLon, val zoom: Double) {
    fun withCenter(lat: Double, lon: Double): MapCamera = copy(center = LatLon(lat, lon))
}

object Mercator {
    fun worldSize(zoom: Double): Double = TILE_SIZE * Math.pow(2.0, zoom)

    fun lonToX(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun latToY(lat: Double, zoom: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * worldSize(zoom)
    }

    fun xToLon(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return Math.toDegrees(atan(sinh(n)))
    }
}

/** Loads AMap raster tiles (GCJ-02, style=8) with a memory and disk cache. */
class TileLoader(context: Context) {
    private val appContext = context.applicationContext
    private val diskRoot = File(appContext.cacheDir, "amap_tiles").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(8)
    private val inFlight = HashSet<String>()
    private val order = LinkedHashMap<String, Unit>()

    val bitmaps = mutableStateMapOf<String, ImageBitmap>()

    fun request(z: Int, x: Int, y: Int) {
        val max = 1 shl z
        if (z < 0 || z > 19 || y < 0 || y >= max) return
        val wrappedX = ((x % max) + max) % max
        val key = "$z/$wrappedX/$y"
        synchronized(order) {
            if (bitmaps.containsKey(key) || inFlight.contains(key)) return
            inFlight.add(key)
        }
        scope.launch {
            permits.withPermit {
                val bitmap = runCatching { load(key, z, wrappedX, y) }.getOrNull()
                synchronized(order) {
                    inFlight.remove(key)
                    if (bitmap != null) {
                        bitmaps[key] = bitmap
                        order[key] = Unit
                        while (order.size > 260) {
                            val oldest = order.keys.firstOrNull() ?: break
                            order.remove(oldest)
                            bitmaps.remove(oldest)
                        }
                    }
                }
            }
        }
    }

    private fun load(key: String, z: Int, x: Int, y: Int): ImageBitmap? {
        val file = File(diskRoot, "$z/${x}_$y.png")
        if (file.isFile && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it.asImageBitmap() }
        }
        val sub = ((x + y) % 4) + 1
        val url = "https://webrd0$sub.is.autonavi.com/appmaptile?" +
            "lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$z"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "FunSportWorldAndroid/1.0 (map tiles)")
        }
        return try {
            connection.connect()
            if (connection.responseCode != 200) {
                Log.w("AmapTiles", "tile $key HTTP ${connection.responseCode}")
                return null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                Log.w("AmapTiles", "tile $key empty body")
                return null
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                Log.w("AmapTiles", "tile $key decode failed (${bytes.size} bytes)")
                return null
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
            bitmap.asImageBitmap()
        } catch (t: Throwable) {
            Log.w("AmapTiles", "tile $key failed: ${t.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}

data class MapViewport(val width: Float, val height: Float) {
    val valid: Boolean get() = width > 0 && height > 0
}

fun fitCamera(
    points: List<LatLon>,
    viewport: MapViewport,
    paddingPx: Float = 90f,
): MapCamera? {
    if (points.isEmpty() || !viewport.valid) return null
    var minLat = points.minOf { it.lat }
    var maxLat = points.maxOf { it.lat }
    var minLon = points.minOf { it.lon }
    var maxLon = points.maxOf { it.lon }
    if (maxLat - minLat < 1e-5 && maxLon - minLon < 1e-5) {
        return MapCamera(LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2), 16.0)
    }
    val center = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    val availW = max(60.0, (viewport.width - 2 * paddingPx).toDouble())
    val availH = max(60.0, (viewport.height - 2 * paddingPx).toDouble())
    var chosen = MIN_ZOOM
    var zoom = MIN_ZOOM
    while (zoom <= MAX_ZOOM) {
        val dx = abs(Mercator.lonToX(maxLon, zoom) - Mercator.lonToX(minLon, zoom))
        val dy = abs(Mercator.latToY(minLat, zoom) - Mercator.latToY(maxLat, zoom))
        if (dx <= availW && dy <= availH) chosen = zoom
        zoom += 0.25
    }
    return MapCamera(center, chosen.coerceIn(MIN_ZOOM, MAX_ZOOM))
}

private fun visibleTiles(camera: MapCamera, viewport: MapViewport): List<Triple<Int, Int, Int>> {
    if (!viewport.valid) return emptyList()
    val tileZoom = floor(camera.zoom).toInt().coerceIn(0, 19)
    val scale = Math.pow(2.0, camera.zoom - tileZoom)
    val centerX = Mercator.lonToX(camera.center.lon, tileZoom.toDouble())
    val centerY = Mercator.latToY(camera.center.lat, tileZoom.toDouble())
    val halfW = viewport.width / 2.0 / scale
    val halfH = viewport.height / 2.0 / scale
    val minX = floor((centerX - halfW) / TILE_SIZE).toInt()
    val maxX = ceil((centerX + halfW) / TILE_SIZE).toInt()
    val minY = floor((centerY - halfH) / TILE_SIZE).toInt()
    val maxY = ceil((centerY + halfH) / TILE_SIZE).toInt()
    val out = ArrayList<Triple<Int, Int, Int>>()
    val maxTile = 1 shl tileZoom
    for (ty in minY..maxY) {
        if (ty < 0 || ty >= maxTile) continue
        for (tx in minX..maxX) {
            out.add(Triple(tileZoom, tx, ty))
        }
    }
    return out
}

/**
 * Interactive raster map with markers and polylines.
 *
 * Gestures:
 *  * one finger drag = pan
 *  * two finger pinch = zoom
 *  * tap = [onTap] (or vertex selection)
 *  * long press on vertex = [onLongPressVertex]
 *  * drag starting on a vertex (when [vertexDragEnabled]) moves that vertex
 */
@Composable
fun AmapMap(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    modifier: Modifier = Modifier,
    checkpoints: List<Checkpoint> = emptyList(),
    planTrack: List<LatLon> = emptyList(),
    sketchVertices: List<LatLon> = emptyList(),
    sketchRing: List<LatLon> = emptyList(),
    selectedVertex: Int? = null,
    selectedCheckpoint: Int? = null,
    vertexDragEnabled: Boolean = true,
    showPlanStartEnd: Boolean = false,
    onViewportChanged: (MapViewport) -> Unit = {},
    onTap: ((LatLon) -> Unit)? = null,
    onTapVertex: ((Int) -> Unit)? = null,
    onLongPressVertex: ((Int) -> Unit)? = null,
    onVertexDrag: ((Int, LatLon) -> Unit)? = null,
    onVertexDragEnd: (() -> Unit)? = null,
    onTileCountChanged: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tileLoader = remember(context) { TileLoader(context) }
    var viewport by remember { mutableStateOf(MapViewport(0f, 0f)) }
    val latestCamera = rememberUpdatedState(camera)
    val latestVertices = rememberUpdatedState(sketchVertices)
    val latestDragEnabled = rememberUpdatedState(vertexDragEnabled)
    // A pointerInput block captures its first lambda; route these through
    // rememberUpdatedState so switching ADD/MOVE (or a new callback) takes
    // effect without recreating the gesture detector.
    val latestCameraChange = rememberUpdatedState(onCameraChange)
    val latestTap = rememberUpdatedState(onTap)
    val latestTapVertex = rememberUpdatedState(onTapVertex)
    val latestLongPressVertex = rememberUpdatedState(onLongPressVertex)
    val latestVertexDrag = rememberUpdatedState(onVertexDrag)
    val latestVertexDragEnd = rememberUpdatedState(onVertexDragEnd)

    val tileKeys by remember(camera, viewport) {
        derivedStateOf { visibleTiles(camera, viewport) }
    }
    LaunchedEffect(tileKeys) {
        tileKeys.forEach { (z, x, y) -> tileLoader.request(z, x, y) }
    }
    LaunchedEffect(tileLoader) {
        snapshotFlow { tileLoader.bitmaps.size }.collect { count ->
            onTileCountChanged?.invoke(count)
        }
    }

    // Finger-friendly hit area for vertex selection/drag.
    val touchRadiusPx = with(density) { 30.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                viewport = MapViewport(it.width.toFloat(), it.height.toFloat())
                onViewportChanged(viewport)
            }
            .pointerInput(Unit) {
                handleMapGestures(
                    cameraState = latestCamera,
                    verticesState = latestVertices,
                    dragEnabledState = latestDragEnabled,
                    touchRadiusPx = touchRadiusPx,
                    onCameraChange = { latestCameraChange.value(it) },
                    onTap = { point -> latestTap.value?.invoke(point) },
                    onTapVertex = { index -> latestTapVertex.value?.invoke(index) },
                    onLongPressVertex = { index -> latestLongPressVertex.value?.invoke(index) },
                    onVertexDrag = { index, point -> latestVertexDrag.value?.invoke(index, point) },
                    onVertexDragEnd = { latestVertexDragEnd.value?.invoke() },
                    viewport = { viewport },
                )
            },
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val tileZoom = floor(camera.zoom).toInt().coerceIn(0, 19)
        val scale = Math.pow(2.0, camera.zoom - tileZoom).toFloat()
        val centerX = Mercator.lonToX(camera.center.lon, tileZoom.toDouble()).toFloat()
        val centerY = Mercator.latToY(camera.center.lat, tileZoom.toDouble()).toFloat()
        val tileDrawSize = (TILE_SIZE * scale).toFloat()

        // Neutral paper-like grid so an unavailable tile server still leaves a
        // readable canvas for routes and checkpoints.
        drawRect(Color(0xFFF4F7F5))
        var gridY = 0f
        while (gridY < height) {
            drawLine(Color(0xFFE1E8E4), Offset(0f, gridY), Offset(width, gridY), strokeWidth = 1f)
            gridY += 128f
        }
        var gridX = 0f
        while (gridX < width) {
            drawLine(Color(0xFFE1E8E4), Offset(gridX, 0f), Offset(gridX, height), strokeWidth = 1f)
            gridX += 128f
        }

        fun project(point: LatLon): Offset {
            val x = (Mercator.lonToX(point.lon, tileZoom.toDouble()).toFloat() - centerX) * scale + width / 2f
            val y = (Mercator.latToY(point.lat, tileZoom.toDouble()).toFloat() - centerY) * scale + height / 2f
            return Offset(x, y)
        }

        val maxTile = 1 shl tileZoom
        val minTileX = floor((centerX - width / 2f / scale) / TILE_SIZE).toInt() - 1
        val maxTileX = ceil((centerX + width / 2f / scale) / TILE_SIZE).toInt() + 1
        val minTileY = floor((centerY - height / 2f / scale) / TILE_SIZE).toInt() - 1
        val maxTileY = ceil((centerY + height / 2f / scale) / TILE_SIZE).toInt() + 1
        for (ty in minTileY..maxTileY) {
            if (ty < 0 || ty >= maxTile) continue
            for (tx in minTileX..maxTileX) {
                val wrapped = ((tx % maxTile) + maxTile) % maxTile
                val bitmap = tileLoader.bitmaps["$tileZoom/$wrapped/$ty"] ?: continue
                val left = (tx * TILE_SIZE - centerX) * scale + width / 2f
                val top = (ty * TILE_SIZE - centerY) * scale + height / 2f
                drawImage(
                    image = bitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(tileDrawSize.toInt() + 1, tileDrawSize.toInt() + 1),
                )
            }
        }

        // Screen overscan guard for markers.
        fun onScreen(offset: Offset, margin: Float = 60f): Boolean =
            offset.x > -margin && offset.x < width + margin && offset.y > -margin && offset.y < height + margin

        val labelPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val smallPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT
        }

        // Plan track.
        if (planTrack.size >= 2) {
            val path = Path()
            planTrack.forEachIndexed { index, point ->
                val offset = project(point)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            drawPath(path, Color(0xFF00C18B), style = Stroke(width = 6f, cap = StrokeCap.Round))
        }

        // Smoothed sketch ring.
        if (sketchRing.size >= 2) {
            val path = Path()
            sketchRing.forEachIndexed { index, point ->
                val offset = project(point)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            drawPath(path, Color(0xFF7C3AED), style = Stroke(width = 7f, cap = StrokeCap.Round))
        }

        // Sketch vertex order line (dashed closure from last to first).
        if (sketchVertices.size >= 2) {
            val path = Path()
            sketchVertices.forEachIndexed { index, point ->
                val offset = project(point)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            if (sketchVertices.size >= 3) {
                val first = project(sketchVertices.first())
                path.lineTo(first.x, first.y)
            }
            drawPath(
                path,
                Color(0xFF2F6FED),
                style = Stroke(
                    width = 4f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f)),
                ),
            )
        }

        val checkpointRadius = 26f
        checkpoints.forEach { checkpoint ->
            val offset = project(checkpoint.point)
            if (!onScreen(offset)) return@forEach
            val selected = selectedCheckpoint == checkpoint.index
            drawCircle(Color.White, radius = checkpointRadius + 3f, center = offset)
            drawCircle(
                if (selected) Color(0xFFD9822B) else Color(0xFFBB7412),
                radius = checkpointRadius,
                center = offset,
            )
            labelPaint.color = android.graphics.Color.WHITE
            labelPaint.textSize = 26f
            drawContext.canvas.nativeCanvas.drawText(
                (checkpoint.index + 1).toString(),
                offset.x,
                offset.y + 9f,
                labelPaint,
            )
        }

        if (showPlanStartEnd && planTrack.size >= 2) {
            drawStartEnd(project(planTrack.first()), project(planTrack.last()), drawContext.canvas.nativeCanvas, labelPaint)
        }

        // Vertices.
        sketchVertices.forEachIndexed { index, point ->
            val offset = project(point)
            if (!onScreen(offset)) return@forEachIndexed
            val selected = selectedVertex == index
            val radius = if (selected) 16f else 12f
            drawCircle(Color.White, radius = radius + 4f, center = offset)
            drawCircle(if (selected) Color(0xFF0E7A5F) else Color(0xFF2F6FED), radius = radius, center = offset)
            if (selected) {
                smallPaint.color = android.graphics.Color.WHITE
                smallPaint.textSize = 22f
                drawContext.canvas.nativeCanvas.drawText(
                    (index + 1).toString(),
                    offset.x,
                    offset.y + 8f,
                    smallPaint,
                )
            }
        }

        // AMap attribution.
        val attribution = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(170, 60, 60, 60)
            textSize = 22f
        }
        drawContext.canvas.nativeCanvas.drawText(
            "© 高德地图",
            width - 12f,
            height - 12f,
            attribution,
        )
    }
}

private fun drawStartEnd(
    start: Offset,
    end: Offset,
    canvas: android.graphics.Canvas,
    paint: Paint,
) {
    val fill = Paint().apply { isAntiAlias = true }
    val border = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = android.graphics.Color.WHITE
    }
    fill.color = android.graphics.Color.rgb(21, 116, 72)
    canvas.drawRect(start.x - 18f, start.y - 18f, start.x + 18f, start.y + 18f, fill)
    canvas.drawRect(start.x - 18f, start.y - 18f, start.x + 18f, start.y + 18f, border)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 24f
    canvas.drawText("起", start.x, start.y + 9f, paint)

    fill.color = android.graphics.Color.rgb(177, 57, 70)
    canvas.drawRect(end.x - 18f, end.y - 18f, end.x + 18f, end.y + 18f, fill)
    canvas.drawRect(end.x - 18f, end.y - 18f, end.x + 18f, end.y + 18f, border)
    canvas.drawText("终", end.x, end.y + 9f, paint)
}

private suspend fun PointerInputScope.handleMapGestures(
    cameraState: androidx.compose.runtime.State<MapCamera>,
    verticesState: androidx.compose.runtime.State<List<LatLon>>,
    dragEnabledState: androidx.compose.runtime.State<Boolean>,
    touchRadiusPx: Float,
    onCameraChange: (MapCamera) -> Unit,
    onTap: ((LatLon) -> Unit)?,
    onTapVertex: ((Int) -> Unit)?,
    onLongPressVertex: ((Int) -> Unit)?,
    onVertexDrag: ((Int, LatLon) -> Unit)?,
    onVertexDragEnd: (() -> Unit)?,
    viewport: () -> MapViewport,
) {
    val touchSlop = viewConfiguration.touchSlop
    // Real fingers often drift a few pixels during a tap. Delaying map panning
    // until this larger threshold keeps those touches as taps (add/select)
    // instead of turning them into a pan. Vertex dragging still uses the
    // smaller system touch slop.
    val tapSlop = touchSlop * 2.2f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val camera = cameraState.value
        val vertices = verticesState.value
        val viewportValue = viewport()
        if (!viewportValue.valid) return@awaitEachGesture

        val tileZoom = floor(camera.zoom).toInt().coerceIn(0, 19)
        val scale = Math.pow(2.0, camera.zoom - tileZoom).toFloat()
        val centerX = Mercator.lonToX(camera.center.lon, tileZoom.toDouble()).toFloat()
        val centerY = Mercator.latToY(camera.center.lat, tileZoom.toDouble()).toFloat()

        fun screenOf(point: LatLon): Offset {
            val x = (Mercator.lonToX(point.lon, tileZoom.toDouble()).toFloat() - centerX) * scale + viewportValue.width / 2f
            val y = (Mercator.latToY(point.lat, tileZoom.toDouble()).toFloat() - centerY) * scale + viewportValue.height / 2f
            return Offset(x, y)
        }

        // Hit-testing is independent of drag mode so a simple tap always selects
        // a route point; dragging is only enabled when the caller allows it.
        var hitVertex: Int? = null
        var best = touchRadiusPx
        vertices.forEachIndexed { index, point ->
            val distance = (screenOf(point) - down.position).getDistance()
            if (distance <= best) {
                best = distance
                hitVertex = index
            }
        }
        val canDragVertex = dragEnabledState.value && onVertexDrag != null

        var lastPosition = down.position
        var moved = false
        var longPressFired = false
        val downTime = down.uptimeMillis
        var draggingVertex = hitVertex != null && canDragVertex
        var latestVertexPoint: LatLon? = null
        // The Compose state can lag one frame behind, which loses increments
        // during a fast pinch. Keep a local camera current for this gesture.
        var workingCamera = camera

        while (true) {
            val event: PointerEvent = awaitPointerEvent()
            val changes = event.changes
            if (changes.isEmpty()) break
            if (changes.size >= 2) {
                val current = workingCamera
                val zoomChange = event.calculateZoom()
                val pan = event.calculatePan()
                var next = current
                if (zoomChange != 1f) {
                    val newZoom = (current.zoom + log2(zoomChange.toDouble()))
                        .coerceIn(MIN_ZOOM, MAX_ZOOM)
                    next = next.copy(zoom = newZoom)
                }
                if (pan != Offset.Zero) {
                    // Apply zoom and pan in one camera update; updating twice with
                    // a stale `cameraState.value` would discard the zoom change.
                    next = panCamera(next, pan)
                }
                if (next != current) {
                    workingCamera = next
                    onCameraChange(next)
                }
                changes.forEach { it.consume() }
                moved = true
                lastPosition = changes.first().position
                continue
            }
            val change: PointerInputChange = changes.first()
            val delta = change.position - lastPosition
            val moveThreshold = if (draggingVertex) touchSlop else tapSlop
            if (!moved && delta.getDistance() > moveThreshold) moved = true

            if (!moved && !longPressFired && change.uptimeMillis - downTime > 550) {
                // Long-press must be checked before the drag branch: a touch
                // that starts on a vertex is in "dragging" state from the very
                // beginning, even when the finger has not moved yet. A long
                // press on empty map must not swallow the tap: slow taps are
                // common, and they should still add a point on release.
                val vertexIndex = hitVertex
                if (vertexIndex != null) {
                    longPressFired = true
                    draggingVertex = false
                    onLongPressVertex?.invoke(vertexIndex)
                }
            } else if (draggingVertex) {
                if (moved) {
                    val vertexIndex = hitVertex
                    if (vertexIndex != null) {
                        val target = screenToLatLon(
                            change.position.x, change.position.y,
                            workingCamera, viewportValue,
                        )
                        latestVertexPoint = target
                        onVertexDrag?.invoke(vertexIndex, target)
                    }
                    change.consume()
                }
            } else if (moved) {
                workingCamera = panCamera(workingCamera, delta)
                onCameraChange(workingCamera)
                change.consume()
            }
            lastPosition = change.position

            if (change.changedToUp()) {
                if (draggingVertex) {
                    onVertexDragEnd?.invoke()
                    draggingVertex = false
                }
                if (!longPressFired) {
                    val vertexIndex = hitVertex
                    if (vertexIndex != null && onTapVertex != null) {
                        // Starting a touch on a vertex always selects it; a drag
                        // additionally moved it. This makes selection forgiving
                        // on real fingers that move a few pixels.
                        onTapVertex.invoke(vertexIndex)
                    } else if (!moved) {
                        val tap = screenToLatLon(
                            change.position.x,
                            change.position.y,
                            workingCamera,
                            viewportValue,
                        )
                        onTap?.invoke(tap)
                    }
                }
                break
            }
        }
    }
}

private fun panCamera(camera: MapCamera, pan: Offset): MapCamera {
    val tileZoom = floor(camera.zoom).toInt().coerceIn(0, 19)
    val scale = Math.pow(2.0, camera.zoom - tileZoom).toFloat()
    val centerX = Mercator.lonToX(camera.center.lon, tileZoom.toDouble()).toFloat() - pan.x / scale
    val centerY = Mercator.latToY(camera.center.lat, tileZoom.toDouble()).toFloat() - pan.y / scale
    val lon = Mercator.xToLon(centerX.toDouble(), tileZoom.toDouble()).coerceIn(-180.0, 180.0)
    val lat = Mercator.yToLat(centerY.toDouble(), tileZoom.toDouble()).coerceIn(-85.0, 85.0)
    return MapCamera(LatLon(lat, lon), camera.zoom)
}

private fun screenToLatLon(
    x: Float,
    y: Float,
    camera: MapCamera,
    viewport: MapViewport,
): LatLon {
    val tileZoom = floor(camera.zoom).toInt().coerceIn(0, 19)
    val scale = Math.pow(2.0, camera.zoom - tileZoom).toFloat()
    val centerX = Mercator.lonToX(camera.center.lon, tileZoom.toDouble()).toFloat()
    val centerY = Mercator.latToY(camera.center.lat, tileZoom.toDouble()).toFloat()
    val worldX = (x - viewport.width / 2f) / scale + centerX
    val worldY = (y - viewport.height / 2f) / scale + centerY
    val lon = Mercator.xToLon(worldX.toDouble(), tileZoom.toDouble())
    val lat = Mercator.yToLat(worldY.toDouble(), tileZoom.toDouble())
    return LatLon(lat, lon)
}

/** Small helper used by fit buttons: geographic midpoint of a point set. */
fun centerOf(points: List<LatLon>): LatLon? {
    if (points.isEmpty()) return null
    var lat = 0.0
    var lon = 0.0
    points.forEach { lat += it.lat; lon += it.lon }
    return LatLon(lat / points.size, lon / points.size)
}

fun distanceMeters(a: LatLon, b: LatLon): Double = haversineMeters(a, b)

@Suppress("unused")
private fun unusedLocale(): Locale = Locale.US
