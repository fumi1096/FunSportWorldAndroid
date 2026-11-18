package com.funsportworld.android.route

import com.funsportworld.android.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteMath] 单元测试。
 *
 * 覆盖平滑/重采样/闭合、最少点与失败路径、打卡点距离与顺序校验、旋转/吸附/反向，
 * 以及载荷整理。其中带 `python` 前缀的基准数组由桌面端原实现实测得到
 * （`funsport/route_sketch.py`，Python 3.12），已写成常量，跑测试不需要 Python。
 *
 * 坐标容差取 1e-7：输出经纬度以 7 位小数（约 1.1 cm）为精度单位，
 * 因此只能承诺"一个量化步长"的绝对一致；实测差异为 0 或 1 个步长。
 * 长度容差取 1e-9：两边用同样的球面距离公式累加，差异只来自末位舍入。
 */
class RouteMathTest {

    private val coordTolerance = 1e-7
    private val lengthTolerance = 1e-9

    // ── 基准数据（Python 实测） ──────────────────────────────────────────

    private val hexVertices = listOf(
        LatLon(39.9100, 116.3970),
        LatLon(39.9090, 116.3985),
        LatLon(39.9075, 116.3980),
        LatLon(39.9068, 116.3965),
        LatLon(39.9080, 116.3952),
        LatLon(39.9095, 116.3958),
    )

    private val triangleVertices = listOf(
        LatLon(39.9080, 116.3970),
        LatLon(39.9085, 116.3980),
        LatLon(39.9075, 116.3985),
    )

    /** `smooth_closed(hexVertices, 20.0)`：51 点，长度 986.043083192254 m。 */
    private val pythonRingHex20 = listOf(
        LatLon(39.91, 116.397),
        LatLon(39.90995432691172, 116.39722302103812),
        LatLon(39.90987821323629, 116.39743188974093),
        LatLon(39.90978401126725, 116.3976279668359),
        LatLon(39.90967695303565, 116.39781248586307),
        LatLon(39.90955993734279, 116.39798644802183),
        LatLon(39.90943232365162, 116.3981472517571),
        LatLon(39.90929455437021, 116.39829298725886),
        LatLon(39.9091451894361, 116.39841762206781),
        LatLon(39.90898156792947, 116.39850588016974),
        LatLon(39.908806708987406, 116.39854281653854),
        LatLon(39.908629343488165, 116.39854133995942),
        LatLon(39.908453277932274, 116.39851235012914),
        LatLon(39.90827998419304, 116.3984624653019),
        LatLon(39.9081098403879, 116.39839663793788),
        LatLon(39.90794358961801, 116.39831556143226),
        LatLon(39.90778205733981, 116.3982196929686),
        LatLon(39.907626370198656, 116.3981086749728),
        LatLon(39.90747942243106, 116.39797907790555),
        LatLon(39.90734422767101, 116.3978292605947),
        LatLon(39.90721942695016, 116.39766477067863),
        LatLon(39.907104606808936, 116.39748833055303),
        LatLon(39.90700075473366, 116.39730068880468),
        LatLon(39.906910547534316, 116.3971014596734),
        LatLon(39.906838992216485, 116.39688987088485),
        LatLon(39.906798121681575, 116.39666524751192),
        LatLon(39.90681283366908, 116.39643644278169),
        LatLon(39.90688756607533, 116.3962275013305),
        LatLon(39.90699306890478, 116.39604171824666),
        LatLon(39.90711479699725, 116.39587347377396),
        LatLon(39.9072473802713, 116.39571966144291),
        LatLon(39.907389001274105, 116.39558020885588),
        LatLon(39.90753745355653, 116.3954533890416),
        LatLon(39.90769368210453, 116.39534361130092),
        LatLon(39.90785706857223, 116.39525360060675),
        LatLon(39.90802838963213, 116.39519466678159),
        LatLon(39.90820519309683, 116.39517859580772),
        LatLon(39.90838215315018, 116.39519528544623),
        LatLon(39.90855674382658, 116.39523684425157),
        LatLon(39.90872825529339, 116.39529628356863),
        LatLon(39.908896424095424, 116.39537033444344),
        LatLon(39.9090602024837, 116.3954595703649),
        LatLon(39.90921927848604, 116.39556216209492),
        LatLon(39.909372083248016, 116.39567981632594),
        LatLon(39.90951506202528, 116.39581675504361),
        LatLon(39.90964446110593, 116.39597502655877),
        LatLon(39.90975965816302, 116.39615096687156),
        LatLon(39.90985943877718, 116.39634222100261),
        LatLon(39.909939108264815, 116.39654878069773),
        LatLon(39.90999113934038, 116.39676962371982),
        LatLon(39.91, 116.397),
    )

    /** `smooth_closed(triangleVertices, 25.0)`：17 点，长度 371.04034471417657 m。 */
    private val pythonRingTriangle25 = listOf(
        LatLon(39.908, 116.397),
        LatLon(39.908174533023164, 116.39714397528877),
        LatLon(39.90830816060122, 116.39735702854952),
        LatLon(39.90841853598686, 116.39759174281977),
        LatLon(39.908496224277386, 116.39784712994086),
        LatLon(39.90844614044403, 116.39809725299278),
        LatLon(39.908271617338535, 116.3982511941596),
        LatLon(39.9080796302122, 116.39836605605291),
        LatLon(39.90787994131153, 116.39845602959767),
        LatLon(39.90767419431782, 116.39851791960538),
        LatLon(39.90748632766515, 116.39846154655822),
        LatLon(39.90751010719148, 116.39818973837158),
        LatLon(39.90757348040837, 116.39792699174446),
        LatLon(39.907651628994294, 116.39767103876336),
        LatLon(39.90774234258638, 116.39742223349035),
        LatLon(39.90784946076697, 116.39718493923178),
        LatLon(39.908, 116.397),
    )

    private val cpValidOrder = listOf(
        pythonRingHex20[5],
        pythonRingHex20[20],
        pythonRingHex20[30],
    )

    private val cpInvertedOrder = listOf(
        pythonRingHex20[30],
        pythonRingHex20[20],
        pythonRingHex20[5],
    )

    /** 对齐用例：起点就是第 20 个顶点，沿原方向顺序可跑。 */
    private val cpAlignForward = listOf(
        pythonRingHex20[20],
        pythonRingHex20[30],
        pythonRingHex20[5],
    )

    /** 对齐用例：起点仍是第 20 个顶点，但只有反向才能满足编号顺序。 */
    private val cpAlignReversed = listOf(
        pythonRingHex20[20],
        pythonRingHex20[5],
        pythonRingHex20[30],
    )

    // ── 平滑 / 重采样 / 闭合 ──────────────────────────────────────────────

    @Test
    fun smoothClosedClosesRingAndKeepsCurveLength() {
        val ring = RouteMath.smoothClosed(hexVertices, 20.0)

        assertEquals(pythonRingHex20.size, ring.size)
        assertTrue("平滑后点数应明显多于原始标点", ring.size > hexVertices.size)
        assertEquals("首尾必须严格相同", ring.first(), ring.last())
        assertEquals(986.043083192254, RouteMath.routeLengthM(ring), lengthTolerance)

        // 闭环上的点都落在标点包围盒附近（不能因为极角排序/投影错误而飞出去）。
        for (point in ring) {
            assertTrue("纬度越界: $point", point.lat in 39.9060..39.9110)
            assertTrue("经度越界: $point", point.lon in 116.3945..116.3990)
        }
    }

    @Test
    fun smoothClosedMatchesPythonRingPointwise() {
        val ring = RouteMath.smoothClosed(hexVertices, 20.0)
        assertEquals(pythonRingHex20.size, ring.size)
        for (index in pythonRingHex20.indices) {
            assertEquals("ring[$index].lat", pythonRingHex20[index].lat, ring[index].lat, coordTolerance)
            assertEquals("ring[$index].lon", pythonRingHex20[index].lon, ring[index].lon, coordTolerance)
        }
    }

    @Test
    fun smoothClosedMatchesPythonForMinimalTriangle() {
        val ring = RouteMath.smoothClosed(triangleVertices, 25.0)
        assertEquals(pythonRingTriangle25.size, ring.size)
        for (index in pythonRingTriangle25.indices) {
            assertEquals("tri[$index].lat", pythonRingTriangle25[index].lat, ring[index].lat, coordTolerance)
            assertEquals("tri[$index].lon", pythonRingTriangle25[index].lon, ring[index].lon, coordTolerance)
        }
        assertEquals(371.04034471417657, RouteMath.routeLengthM(ring), lengthTolerance)
    }

    @Test
    fun smoothClosedUsesDefaultSpacing() {
        val hexRing = RouteMath.smoothClosed(hexVertices)
        assertEquals(495, hexRing.size)
        assertEquals(986.9940798190715, RouteMath.routeLengthM(hexRing), lengthTolerance)
        assertEquals(hexRing.first(), hexRing.last())

        val triRing = RouteMath.smoothClosed(triangleVertices)
        assertEquals(190, triRing.size)
        assertEquals(375.80029531334685, RouteMath.routeLengthM(triRing), lengthTolerance)
        assertEquals(triRing.first(), triRing.last())
    }

    @Test
    fun smoothClosedPointCountFollowsSpacing() {
        // ceil(986.04 / 100) = 10 个采样点，再补闭合首点。
        val ring = RouteMath.smoothClosed(hexVertices, 100.0)
        assertEquals(11, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    // ── 失败路径 ─────────────────────────────────────────────────────────

    @Test
    fun smoothClosedRejectsFewerThanThreeVertices() {
        val twoPoints = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.smoothClosed(listOf(hexVertices[0], hexVertices[1]))
        }
        assertEquals("手绘路线至少需要 3 个点", twoPoints.message)

        // 三个重合点会被 normalize 去重成一个点，同样应报顶点不足。
        val duplicated = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.smoothClosed(listOf(hexVertices[0], hexVertices[0], hexVertices[0]))
        }
        assertEquals("手绘路线至少需要 3 个点", duplicated.message)
    }

    @Test
    fun smoothClosedRejectsRepeatedVertexAtClosure() {
        // 首尾相同会让向心 knot 为 0（normalize 只去相邻重复，不去环绕重复）。
        val error = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.smoothClosed(
                listOf(hexVertices[0], hexVertices[1], hexVertices[2], hexVertices[0])
            )
        }
        assertEquals("手绘路线包含重复点，无法平滑", error.message)
    }

    @Test
    fun smoothClosedRejectsTooShortRoute() {
        val tiny = listOf(
            LatLon(39.908000, 116.397000),
            LatLon(39.908005, 116.397010),
            LatLon(39.907995, 116.397005),
        )
        val error = assertThrows(IllegalArgumentException::class.java) { RouteMath.smoothClosed(tiny) }
        assertEquals("手绘路线太短（3 m），至少需要 30 m", error.message)
    }

    @Test
    fun normalizeRejectsInvalidCoordinatesAndDropsDuplicates() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.normalize(listOf(LatLon(Double.NaN, 116.397), hexVertices[1], hexVertices[2]))
        }
        assertEquals("路线坐标包含无效经纬度", error.message)

        // 单轴占位值（0/-1）与桌面端 coordinate_pair 一样要被拒绝。
        assertThrows(IllegalArgumentException::class.java) {
            RouteMath.normalize(listOf(LatLon(0.0, 116.397), hexVertices[1], hexVertices[2]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteMath.normalize(listOf(hexVertices[0], LatLon(39.9, -1.0), hexVertices[2]))
        }

        val normalized = RouteMath.normalize(listOf(hexVertices[0], hexVertices[0], hexVertices[1]))
        assertEquals(2, normalized.size)
        assertEquals(hexVertices[0], normalized[0])
        assertEquals(hexVertices[1], normalized[1])
    }

    @Test
    fun closeRingAppendsStartAndRejectsOpenRing() {
        val closed = RouteMath.closeRing(pythonRingHex20)
        assertEquals(pythonRingHex20.size, closed.size)
        assertEquals(closed.first(), closed.last())

        // 去掉闭合点后首尾相距约 20 m，超过 3 m 容差。
        val error = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.closeRing(pythonRingHex20.dropLast(1))
        }
        assertEquals("路线不闭合：首尾相距 20 m（上限 3 m）", error.message)

        assertThrows(IllegalArgumentException::class.java) {
            RouteMath.closeRing(listOf(hexVertices[0], hexVertices[1]))
        }
    }

    // ── analyzeRoute ─────────────────────────────────────────────────────

    @Test
    fun analyzeRouteAcceptsOrderedCheckpoints() {
        val analysis = RouteMath.analyzeRoute(pythonRingHex20, cpValidOrder, requireOrder = true)

        assertEquals(986.04, analysis.lengthM, 1e-9)
        assertEquals(0.0, analysis.closedGapM, 1e-9)
        assertEquals(pythonRingHex20.size, analysis.vertexCount)
        assertEquals(true, analysis.orderOk)
        assertEquals(listOf(1, 2, 3), analysis.routeOrder)
        assertTrue("有效顺序不应有问题: ${analysis.problems}", analysis.problems.isEmpty())

        // (nearest_m, arc_m, vertex_index) 与 Python 完全一致。
        val expected = listOf(
            Triple(0.0, 98.61, 5),
            Triple(0.0, 394.5, 20),
            Triple(0.0, 591.55, 30),
        )
        assertEquals(3, analysis.checkpoints.size)
        for (index in expected.indices) {
            val coverage = analysis.checkpoints[index]
            assertEquals(index, coverage.index)
            assertEquals(expected[index].first, coverage.nearestM, 1e-9)
            assertEquals(expected[index].second, coverage.arcM, 1e-9)
            assertEquals(expected[index].third, coverage.vertexIndex)
        }
    }

    @Test
    fun analyzeRouteDetectsInvertedCheckpointOrder() {
        val analysis = RouteMath.analyzeRoute(pythonRingHex20, cpInvertedOrder, requireOrder = true)

        assertEquals(false, analysis.orderOk)
        assertEquals(listOf(3, 2, 1), analysis.routeOrder)
        assertEquals(1, analysis.problems.size)
        assertEquals(
            "打卡点沿路线顺序与表单顺序不一致（路线经过顺序 3→2→1；表单顺序 1→2→3）。" +
                "可尝试反向画线或调整标点；随机点模式不受顺序限制",
            analysis.problems[0],
        )
        // 顺序反了但每个点都在路上，不该报距离问题。
        assertTrue(analysis.checkpoints.all { it.nearestM == 0.0 })
        assertEquals(listOf(30, 20, 5), analysis.checkpoints.map { it.vertexIndex })
    }

    @Test
    fun analyzeRouteSkipsOrderCheckWhenOrderNotRequired() {
        val analysis = RouteMath.analyzeRoute(
            pythonRingHex20,
            cpInvertedOrder,
            requireOrder = false,
        )
        assertNull(analysis.orderOk)
        assertEquals(listOf(3, 2, 1), analysis.routeOrder)
        assertTrue(analysis.problems.isEmpty())
    }

    @Test
    fun analyzeRouteWithoutCheckpoints() {
        val analysis = RouteMath.analyzeRoute(pythonRingHex20, null, requireOrder = true)
        assertNull(analysis.orderOk)
        assertNull(analysis.routeOrder)
        assertTrue(analysis.checkpoints.isEmpty())
        assertTrue(analysis.problems.isEmpty())
        assertEquals(986.04, analysis.lengthM, 1e-9)
    }

    @Test
    fun analyzeRouteFlagsUnclosedRing() {
        val openRing = pythonRingHex20.dropLast(1)
        val analysis = RouteMath.analyzeRoute(openRing, null, requireOrder = true)

        assertEquals(50, analysis.vertexCount)
        assertEquals(966.37, analysis.lengthM, 1e-9)
        assertEquals(19.67, analysis.closedGapM, 1e-9)
        assertEquals(listOf("路线首尾相距 20 m，未闭合"), analysis.problems)
    }

    @Test
    fun analyzeRouteFlagsShortRing() {
        // 边长 5 m 的闭合方形，周长约 19.85 m < 30 m。
        val dLat = 5.0 / LatLon.MET_PER_DEG_LAT
        val dLon = 5.0 / LatLon.MET_PER_DEG_LNG
        val square = listOf(
            LatLon(39.908, 116.397),
            LatLon(39.908, 116.397 + dLon),
            LatLon(39.908 + dLat, 116.397 + dLon),
            LatLon(39.908 + dLat, 116.397),
            LatLon(39.908, 116.397),
        )
        val analysis = RouteMath.analyzeRoute(square, null, requireOrder = true)

        assertEquals(5, analysis.vertexCount)
        assertEquals(19.85, analysis.lengthM, 1e-9)
        assertEquals(listOf("路线长度 20 m 小于 30 m"), analysis.problems)
    }

    @Test
    fun analyzeRouteFlagsCheckpointTooFarFromRoute() {
        val farCheckpoints = listOf(
            LatLon(39.95, 116.45),
            pythonRingHex20[5],
            pythonRingHex20[10],
        )
        val analysis = RouteMath.analyzeRoute(pythonRingHex20, farCheckpoints, requireOrder = true)

        assertEquals(3, analysis.checkpoints.size)
        val far = analysis.checkpoints[0]
        assertEquals(6315.9, far.nearestM, 1e-9)
        assertEquals(98.61, far.arcM, 1e-9)
        assertEquals(5, far.vertexIndex)
        assertEquals(0.0, analysis.checkpoints[1].nearestM, 1e-9)
        assertEquals(98.61, analysis.checkpoints[1].arcM, 1e-9)
        assertEquals(5, analysis.checkpoints[1].vertexIndex)
        assertEquals(197.18, analysis.checkpoints[2].arcM, 1e-9)
        assertEquals(10, analysis.checkpoints[2].vertexIndex)

        assertTrue(analysis.problems.contains("第 1 个打卡点距路线 6315.9 m（需 ≤ 25 m）"))
        // 第 1 个点远离路线、与第 2 个点落到同一顶点，顺序也谈不上递增。
        assertEquals(false, analysis.orderOk)
    }

    @Test
    fun analyzeRouteRejectsTooFewVertices() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.analyzeRoute(listOf(hexVertices[0]), null, requireOrder = true)
        }
        assertEquals("路线折线至少需要 2 个不同点", error.message)
    }

    // ── alignRoute ───────────────────────────────────────────────────────

    @Test
    fun alignRouteRotatesRingToFirstCheckpoint() {
        val aligned = RouteMath.alignRoute(pythonRingHex20, cpAlignForward, requireOrder = true)

        assertTrue("起点下标不为 0，应发生旋转", aligned.rotated)
        assertFalse(aligned.reversed)
        assertTrue("首个打卡点就是顶点，应吸附", aligned.snapped)
        assertEquals(true, aligned.analysis.orderOk)

        assertEquals(pythonRingHex20.size, aligned.ring.size)
        assertEquals("起点应落在第一个打卡点", pythonRingHex20[20], aligned.ring[0])
        assertEquals("旋转后应保持原先后继方向", pythonRingHex20[21], aligned.ring[1])
        assertEquals(aligned.ring.first(), aligned.ring.last())
        // 旋转不改变路线本身，周长不变。
        assertEquals(986.04, aligned.analysis.lengthM, 1e-9)
    }

    @Test
    fun alignRouteSnapsStartToNearbyCheckpoint() {
        val offset = LatLon(
            pythonRingHex20[20].lat + 0.00005,
            pythonRingHex20[20].lon + 0.00005,
        )
        val checkpoints = listOf(offset, pythonRingHex20[30], pythonRingHex20[5])
        val aligned = RouteMath.alignRoute(pythonRingHex20, checkpoints, requireOrder = true)

        assertTrue(aligned.rotated)
        assertTrue("8 m 内应吸附到打卡点", aligned.snapped)
        assertEquals("吸附后起点就是打卡点本身", offset, aligned.ring[0])
        assertEquals(aligned.ring.first(), aligned.ring.last())
        assertEquals(986.03, aligned.analysis.lengthM, 1e-9)
        assertEquals(true, aligned.analysis.orderOk)
    }

    @Test
    fun alignRouteReversesWhenOrderInverted() {
        val aligned = RouteMath.alignRoute(pythonRingHex20, cpAlignReversed, requireOrder = true)

        assertTrue(aligned.rotated)
        assertTrue("顺序不满足时应保留首点、反向其余点", aligned.reversed)
        assertTrue(aligned.snapped)
        assertEquals(true, aligned.analysis.orderOk)

        assertEquals(pythonRingHex20[20], aligned.ring[0])
        assertEquals("反向时第二个点应取旋转前的上一个顶点", pythonRingHex20[19], aligned.ring[1])
        assertEquals(aligned.ring.first(), aligned.ring.last())
        assertTrue(aligned.analysis.problems.isEmpty())
    }

    @Test
    fun alignRouteDoesNotReverseWhenOrderNotRequired() {
        val aligned = RouteMath.alignRoute(
            pythonRingHex20,
            cpAlignReversed,
            requireOrder = false,
        )
        assertTrue(aligned.rotated)
        assertFalse(aligned.reversed)
        assertNull(aligned.analysis.orderOk)
        assertEquals(pythonRingHex20[20], aligned.ring[0])
    }

    @Test
    fun alignRouteRequiresCheckpoints() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RouteMath.alignRoute(pythonRingHex20, emptyList(), requireOrder = true)
        }
        assertEquals("路线对齐至少需要 1 个打卡点", error.message)
    }

    // ── preparePayload ───────────────────────────────────────────────────

    @Test
    fun preparePayloadMatchesPythonAlignedRoute() {
        val payload = RouteMath.preparePayload(
            hexVertices,
            listOf(pythonRingHex20[20], pythonRingHex20[30], pythonRingHex20[5]),
            requireOrder = true,
        )

        assertEquals(6, payload.vertices.size)
        assertEquals(495, payload.ring.size)
        assertEquals(payload.ring.first(), payload.ring.last())

        // 起点被吸附到第一个打卡点，并在输出时量化到 7 位小数。
        assertEquals(39.9072194, payload.ring[0].lat, coordTolerance)
        assertEquals(116.3976648, payload.ring[0].lon, coordTolerance)

        // Python 的 length_m = round(route_length_m(points), 2) = 986.99，
        // 这里存真实折线长度，序列化时 RoutePayload.toJson 按 2 位小数输出。
        assertEquals(986.9940798190715, payload.lengthM, lengthTolerance)

        // 所有输出坐标都必须落在 7 位小数的量化格上。
        for (point in payload.ring) {
            assertEquals("lat 未量化: ${point.lat}", LatLon.round7(point.lat), point.lat, 0.0)
            assertEquals("lon 未量化: ${point.lon}", LatLon.round7(point.lon), point.lon, 0.0)
        }
        for (point in payload.vertices) {
            assertEquals(LatLon.round7(point.lat), point.lat, 0.0)
            assertEquals(LatLon.round7(point.lon), point.lon, 0.0)
        }
        // 顶点是 normalize 后的原标点，只做小数量化。
        assertEquals(hexVertices, payload.vertices)
    }

    @Test
    fun preparePayloadWithoutCheckpointsKeepsSmoothedRing() {
        val payload = RouteMath.preparePayload(hexVertices, null, requireOrder = true)

        assertEquals(6, payload.vertices.size)
        assertEquals(495, payload.ring.size)
        assertEquals(payload.ring.first(), payload.ring.last())
        assertEquals(986.9940798190715, payload.lengthM, lengthTolerance)
        // 平滑后的每个点本身已是 7 位小数，量化不改变形状：首点与 Python 环首点一致。
        assertEquals(pythonRingHex20[0].lat, payload.ring[0].lat, coordTolerance)
        assertEquals(pythonRingHex20[0].lon, payload.ring[0].lon, coordTolerance)
        for (point in payload.ring) {
            assertEquals(LatLon.round7(point.lat), point.lat, 0.0)
            assertEquals(LatLon.round7(point.lon), point.lon, 0.0)
        }
    }

    @Test
    fun preparePayloadWithReversedCheckpointsStillCloses() {
        val payload = RouteMath.preparePayload(
            hexVertices,
            listOf(pythonRingHex20[20], pythonRingHex20[5], pythonRingHex20[30]),
            requireOrder = true,
        )
        assertEquals(payload.ring.first(), payload.ring.last())
        assertEquals(495, payload.ring.size)
        assertEquals(986.9940798190715, payload.lengthM, lengthTolerance)
        assertEquals(39.9072194, payload.ring[0].lat, coordTolerance)
        assertEquals(116.3976648, payload.ring[0].lon, coordTolerance)
    }
}
