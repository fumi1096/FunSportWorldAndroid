package com.funsportworld.android.route

import com.funsportworld.android.data.LatLon
import com.funsportworld.android.data.RoutePayload
import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一个打卡点相对闭合路线折线的覆盖情况。
 *
 * @property index 打卡点在调用方序列中的下标（0 基）。
 * @property nearestM 到最近折线顶点的距离（米，保留 2 位小数）。
 * @property arcM 该顶点沿路线的弧长位置（米，保留 2 位小数）。
 * @property vertexIndex 该顶点在路线环中的下标。
 */
data class CheckpointCoverage(
    val index: Int,
    val nearestM: Double,
    val arcM: Double,
    val vertexIndex: Int,
)

/**
 * 闭合路线的校验结论。
 *
 * @property lengthM 折线周长（米，保留 2 位小数）。
 * @property closedGapM 首尾相距（米，保留 2 位小数）。
 * @property checkpoints 每个打卡点的最近距离/弧长位置；没有打卡点时为 空。
 * @property orderOk 是否需要顺序打卡的结论：`null` 表示没有打卡点或未要求顺序。
 * @property routeOrder 按沿路线先后排出的 1 基打卡点编号；没有打卡点时为 `null`。
 * @property problems 面向用户的中文问题列表，与桌面端提示文案一致。
 * @property vertexCount 路线环的顶点数（含重复的首点）。
 */
data class RouteAnalysis(
    val lengthM: Double,
    val closedGapM: Double,
    val checkpoints: List<CheckpointCoverage>,
    val orderOk: Boolean?,
    val routeOrder: List<Int>?,
    val problems: List<String>,
    val vertexCount: Int,
)

/**
 * 已按第一个打卡点旋转/吸附（必要时反向）的闭环。
 *
 * @property ring 首尾严格相同的闭环坐标。
 * @property analysis 对齐之后重新计算的分析结论。
 * @property rotated 是否发生过旋转（起点下标不为 0）。
 * @property reversed 是否为了顺序打卡而保留了首点、反向了其余点。
 * @property snapped 首顶点是否被吸附到第一个打卡点（相距 <= 通过半径）。
 */
data class AlignedRoute(
    val ring: List<LatLon>,
    val analysis: RouteAnalysis,
    val rotated: Boolean,
    val reversed: Boolean,
    val snapped: Boolean,
)

/**
 * 手绘路线几何：向心 Catmull-Rom 平滑、弧长重采样、闭合、旋转/反向/吸附，
 * 以及打卡点距离与顺序校验。
 *
 * 本对象逐行对齐桌面端 `funsport/route_sketch.py`（配合 `funsport/coordinates.py`
 * 的 `distance_m` 与 `funsport/track/geom.py` 的米制常量），因此同一顶点序列会得到
 * 同一条闭环；默认参数下与 Python 结果的差异只来自最后 7 位小数的量化。
 *
 * 坐标约定：GCJ-02 `(纬度, 经度)`，与桌面端一致。
 */
object RouteMath {

    /** 每段 Catmull-Rom 的采样点数量。 */
    const val SMOOTH_SAMPLES = 16

    /** 重采样默认间距（米）。 */
    const val DEFAULT_SPACING_M = 2.0

    /** 重采样点数量上限（决定 `total / MAX_POINTS` 这个间距下限）。 */
    const val MAX_POINTS = 20000

    /** 手绘路线最少顶点数。 */
    const val MIN_VERTICES = 3

    /** 路线最短长度（米）。 */
    const val MIN_LENGTH_M = 30.0

    /** 闭合容差（米）：首尾相距不超过该值即视为闭合。 */
    const val CLOSE_TOLERANCE_M = 3.0

    /** 打卡点通过半径（米）。 */
    const val PASS_RADIUS_M = 25.0

    private const val EARTH_RADIUS_M = 6371008.8
    private const val MET_PER_DEG_LAT = LatLon.MET_PER_DEG_LAT
    private const val MET_PER_DEG_LNG = LatLon.MET_PER_DEG_LNG
    private const val DUPLICATE_TOLERANCE_M = 0.05
    private const val ROUND_DECIMALS = 7

    // ── 对外 API ──────────────────────────────────────────────────────────

    /**
     * 校验并复制坐标序列，去掉相邻重复点（相距 < 0.05 m），拒绝不可用坐标。
     *
     * 校验规则与 Python `coordinates.coordinate_pair` 一致：必须是有限值、在经纬度
     * 范围内，且不是 App 常见的单轴占位值（0 或 -1），这些值与 [LatLon.isValid] 的
     * 约定相同。
     *
     * @throws IllegalArgumentException 存在无效经纬度时（中文消息与桌面端一致）。
     */
    fun normalize(points: List<LatLon>): List<LatLon> {
        val out = ArrayList<LatLon>(points.size)
        for (point in points) {
            if (!isValidCoordinate(point)) throw IllegalArgumentException("路线坐标包含无效经纬度")
            if (out.isNotEmpty() && distanceM(out[out.size - 1], point) < DUPLICATE_TOLERANCE_M) continue
            out.add(point)
        }
        return out
    }

    /**
     * 把标点序列变成首尾闭合、约每 [maxSpacingM] 一点的有序折线。
     *
     * 步骤与桌面端一致：向心 Catmull-Rom 插值（每段 [SMOOTH_SAMPLES] 个采样点）→
     * 按总弧长重采样（间距 `max(maxSpacingM, total / MAX_POINTS)`，点数
     * `max(3, ceil(total / spacing))`）→ 首尾闭合。
     *
     * 返回的闭环坐标不做 7 位小数取整（与 Python 相同：平滑后的重采样点保留全精度，
     * 只有 Catmull-Rom 控制点本身在还原经纬度时取整）；需要写盘时由
     * [preparePayload] 统一取整。
     *
     * @throws IllegalArgumentException 顶点少于 3 个、包含重复点、长度为 0 或短于
     *   [MIN_LENGTH_M] 米时。
     */
    fun smoothClosed(vertices: List<LatLon>, maxSpacingM: Double = DEFAULT_SPACING_M): List<LatLon> {
        val points = normalize(vertices)
        if (points.size < MIN_VERTICES) throw IllegalArgumentException("手绘路线至少需要 3 个点")
        val dense = catmullRomClosed(points, SMOOTH_SAMPLES)
        val arcs = DoubleArray(dense.size)
        for (index in 1 until dense.size) {
            arcs[index] = arcs[index - 1] + distanceM(dense[index - 1], dense[index])
        }
        // 补上闭合的一段，保证重采样覆盖整圈。
        val closing = distanceM(dense[dense.size - 1], dense[0])
        val total = arcs[dense.size - 1] + closing
        if (total <= 0.0) throw IllegalArgumentException("手绘路线长度为零")
        if (total < MIN_LENGTH_M) {
            throw IllegalArgumentException(
                "手绘路线太短（" + formatFixed(total, 0) + " m），至少需要 " +
                    formatFixed(MIN_LENGTH_M, 0) + " m"
            )
        }
        val spacing = max(maxSpacingM, total / MAX_POINTS)
        val count = max(3, ceil(total / spacing).toInt())
        val ring = ArrayList<LatLon>(count + 1)
        for (index in 0 until count) {
            ring.add(pointAt(dense, arcs, closing, total * index / count))
        }
        ring.add(ring[0])
        return ring
    }

    /**
     * 返回首尾严格相同的闭合折线副本。
     *
     * @throws IllegalArgumentException 少于 2 个不同点，或首尾相距超过
     *   [CLOSE_TOLERANCE_M] 米时。
     */
    fun closeRing(ring: List<LatLon>): List<LatLon> {
        val points = normalize(ring)
        if (points.size < 2) throw IllegalArgumentException("路线折线至少需要 2 个不同点")
        val out = ArrayList<LatLon>(points.size + 1)
        out.addAll(points)
        if (out[out.size - 1] != out[0]) {
            val gap = distanceM(out[out.size - 1], out[0])
            if (gap > CLOSE_TOLERANCE_M) {
                throw IllegalArgumentException(
                    "路线不闭合：首尾相距 " + formatFixed(gap, 0) + " m（上限 " +
                        formatFixed(CLOSE_TOLERANCE_M, 0) + " m）"
                )
            }
            out.add(out[0])
        }
        return out
    }

    /** 计算折线的折线长度（米），逐段累加相邻点距离。 */
    fun routeLengthM(ring: List<LatLon>): Double {
        var total = 0.0
        for (index in 1 until ring.size) {
            total += distanceM(ring[index - 1], ring[index])
        }
        return total
    }

    /**
     * 校验闭合路线，并给出每个打卡点的最近距离与弧长位置。
     *
     * `requireOrder` 只在顺序打卡模式下为真；随机点模式不要求按编号顺序经过。
     * 最近顶点只在路线环的前 `n - 1` 个顶点中查找（与桌面端一致，避免重复的首点
     * 抢走末尾点的命中）。
     *
     * @throws IllegalArgumentException 路线少于 2 个不同点时。
     */
    fun analyzeRoute(
        ring: List<LatLon>,
        checkpoints: List<LatLon>?,
        passRadiusM: Double = PASS_RADIUS_M,
        requireOrder: Boolean,
    ): RouteAnalysis {
        if (ring.size < 2) throw IllegalArgumentException("路线折线至少需要 2 个不同点")
        val problems = ArrayList<String>()

        val gap = distanceM(ring[ring.size - 1], ring[0])
        if (gap > CLOSE_TOLERANCE_M) {
            problems.add("路线首尾相距 " + formatFixed(gap, 0) + " m，未闭合")
        }
        val length = routeLengthM(ring)
        if (length < MIN_LENGTH_M) {
            problems.add(
                "路线长度 " + formatFixed(length, 0) + " m 小于 " +
                    formatFixed(MIN_LENGTH_M, 0) + " m"
            )
        }

        val coverage = ArrayList<CheckpointCoverage>()
        var orderOk: Boolean? = null
        var routeOrder: List<Int>? = null
        if (!checkpoints.isNullOrEmpty()) {
            val arcs = arcsFor(ring)
            val positions = DoubleArray(checkpoints.size)
            for (index in checkpoints.indices) {
                val nearest = nearestVertex(ring, checkpoints[index], arcs)
                coverage.add(
                    CheckpointCoverage(
                        index = index,
                        nearestM = roundTo(nearest.distanceM, 2),
                        arcM = roundTo(nearest.arcM, 2),
                        vertexIndex = nearest.vertexIndex,
                    )
                )
                positions[index] = nearest.arcM
            }
            // 按弧长排序得到路线经过顺序；sortWith 稳定，弧长相同保持表单先后。
            val sortedIndices = (checkpoints.indices).sortedWith(compareBy { positions[it] })
            val order = sortedIndices.map { it + 1 }
            routeOrder = order
            for (item in coverage) {
                if (item.nearestM > passRadiusM) {
                    problems.add(
                        "第 " + (item.index + 1) + " 个打卡点距路线 " +
                            formatFixed(item.nearestM, 1) + " m（需 ≤ " +
                            formatFixed(passRadiusM, 0) + " m）"
                    )
                }
            }
            if (requireOrder) {
                var increasing = true
                for (index in 1 until checkpoints.size) {
                    if (!(positions[index] > positions[index - 1])) {
                        increasing = false
                        break
                    }
                }
                orderOk = increasing
                if (!increasing) {
                    val formOrder = (1..checkpoints.size).joinToString("→")
                    problems.add(
                        "打卡点沿路线顺序与表单顺序不一致（路线经过顺序 " +
                            order.joinToString("→") + "；表单顺序 " + formOrder +
                            "）。可尝试反向画线或调整标点；随机点模式不受顺序限制"
                    )
                }
            }
        }

        return RouteAnalysis(
            lengthM = roundTo(length, 2),
            closedGapM = roundTo(gap, 2),
            checkpoints = coverage,
            orderOk = orderOk,
            routeOrder = routeOrder,
            problems = problems,
            vertexCount = ring.size,
        )
    }

    /**
     * 旋转/必要时反向闭合路线，使起点落在第一个打卡点且顺序可跑。
     *
     * `requireOrder` 为假时只旋转并吸附起点，不检查、也不为了顺序反向。
     *
     * @throws IllegalArgumentException 没有打卡点，或路线无法闭合时。
     */
    fun alignRoute(
        ring: List<LatLon>,
        checkpoints: List<LatLon>,
        passRadiusM: Double = PASS_RADIUS_M,
        requireOrder: Boolean,
    ): AlignedRoute {
        if (checkpoints.isEmpty()) throw IllegalArgumentException("路线对齐至少需要 1 个打卡点")
        val points = closeRing(ring)
        // 去掉重复的首点后作为可旋转的基准序列。
        var base = ArrayList(points.subList(0, points.size - 1))
        val arcs = arcsFor(points)
        val checkpoint0 = checkpoints[0]
        val startIndex = nearestVertex(points, checkpoint0, arcs).vertexIndex
        val rotated = startIndex % base.size
        if (rotated != 0) {
            val rotatedBase = ArrayList<LatLon>(base.size)
            for (index in rotated until base.size) rotatedBase.add(base[index])
            for (index in 0 until rotated) rotatedBase.add(base[index])
            base = rotatedBase
        }
        var snapped = false
        if (distanceM(base[0], checkpoint0) <= passRadiusM) {
            base[0] = checkpoint0
            snapped = true
        }
        var analysis = analyzeRoute(base + base[0], checkpoints, passRadiusM, requireOrder = requireOrder)
        var reversedFlag = false
        if (requireOrder && analysis.orderOk != true && base.size > 2) {
            // 保留首点、反向其余点：相当于沿相反方向跑同一条闭环。
            val candidate = ArrayList<LatLon>(base.size)
            candidate.add(base[0])
            for (index in base.size - 1 downTo 1) candidate.add(base[index])
            val alt = analyzeRoute(candidate + candidate[0], checkpoints, passRadiusM, requireOrder = true)
            if (alt.orderOk == true) {
                analysis = alt
                reversedFlag = true
                base = candidate
            }
            // 两个方向都不满足顺序时保留原方向，让 problems 暴露原因。
        }
        return AlignedRoute(
            ring = base + base[0],
            analysis = analysis,
            rotated = rotated != 0,
            reversed = reversedFlag,
            snapped = snapped,
        )
    }

    /**
     * 把地图标点整理成可保存/传给后端的路线载荷。
     *
     * 平滑 →（有打卡点时）对齐；`vertices` 用校验后的原顶点，`ring` 用对齐后的
     * 闭环坐标，`length_m` 取闭环的真实折线长度（与桌面端相同；写盘时
     * [RoutePayload.toJson] 会按 2 位小数输出）。输出的所有坐标都保留 7 位小数，
     * 与桌面端的 `round(..., 7)` 一致。
     *
     * `requireOrder` 为假（随机点模式或模式未知）时只把起点转到打卡点附近，不为
     * 满足顺序而反向。
     */
    fun preparePayload(
        vertices: List<LatLon>,
        checkpoints: List<LatLon>?,
        requireOrder: Boolean,
        maxSpacingM: Double = DEFAULT_SPACING_M,
    ): RoutePayload {
        var ring = smoothClosed(vertices, maxSpacingM)
        if (!checkpoints.isNullOrEmpty()) {
            ring = alignRoute(ring, checkpoints, requireOrder = requireOrder).ring
        }
        val length = routeLengthM(ring)
        val outRing = ring.map { rounded(it) }
        val outVertices = normalize(vertices).map { rounded(it) }
        return RoutePayload(vertices = outVertices, ring = outRing, lengthM = length)
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    /** 最近折线顶点的三个观测量。 */
    private data class NearestVertex(val distanceM: Double, val arcM: Double, val vertexIndex: Int)

    /**
     * 对闭合顶点序列做向心 Catmull-Rom 插值（以顶点重心为原点的米制平面）。
     *
     * 向心参数化用相邻点距离的平方根作为 knot，避免尖点与自交；与桌面端
     * `_catmull_rom_closed` 完全一致（包括重复点导致的零 knot 检查）。
     */
    private fun catmullRomClosed(vertices: List<LatLon>, samples: Int): List<LatLon> {
        val n = vertices.size
        if (n < MIN_VERTICES) throw IllegalArgumentException("手绘路线至少需要 3 个点")
        var sumLat = 0.0
        var sumLon = 0.0
        for (vertex in vertices) {
            sumLat += vertex.lat
            sumLon += vertex.lon
        }
        val centerLat = sumLat / n
        val centerLon = sumLon / n
        // 以重心为原点投影到米制平面，角度/比例不随纬度变形。
        val planeY = DoubleArray(n) { (vertices[it].lat - centerLat) * MET_PER_DEG_LAT }
        val planeX = DoubleArray(n) { (vertices[it].lon - centerLon) * MET_PER_DEG_LNG }
        val dense = ArrayList<LatLon>(n * samples)
        for (index in 0 until n) {
            val i0 = (index - 1 + n) % n
            val i2 = (index + 1) % n
            val i3 = (index + 2) % n
            val p0y = planeY[i0]
            val p0x = planeX[i0]
            val p1y = planeY[index]
            val p1x = planeX[index]
            val p2y = planeY[i2]
            val p2x = planeX[i2]
            val p3y = planeY[i3]
            val p3x = planeX[i3]
            val t0 = 0.0
            val t1 = t0 + knot(p0y, p0x, p1y, p1x)
            val t2 = t1 + knot(p1y, p1x, p2y, p2x)
            val t3 = t2 + knot(p2y, p2x, p3y, p3x)
            if (t1 == t0 || t2 == t1 || t3 == t2) {
                throw IllegalArgumentException("手绘路线包含重复点，无法平滑")
            }
            for (step in 0 until samples) {
                val t = t1 + (t2 - t1) * (step.toDouble() / samples)
                val a1y = (t1 - t) * p0y / (t1 - t0) + (t - t0) * p1y / (t1 - t0)
                val a1x = (t1 - t) * p0x / (t1 - t0) + (t - t0) * p1x / (t1 - t0)
                val a2y = (t2 - t) * p1y / (t2 - t1) + (t - t1) * p2y / (t2 - t1)
                val a2x = (t2 - t) * p1x / (t2 - t1) + (t - t1) * p2x / (t2 - t1)
                val a3y = (t3 - t) * p2y / (t3 - t2) + (t - t2) * p3y / (t3 - t2)
                val a3x = (t3 - t) * p2x / (t3 - t2) + (t - t2) * p3x / (t3 - t2)
                val b1y = (t2 - t) * a1y / (t2 - t0) + (t - t0) * a2y / (t2 - t0)
                val b1x = (t2 - t) * a1x / (t2 - t0) + (t - t0) * a2x / (t2 - t0)
                val b2y = (t3 - t) * a2y / (t3 - t1) + (t - t1) * a3y / (t3 - t1)
                val b2x = (t3 - t) * a2x / (t3 - t1) + (t - t1) * a3x / (t3 - t1)
                val y = (t2 - t) * b1y / (t2 - t1) + (t - t1) * b2y / (t2 - t1)
                val x = (t2 - t) * b1x / (t2 - t1) + (t - t1) * b2x / (t2 - t1)
                dense.add(
                    LatLon(
                        round7(centerLat + y / MET_PER_DEG_LAT),
                        round7(centerLon + x / MET_PER_DEG_LNG),
                    )
                )
            }
        }
        return dense
    }

    /** 向心参数化：相邻点距离的平方根。 */
    private fun knot(y0: Double, x0: Double, y1: Double, x1: Double): Double =
        sqrt(hypot(y1 - y0, x1 - x0))

    /**
     * 在闭合折线上按弧长取点；`s = total` 时回到起点。
     *
     * 与桌面端 `_point_at` 一致：先在 `arcs` 上二分，再在相邻两点间线性插值；
     * 超过最后一段时退回起点闭合段。
     */
    private fun pointAt(dense: List<LatLon>, arcs: DoubleArray, closing: Double, s: Double): LatLon {
        val total = arcs[arcs.size - 1] + closing
        if (s <= 0.0) return dense[0]
        if (s >= total) return dense[0]
        if (s >= arcs[arcs.size - 1]) {
            val t = if (closing > 0.0) (s - arcs[arcs.size - 1]) / closing else 0.0
            val a = dense[dense.size - 1]
            val b = dense[0]
            return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
        var lo = 0
        var hi = arcs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (arcs[mid] <= s) lo = mid else hi = mid - 1
        }
        val i = min(lo, dense.size - 2)
        val segment = arcs[i + 1] - arcs[i]
        val t = if (segment > 0.0) (s - arcs[i]) / segment else 0.0
        val a = dense[i]
        val b = dense[i + 1]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** 每个顶点的弧长位置表；长度与路线环相同。 */
    private fun arcsFor(ring: List<LatLon>): DoubleArray {
        val arcs = DoubleArray(ring.size)
        for (index in 1 until ring.size) {
            arcs[index] = arcs[index - 1] + distanceM(ring[index - 1], ring[index])
        }
        return arcs
    }

    /** 在路线环的前 `n - 1` 个顶点中找离 [point] 最近的顶点。 */
    private fun nearestVertex(ring: List<LatLon>, point: LatLon, arcs: DoubleArray): NearestVertex {
        var best = NearestVertex(Double.POSITIVE_INFINITY, 0.0, 0)
        for (index in 0 until ring.size - 1) {
            val current = distanceM(ring[index], point)
            if (current < best.distanceM) best = NearestVertex(current, arcs[index], index)
        }
        return best
    }

    /** 与 [LatLon.isValid] 同义的占位值校验（桌面端 `coordinate_pair` 的规则）。 */
    private fun isValidCoordinate(point: LatLon): Boolean {
        val lat = point.lat
        val lon = point.lon
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
        if (lat == 0.0 || lat == -1.0 || lon == 0.0 || lon == -1.0) return false
        return true
    }

    /**
     * 球面距离（米）：haversine + `asin`，半径 6371008.8 m。
     *
     * 与桌面端 `coordinates.distance_m` 同式，保证折线长度与重采样位置逐点一致；
     * 数据层的 `haversineMeters` 用 `atan2` 形式，数学等价、只差末位 ulp。
     */
    private fun distanceM(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)
        val sinLat = sin((lat2 - lat1) / 2.0)
        val sinLon = sin((lon2 - lon1) / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return EARTH_RADIUS_M * 2.0 * asin(sqrt(max(0.0, min(1.0, h))))
    }

    /** 与 Python `round(value, 7)` 一致的 7 位小数量化。 */
    private fun round7(value: Double): Double = LatLon.round7(value)

    /** 输出用坐标：经纬度各自保留 7 位小数。 */
    private fun rounded(point: LatLon): LatLon = LatLon(roundTo(point.lat, ROUND_DECIMALS), roundTo(point.lon, ROUND_DECIMALS))

    /** 十进制四舍五入（`%.Nf`），与 Python `round` 一致到 7 位小数。 */
    private fun roundTo(value: Double, decimals: Int): Double =
        String.format(Locale.US, "%.${decimals}f", value).toDouble()

    /** 中文提示里用的定点数字（对应 Python 的 `{:.Nf}`）。 */
    private fun formatFixed(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)
}
