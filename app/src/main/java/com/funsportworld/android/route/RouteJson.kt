package com.funsportworld.android.route

import com.funsportworld.android.data.LatLon
import com.funsportworld.android.data.RoutePayload
import com.funsportworld.android.data.haversineMeters
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversion and import/export helpers for route JSON.
 *
 * The desktop app stores routes as GCJ-02, optionally with a declared source
 * coordinate system. Files produced by other tools (GPX export, BD-09 tools)
 * are normalised here before they reach [RouteMath].
 */
object RouteJson {

    private const val CLOSE_TOLERANCE_M = 3.0
    private const val X_PI = Math.PI * 3000.0 / 180.0

    fun export(payload: RoutePayload): String = payload.toJson().toString(2)

    fun parse(text: String): RoutePayload {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("路线文件为空")
        val root: Any = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (t: Throwable) {
            throw IllegalArgumentException("路线文件不是有效的 JSON：${t.message}")
        }
        val obj: JSONObject = when (root) {
            is JSONArray -> JSONObject().put("points", root)
            is JSONObject -> root
            else -> throw IllegalArgumentException("路线文件必须是 JSON 对象或坐标数组")
        }
        val coord = obj.optString("coord", "gcj02").trim().lowercase()
        val converter: (LatLon) -> LatLon = when (coord) {
            "gcj02", "gcj", "gcj-02" -> { p -> p }
            "bd09", "bd", "bd-09" -> { p -> bd09ToGcj02(p) }
            "wgs84", "wgs", "gps" -> { p -> wgs84ToGcj02(p) }
            else -> throw IllegalArgumentException("不支持的坐标系：$coord")
        }
        val rawVertices = LatLon.listFromJson(obj.optJSONArray("vertices"))
        val rawPoints = LatLon.listFromJson(obj.optJSONArray("points"))
        val vertices = normalize(rawVertices.map(converter))
        val points = normalize(rawPoints.map(converter))

        if (vertices.isEmpty() && points.isEmpty()) {
            throw IllegalArgumentException("路线文件缺少 points 或 vertices 坐标")
        }
        val resultVertices = if (vertices.size >= 3) vertices else points
        if (resultVertices.size < 3) {
            throw IllegalArgumentException("手绘路线至少需要 3 个点")
        }
        val ring: List<LatLon> = when {
            points.size >= 3 && haversineMeters(points.last(), points.first()) <= CLOSE_TOLERANCE_M -> closeRing(points)
            else -> RouteMath.smoothClosed(resultVertices)
        }
        return RoutePayload(
            vertices = resultVertices,
            ring = ring,
            lengthM = RouteMath.routeLengthM(ring),
        )
    }

    /** Remove adjacent duplicates and reject invalid coordinates. */
    fun normalize(points: List<LatLon>): List<LatLon> {
        val out = ArrayList<LatLon>(points.size)
        for (point in points) {
            if (!point.isValid()) throw IllegalArgumentException("路线坐标包含无效经纬度")
            if (out.isNotEmpty() && haversineMeters(out.last(), point) < 0.05) continue
            out.add(point)
        }
        return out
    }

    fun closeRing(points: List<LatLon>): List<LatLon> {
        if (points.size < 2) throw IllegalArgumentException("路线折线至少需要 2 个不同点")
        val out = ArrayList(points)
        if (out.last() != out.first()) {
            val gap = haversineMeters(out.last(), out.first())
            if (gap > CLOSE_TOLERANCE_M) {
                throw IllegalArgumentException(
                    "路线不闭合：首尾相距 " + String.format(java.util.Locale.US, "%.0f", gap) + " m"
                )
            }
            out.add(out.first())
        }
        return out
    }

    fun wgs84ToGcj02(point: LatLon): LatLon {
        if (outOfChina(point)) return point
        val a = 6378245.0
        val ee = 0.00669342162296594323
        val dLat = transformLat(point.lon - 105.0, point.lat - 35.0)
        val dLon = transformLon(point.lon - 105.0, point.lat - 35.0)
        val radLat = point.lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        val lat = point.lat + (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        val lon = point.lon + (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
        return LatLon(lat, lon)
    }

    fun gcj02ToBd09(point: LatLon): LatLon {
        val z = Math.hypot(point.lon, point.lat) + 0.00002 * Math.sin(point.lat * X_PI)
        val theta = Math.atan2(point.lat, point.lon) + 0.000003 * Math.cos(point.lon * X_PI)
        return LatLon(z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065)
    }

    fun bd09ToGcj02(point: LatLon): LatLon {
        var x = point.lon - 0.0065
        var y = point.lat - 0.006
        var z = Math.hypot(x, y) - 0.00002 * Math.sin(y * X_PI)
        var theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI)
        var gLat = z * Math.sin(theta)
        var gLon = z * Math.cos(theta)
        for (i in 0 until 6) {
            val check = gcj02ToBd09(LatLon(gLat, gLon))
            val dLat = check.lat - point.lat
            val dLon = check.lon - point.lon
            gLat -= dLat
            gLon -= dLon
            if (maxOf(Math.abs(dLat), Math.abs(dLon)) < 1e-11) break
        }
        return LatLon(gLat, gLon)
    }

    private fun outOfChina(point: LatLon): Boolean =
        !(72.004 <= point.lon && point.lon <= 137.8347 && 0.8293 <= point.lat && point.lat <= 55.8271)

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
