package com.funsportworld.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** GCJ-02 coordinate pair (latitude, longitude). */
data class LatLon(val lat: Double, val lon: Double) {
    fun isValid(): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            !(lat == 0.0 && lon == 0.0) && lat != -1.0 && lon != -1.0

    fun toJson(): JSONArray = JSONArray().put(round7(lat)).put(round7(lon))

    companion object {
        const val MET_PER_DEG_LAT = 111132.0
        const val MET_PER_DEG_LNG = 86600.0

        fun round7(value: Double): Double = String.format(java.util.Locale.US, "%.7f", value).toDouble()

        fun fromJson(array: JSONArray?): LatLon? {
            if (array == null || array.length() < 2) return null
            val lat = array.optDouble(0, Double.NaN)
            val lon = array.optDouble(1, Double.NaN)
            val point = LatLon(lat, lon)
            return if (point.isValid()) point else null
        }

        fun listFromJson(array: JSONArray?): List<LatLon> {
            if (array == null) return emptyList()
            val out = ArrayList<LatLon>(array.length())
            for (i in 0 until array.length()) {
                fromJson(array.optJSONArray(i))?.let { out.add(it) }
            }
            return out
        }

        fun toJsonArray(points: List<LatLon>): JSONArray {
            val array = JSONArray()
            points.forEach { array.put(it.toJson()) }
            return array
        }
    }
}

fun haversineMeters(a: LatLon, b: LatLon): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 6371008.8 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
}

fun polylineLength(points: List<LatLon>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
    return total
}

/** A checkpoint returned by the Python core (GCJ-02 coordinates). */
data class Checkpoint(
    val index: Int,
    val id: Int?,
    val name: String,
    val lat: Double,
    val lon: Double,
    val position: Int,
    val isFixed: Int,
    val source: String,
    val deltaM: Double?,
) {
    val point: LatLon get() = LatLon(lat, lon)
}

data class AccountRow(
    val username: String,
    val active: Boolean,
    val hasSession: Boolean,
    val hasPassword: Boolean,
    val remember: Boolean,
    val name: String,
    val uid: Int?,
)

data class PointsCacheInfo(val count: Int, val ts: Long)

data class AppSettings(
    val username: String,
    val hasPassword: Boolean,
    val hasAmapKey: Boolean,
    val city: String,
    val anchorLat: String,
    val anchorLon: String,
    val startBeforeMin: Int,
    val startBeforeMax: Int,
    val remember: Boolean,
    val loggedIn: Boolean,
    val sessionName: String,
    val sessionUid: Int?,
    val accounts: List<AccountRow>,
    val activeAccount: String,
    val pointsCache: PointsCacheInfo?,
    val dataDir: String,
)

data class PlanCheckpoint(
    val index: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val position: Int,
    val nearestM: Double?,
)

data class PlanUi(
    val planId: String,
    val previewOnly: Boolean,
    val details: String,
    val provider: String,
    val startMs: Long,
    val totalTime: Int,
    val totalDistance: Double,
    val totalSteps: Int,
    val paceSecPerKm: Double,
    val cadence: Double,
    val windowOk: Boolean,
    val windowReason: String,
    val freezeRunTime: Int,
    val allowOutsideWindow: Boolean,
    val seed: Long,
    val checkpoints: List<PlanCheckpoint>,
    val allPassed: Boolean?,
    val passedCount: Int?,
    val eventCount: Int,
    val completionSupported: Boolean?,
    val completionComplete: Boolean?,
    val completionReason: Int?,
    val coordinateReport: JSONObject?,
    val checkpointOrder: JSONObject?,
    val trackPreview: List<LatLon>,
    val locationCount: Int,
    val sketchVertices: Int?,
    val sketchPoints: Int?,
    val sketchOrderOk: Boolean?,
    val sketchRotated: Boolean?,
    val sketchReversed: Boolean?,
    val sketchSnapped: Boolean?,
    val ringLengthM: Double?,
)

data class LogLine(val seq: Long, val time: Long, val text: String)

/** Where a route came from, used to decide which actions make sense. */
enum class RouteSource { DRAFT, LIBRARY, IMPORT }

data class RouteMeta(
    val id: String,
    val name: String,
    val note: String,
    val lengthM: Double,
    val vertexCount: Int,
    val checkpointCount: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val isDefault: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("note", note)
        put("length_m", lengthM)
        put("vertex_count", vertexCount)
        if (checkpointCount != null) put("checkpoint_count", checkpointCount)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("last_used_at", lastUsedAt)
        put("is_default", isDefault)
    }

    companion object {
        fun fromJson(obj: JSONObject): RouteMeta = RouteMeta(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name", "未命名路线"),
            note = obj.optString("note", ""),
            lengthM = obj.optDouble("length_m", 0.0),
            vertexCount = obj.optInt("vertex_count", 0),
            checkpointCount = if (obj.has("checkpoint_count")) obj.optInt("checkpoint_count") else null,
            createdAt = obj.optLong("created_at"),
            updatedAt = obj.optLong("updated_at"),
            lastUsedAt = obj.optLong("last_used_at"),
            isDefault = obj.optBoolean("is_default", false),
        )
    }
}

/** A route payload compatible with the desktop app's route JSON format. */
data class RoutePayload(
    val vertices: List<LatLon>,
    val ring: List<LatLon>,
    val lengthM: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("coord", "gcj02")
        put("vertices", LatLon.toJsonArray(vertices))
        put("points", LatLon.toJsonArray(ring))
        put("length_m", String.format(java.util.Locale.US, "%.2f", lengthM).toDouble())
    }

    fun toWireJson(): String = toJson().toString()

    companion object {
        fun fromJson(obj: JSONObject): RoutePayload? {
            val vertices = LatLon.listFromJson(obj.optJSONArray("vertices"))
            val ring = LatLon.listFromJson(obj.optJSONArray("points"))
            if (vertices.size < 3) return null
            val resolvedRing = if (ring.size >= 3) ring else vertices + vertices.first()
            return RoutePayload(
                vertices = vertices,
                ring = resolvedRing,
                lengthM = obj.optDouble("length_m", polylineLength(resolvedRing)),
            )
        }
    }
}

/** Parsing helpers shared by the bridge and screens. */
object Parse {
    fun settings(state: JSONObject): AppSettings {
        val accountsArray = state.optJSONArray("accounts") ?: JSONArray()
        val accounts = ArrayList<AccountRow>(accountsArray.length())
        for (i in 0 until accountsArray.length()) {
            val row = accountsArray.optJSONObject(i) ?: continue
            accounts.add(
                AccountRow(
                    username = row.optString("username"),
                    active = row.optBoolean("active"),
                    hasSession = row.optBoolean("has_session"),
                    hasPassword = row.optBoolean("has_password"),
                    remember = row.optBoolean("remember"),
                    name = row.optString("name"),
                    uid = if (row.has("uid") && !row.isNull("uid")) row.optInt("uid") else null,
                )
            )
        }
        val cacheObj = state.optJSONObject("points_cache")
        val cache = if (cacheObj != null && cacheObj.optInt("count", 0) > 0) {
            PointsCacheInfo(cacheObj.optInt("count"), cacheObj.optLong("ts"))
        } else null
        return AppSettings(
            username = state.optString("username"),
            hasPassword = state.optBoolean("has_password"),
            hasAmapKey = state.optBoolean("has_amap_key"),
            city = state.optString("city"),
            anchorLat = state.optString("anchor_lat"),
            anchorLon = state.optString("anchor_lon"),
            startBeforeMin = state.optInt("start_before_min", 30),
            startBeforeMax = state.optInt("start_before_max", 300),
            remember = state.optBoolean("remember"),
            loggedIn = state.optBoolean("logged_in"),
            sessionName = state.optString("session_name"),
            sessionUid = if (state.has("session_uid") && !state.isNull("session_uid")) state.optInt("session_uid") else null,
            accounts = accounts,
            activeAccount = state.optString("active_account"),
            pointsCache = cache,
            dataDir = state.optString("data_dir"),
        )
    }

    fun checkpoints(pointsResponse: JSONObject): List<Checkpoint> {
        val array = pointsResponse.optJSONArray("checkpoints") ?: JSONArray()
        val out = ArrayList<Checkpoint>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            out.add(
                Checkpoint(
                    index = obj.optInt("index", i),
                    id = if (obj.has("id") && !obj.isNull("id")) obj.optInt("id") else null,
                    name = obj.optString("name"),
                    lat = lat,
                    lon = lon,
                    position = obj.optInt("position", 999),
                    isFixed = obj.optInt("isFixed", 0),
                    source = obj.optString("source"),
                    deltaM = if (obj.has("delta_m") && !obj.isNull("delta_m")) obj.optDouble("delta_m") else null,
                )
            )
        }
        return out
    }

    fun plan(obj: JSONObject): PlanUi {
        val checkpointsArray = obj.optJSONArray("checkpoints") ?: JSONArray()
        val planCheckpoints = ArrayList<PlanCheckpoint>(checkpointsArray.length())
        for (i in 0 until checkpointsArray.length()) {
            val row = checkpointsArray.optJSONObject(i) ?: continue
            planCheckpoints.add(
                PlanCheckpoint(
                    index = row.optInt("index", i),
                    name = row.optString("name"),
                    lat = row.optDouble("lat", 0.0),
                    lon = row.optDouble("lon", 0.0),
                    position = row.optInt("position", 999),
                    nearestM = if (row.has("nearest_m") && !row.isNull("nearest_m")) row.optDouble("nearest_m") else null,
                )
            )
        }
        val evaluation = obj.optJSONObject("checkpoint_evaluation")
        val completion = obj.optJSONObject("completion")
        val routeMeta = obj.optJSONObject("route_meta")
        val sketch = routeMeta?.optJSONObject("sketch")
        val track = obj.optJSONArray("track_preview") ?: JSONArray()
        val preview = ArrayList<LatLon>(track.length())
        for (i in 0 until track.length()) {
            LatLon.fromJson(track.optJSONArray(i))?.let { preview.add(it) }
        }
        return PlanUi(
            planId = obj.optString("plan_id"),
            previewOnly = obj.optBoolean("preview_only"),
            details = obj.optString("details"),
            provider = obj.optString("provider"),
            startMs = obj.optLong("start_ms"),
            totalTime = obj.optInt("total_time"),
            totalDistance = obj.optDouble("total_distance"),
            totalSteps = obj.optInt("total_steps"),
            paceSecPerKm = obj.optDouble("pace_s_per_km"),
            cadence = obj.optDouble("cadence"),
            windowOk = obj.optBoolean("window_ok"),
            windowReason = obj.optString("window_reason"),
            freezeRunTime = obj.optInt("freeze_run_time"),
            allowOutsideWindow = obj.optBoolean("allow_outside_window"),
            seed = obj.optLong("seed"),
            checkpoints = planCheckpoints,
            allPassed = if (evaluation != null && evaluation.has("all_passed") && !evaluation.isNull("all_passed")) evaluation.optBoolean("all_passed") else null,
            passedCount = if (evaluation != null && evaluation.has("passed_count") && !evaluation.isNull("passed_count")) evaluation.optInt("passed_count") else null,
            eventCount = evaluation?.optInt("event_count", 0) ?: 0,
            completionSupported = completion?.let { if (it.has("supported") && !it.isNull("supported")) it.optBoolean("supported") else null },
            completionComplete = completion?.let { if (it.has("complete") && !it.isNull("complete")) it.optBoolean("complete") else null },
            completionReason = completion?.let { if (it.has("reason") && !it.isNull("reason")) it.optInt("reason") else null },
            coordinateReport = obj.optJSONObject("coordinate_report"),
            checkpointOrder = obj.optJSONObject("checkpoint_order"),
            trackPreview = preview,
            locationCount = obj.optInt("location_count"),
            sketchVertices = sketch?.optInt("vertices"),
            sketchPoints = sketch?.optInt("points"),
            sketchOrderOk = sketch?.let { if (it.has("checkpoint_order_ok") && !it.isNull("checkpoint_order_ok")) it.optBoolean("checkpoint_order_ok") else null },
            sketchRotated = sketch?.optBoolean("rotated"),
            sketchReversed = sketch?.optBoolean("reversed"),
            sketchSnapped = sketch?.optBoolean("snapped"),
            ringLengthM = if (routeMeta?.has("ring_length_m") == true) routeMeta.optDouble("ring_length_m") else null,
        )
    }

    fun logLines(obj: JSONObject): Pair<Long, List<LogLine>> {
        val next = obj.optLong("next", 0L)
        val array = obj.optJSONArray("lines") ?: JSONArray()
        val lines = ArrayList<LogLine>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            lines.add(LogLine(row.optLong("seq"), row.optLong("time"), row.optString("text")))
        }
        return next to lines
    }
}

/** Lightweight arithmetic validation shared by UI and route storage. */
fun isFiniteNumber(value: Double): Boolean = value.isFinite() && !value.isNaN()

fun approxEqual(a: Double, b: Double, epsilon: Double = 1e-9): Boolean = abs(a - b) <= epsilon
