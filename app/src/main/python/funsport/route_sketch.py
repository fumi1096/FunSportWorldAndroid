"""手绘路线：地图标点 → Catmull-Rom 平滑闭合折线 → 校验与导入导出。

坐标约定：本模块对外使用 GCJ-02 ``(纬度, 经度)``，与 GUI 高德瓦片一致；
生成器需要 BD-09，由 ``funsport.api.flow`` 在调用前转换。
"""
import hashlib
import json
import math
from pathlib import Path

from .coordinates import bd09_to_gcj02, coordinate_pair, distance_m
from .track.geom import MET_PER_DEG_LAT, MET_PER_DEG_LNG

SCHEMA = 1
MIN_VERTICES = 3
DEFAULT_SPACING_M = 2.0
MAX_POINTS = 20000
CLOSE_TOLERANCE_M = 3.0
PASS_RADIUS_M = 25.0
MIN_LENGTH_M = 30.0
SMOOTH_SAMPLES = 16

_COORD_ALIASES = {"gcj02": "gcj02", "gcj": "gcj02", "gcj-02": "gcj02",
                  "bd09": "bd09", "bd": "bd09", "bd-09": "bd09",
                  "wgs84": "wgs84", "wgs": "wgs84"}


def _plane(points, center):
    """把经纬度批量换算成以 center 为原点的米制平面坐标。"""
    cy, cx = center
    return [((lat - cy) * MET_PER_DEG_LAT, (lon - cx) * MET_PER_DEG_LNG)
            for lat, lon in points]


def _unplane(points, center):
    """把米制平面坐标还原成经纬度（7 位小数，与上传精度一致）。"""
    cy, cx = center
    return [(round(cy + y / MET_PER_DEG_LAT, 7), round(cx + x / MET_PER_DEG_LNG, 7))
            for y, x in points]


def _valid_point(value):
    """接受二元序列并返回有限 GCJ 坐标；占位值由 coordinate_pair 拒绝。"""
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        return None
    return coordinate_pair(value[0], value[1])


def normalize_points(values):
    """校验并复制坐标序列，去掉相邻重复点，拒绝不可用坐标。"""
    if not isinstance(values, (list, tuple)):
        raise ValueError("路线坐标必须是数组")
    out = []
    for value in values:
        point = _valid_point(value)
        if point is None:
            raise ValueError("路线坐标包含无效经纬度")
        if out and distance_m(out[-1], point) < 0.05:
            continue
        out.append(point)
    return out


def wgs84_to_gcj02(lat, lon):
    """把 GPS/GPX 常用的 WGS-84 坐标转换为高德 GCJ-02，供导入使用。"""
    a = 6378245.0
    ee = 0.00669342162296594323

    def out_of_china(y, x):
        return not (72.004 <= x <= 137.8347 and 0.8293 <= y <= 55.8271)

    def transform_lat(x, y):
        ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
        ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
        return ret

    def transform_lon(x, y):
        ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
        ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
        return ret

    if out_of_china(lat, lon):
        return lat, lon
    dlat = transform_lat(lon - 105.0, lat - 35.0)
    dlon = transform_lon(lon - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * math.pi
    magic = math.sin(rad_lat)
    magic = 1 - ee * magic * magic
    sqrt_magic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((a * (1 - ee)) / (magic * sqrt_magic) * math.pi)
    dlon = (dlon * 180.0) / (a / sqrt_magic * math.cos(rad_lat) * math.pi)
    return lat + dlat, lon + dlon


def _convert_coordinate(point, coord):
    """按声明的坐标系把单点转换为 GCJ-02。"""
    if coord == "gcj02":
        return point
    if coord == "bd09":
        return bd09_to_gcj02(*point)
    if coord == "wgs84":
        return wgs84_to_gcj02(*point)
    raise ValueError("不支持的坐标系：" + str(coord))


def _catmull_rom_closed(vertices, samples=SMOOTH_SAMPLES):
    """对闭合顶点序列做向心 Catmull-Rom 插值，不使用极角排序改动作业顺序。"""
    points = list(vertices)
    n = len(points)
    if n < 3:
        raise ValueError("手绘路线至少需要 3 个点")
    # 以重心为原点投影到米制平面，角度/比例不随纬度变形。
    center = (sum(p[0] for p in points) / n, sum(p[1] for p in points) / n)
    plane = _plane(points, center)

    def knot(a, b):
        """向心参数化：相邻点距离的平方根，避免尖点和自交。"""
        return math.hypot(b[0] - a[0], b[1] - a[1]) ** 0.5

    dense = []
    for index in range(n):
        p0 = plane[(index - 1) % n]
        p1 = plane[index]
        p2 = plane[(index + 1) % n]
        p3 = plane[(index + 2) % n]
        t0 = 0.0
        t1 = t0 + knot(p0, p1)
        t2 = t1 + knot(p1, p2)
        t3 = t2 + knot(p2, p3)
        if t1 == t0 or t2 == t1 or t3 == t2:
            raise ValueError("手绘路线包含重复点，无法平滑")
        for step in range(samples):
            t = t1 + (t2 - t1) * (step / samples)
            a1 = ((t1 - t) * p0[0] / (t1 - t0) + (t - t0) * p1[0] / (t1 - t0),
                  (t1 - t) * p0[1] / (t1 - t0) + (t - t0) * p1[1] / (t1 - t0))
            a2 = ((t2 - t) * p1[0] / (t2 - t1) + (t - t1) * p2[0] / (t2 - t1),
                  (t2 - t) * p1[1] / (t2 - t1) + (t - t1) * p2[1] / (t2 - t1))
            a3 = ((t3 - t) * p2[0] / (t3 - t2) + (t - t2) * p3[0] / (t3 - t2),
                  (t3 - t) * p2[1] / (t3 - t2) + (t - t2) * p3[1] / (t3 - t2))
            b1 = ((t2 - t) * a1[0] / (t2 - t0) + (t - t0) * a2[0] / (t2 - t0),
                  (t2 - t) * a1[1] / (t2 - t0) + (t - t0) * a2[1] / (t2 - t0))
            b2 = ((t3 - t) * a2[0] / (t3 - t1) + (t - t1) * a3[0] / (t3 - t1),
                  (t3 - t) * a2[1] / (t3 - t1) + (t - t1) * a3[1] / (t3 - t1))
            dense.append(((t2 - t) * b1[0] / (t2 - t1) + (t - t1) * b2[0] / (t2 - t1),
                          (t2 - t) * b1[1] / (t2 - t1) + (t - t1) * b2[1] / (t2 - t1)))
    return _unplane(dense, center)


def smooth_closed(vertices, max_spacing_m=DEFAULT_SPACING_M):
    """把标点序列变成首尾闭合、约每 max_spacing_m 一点的有序折线。"""
    points = normalize_points(vertices)
    if len(points) < MIN_VERTICES:
        raise ValueError("手绘路线至少需要 3 个点")
    dense = _catmull_rom_closed(points)
    # 弧长重采样，控制坐标点数量且保持曲线形状。
    arcs = [0.0]
    for a, b in zip(dense, dense[1:]):
        arcs.append(arcs[-1] + distance_m(a, b))
    # 补上闭合的一段，保证重采样覆盖整圈。
    closing = distance_m(dense[-1], dense[0])
    total = arcs[-1] + closing
    if total <= 0:
        raise ValueError("手绘路线长度为零")
    if total < MIN_LENGTH_M:
        raise ValueError("手绘路线太短（{:.0f} m），至少需要 {:.0f} m".format(total, MIN_LENGTH_M))
    spacing = max(float(max_spacing_m), total / MAX_POINTS)
    count = max(3, int(math.ceil(total / spacing)))
    ring = []
    for index in range(count):
        s = total * index / count
        ring.append(_point_at(dense, arcs, closing, s))
    ring.append(ring[0])
    return ring


def _point_at(dense, arcs, closing, s):
    """在闭合折线上按弧长取点；s=total 时回到起点。"""
    total = arcs[-1] + closing
    if s <= 0:
        return dense[0]
    if s >= total:
        return dense[0]
    if s >= arcs[-1]:
        t = (s - arcs[-1]) / closing if closing > 0 else 0.0
        a, b = dense[-1], dense[0]
        return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    lo, hi = 0, len(arcs) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if arcs[mid] <= s:
            lo = mid
        else:
            hi = mid - 1
    i = min(lo, len(dense) - 2)
    segment = arcs[i + 1] - arcs[i]
    t = (s - arcs[i]) / segment if segment > 0 else 0.0
    a, b = dense[i], dense[i + 1]
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def close_ring(ring):
    """返回首尾严格相同的闭合折线副本，并拒绝过短的输入。"""
    points = normalize_points(ring)
    if len(points) < 2:
        raise ValueError("路线折线至少需要 2 个不同点")
    if points[-1] != points[0]:
        gap = distance_m(points[-1], points[0])
        if gap > CLOSE_TOLERANCE_M:
            raise ValueError("路线不闭合：首尾相距 {:.0f} m（上限 {:.0f} m）".format(gap, CLOSE_TOLERANCE_M))
        points.append(points[0])
    return points


def route_length_m(ring):
    """计算闭合折线的折线长度。"""
    return sum(distance_m(a, b) for a, b in zip(ring, ring[1:]))


def ring_fingerprint(ring):
    """路线内容摘要，供方案明细与预览/提交一致性核对。"""
    text = json.dumps([[round(lat, 7), round(lon, 7)] for lat, lon in ring],
                      separators=(",", ":"))
    return hashlib.sha256(text.encode()).hexdigest()[:12]


def _arcs_for(ring):
    arcs = [0.0]
    for a, b in zip(ring, ring[1:]):
        arcs.append(arcs[-1] + distance_m(a, b))
    return arcs


def _nearest_vertex(ring, point, arcs):
    """返回 (最近顶点距离, 该顶点弧长位置, 顶点下标)。"""
    best = (float("inf"), 0.0, 0)
    for index, vertex in enumerate(ring[:-1]):
        current = distance_m(vertex, point)
        if current < best[0]:
            best = (current, arcs[index], index)
    return best


def analyze_route(ring, checkpoints_gcj=None, pass_radius_m=PASS_RADIUS_M, require_order=True):
    """校验闭合路线，并给出每个打卡点的最近距离与弧长位置。

    ``require_order`` 只在顺序打卡模式下为真；随机点模式不要求按编号顺序经过。
    """
    problems = []
    if len(ring) < 2:
        raise ValueError("路线折线至少需要 2 个不同点")
    gap = distance_m(ring[-1], ring[0])
    if gap > CLOSE_TOLERANCE_M:
        problems.append("路线首尾相距 {:.0f} m，未闭合".format(gap))
    length = route_length_m(ring)
    if length < MIN_LENGTH_M:
        problems.append("路线长度 {:.0f} m 小于 {:.0f} m".format(length, MIN_LENGTH_M))
    arcs = _arcs_for(ring)
    checkpoint_info = []
    order_ok = None
    route_order = None
    if checkpoints_gcj:
        positions = []
        for index, point in enumerate(checkpoints_gcj):
            nearest, arc, vertex_index = _nearest_vertex(ring, point, arcs)
            checkpoint_info.append({"index": index, "nearest_m": round(nearest, 2),
                                    "arc_m": round(arc, 2), "vertex_index": vertex_index})
            positions.append(arc)
        route_order = [index + 1 for index, _ in sorted(enumerate(positions), key=lambda item: item[1])]
        for item in checkpoint_info:
            if item["nearest_m"] > pass_radius_m:
                problems.append("第 {} 个打卡点距路线 {:.1f} m（需 ≤ {:.0f} m）".format(
                    item["index"] + 1, item["nearest_m"], pass_radius_m))
        if require_order:
            order_ok = all(later > earlier for earlier, later in zip(positions, positions[1:]))
            if not order_ok:
                problems.append(
                    "打卡点沿路线顺序与表单顺序不一致（路线经过顺序 {}；表单顺序 {}）。"
                    "可尝试反向画线或调整标点；随机点模式不受顺序限制".format(
                        "→".join(map(str, route_order)),
                        "→".join(str(index + 1) for index in range(len(checkpoints_gcj)))))
    return {"length_m": round(length, 2), "closed_gap_m": round(gap, 2),
            "checkpoints": checkpoint_info, "order_ok": order_ok,
            "route_order": route_order,
            "problems": problems, "vertex_count": len(ring)}


def align_route(ring, checkpoints_gcj, pass_radius_m=PASS_RADIUS_M, require_order=True):
    """旋转/必要时反向闭合路线，使起点落在第一个打卡点且顺序可跑。

    ``require_order`` 为假时只旋转并吸附起点，不检查、也不为了顺序反向。
    返回 ``(ring, info)``；``info`` 含 ``rotated``/``reversed``/``snapped``
    与重新计算后的 ``analyze_route`` 字段。
    """
    points = close_ring(ring)
    base = points[:-1]
    arcs = _arcs_for(points)
    checkpoint0 = checkpoints_gcj[0]
    _, _, start_index = _nearest_vertex(points, checkpoint0, arcs)
    rotated = start_index % len(base)
    base = base[rotated:] + base[:rotated]
    snapped = False
    gap = distance_m(base[0], checkpoint0)
    if gap <= pass_radius_m:
        base[0] = checkpoint0
        snapped = True
    ordered = list(base) + [base[0]]
    info = analyze_route(ordered, checkpoints_gcj, pass_radius_m, require_order=require_order)
    reversed_flag = False
    if require_order and info["order_ok"] is not True and len(base) > 2:
        candidate = [base[0]] + list(reversed(base[1:]))
        ordered = candidate + [candidate[0]]
        alt = analyze_route(ordered, checkpoints_gcj, pass_radius_m, require_order=True)
        if alt["order_ok"] is True:
            info, reversed_flag = alt, True
            base = candidate
        else:
            # 两个方向都不满足顺序时保留原方向，让 problems 暴露原因。
            pass
    info.update({"rotated": rotated != 0, "reversed": reversed_flag, "snapped": snapped})
    return base + [base[0]], info


def ring_from_payload(payload):
    """从载荷中取出 GCJ-02 闭合折线；没有平滑点时用顶点重新平滑。"""
    if not isinstance(payload, dict):
        raise ValueError("路线载荷必须是对象")
    coord = str(payload.get("coord", "gcj02")).lower()
    coord = _COORD_ALIASES.get(coord, coord)
    if coord not in ("gcj02", "bd09", "wgs84"):
        raise ValueError("不支持的坐标系：" + coord)
    raw = payload.get("points")
    if raw:
        points = [ _convert_coordinate(p, coord) for p in normalize_points(raw) ]
        return close_ring(points)
    vertices = payload.get("vertices")
    if not vertices:
        raise ValueError("路线载荷缺少 points 或 vertices")
    vertices = [_convert_coordinate(p, coord) for p in normalize_points(vertices)]
    return smooth_closed(vertices)


def prepare_payload(vertices, checkpoints_gcj=None, max_spacing_m=DEFAULT_SPACING_M,
                    require_order=True):
    """把地图标点整理成可保存/传给后端的路线载荷。

    ``require_order`` 为假（随机点模式或模式未知）时只把起点转到打卡点附近，
    不为满足顺序而反向。
    """
    ring = smooth_closed(vertices, max_spacing_m)
    if checkpoints_gcj:
        ring, _ = align_route(ring, checkpoints_gcj, require_order=require_order)
    payload = {"schema": SCHEMA, "coord": "gcj02",
               "vertices": [[round(lat, 7), round(lon, 7)] for lat, lon in normalize_points(vertices)],
               "points": [[lat, lon] for lat, lon in ring],
               "length_m": round(route_length_m(ring), 2)}
    return payload


def prepare_route(payload, checkpoints_gcj=None, pass_radius_m=PASS_RADIUS_M, require_order=True):
    """校验路线载荷并返回后端需要的 GCJ 闭合折线与检查结论。"""
    ring = ring_from_payload(payload)
    info = analyze_route(ring, checkpoints_gcj, pass_radius_m, require_order=require_order)
    if checkpoints_gcj:
        ring, aligned = align_route(ring, checkpoints_gcj, pass_radius_m, require_order=require_order)
        info = aligned
    info = dict(info)
    info["ring_gcj"] = ring
    info["fingerprint"] = ring_fingerprint(ring)
    return info


def save_route(path, payload):
    """把路线载荷写入 JSON 文件；父目录不存在时不创建，避免误写。"""
    path = Path(path)
    text = json.dumps(payload, ensure_ascii=False, indent=1, allow_nan=False)
    path.write_text(text, encoding="utf-8")


def load_route(path):
    """读取 JSON 坐标集，兼容 GCJ/BD/WGS 三种声明并统一成 GCJ 载荷。"""
    path = Path(path)
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (ValueError, OSError, UnicodeError) as exc:
        raise ValueError("路线文件无法读取：{}".format(exc)) from exc
    if isinstance(data, list):
        data = {"points": data}
    if not isinstance(data, dict):
        raise ValueError("路线文件必须是 JSON 对象或坐标数组")
    coord = str(data.get("coord", "gcj02")).lower()
    coord = _COORD_ALIASES.get(coord, coord)
    raw_points = data.get("points") or data.get("vertices") or []
    points = [_convert_coordinate(p, coord) for p in normalize_points(raw_points)]
    if len(points) < 2:
        raise ValueError("路线文件中的坐标点不足")
    vertices = data.get("vertices")
    if not vertices:
        vertices = points
    vertices = [_convert_coordinate(p, coord) for p in normalize_points(vertices)]
    raw = data.get("points")
    if raw:
        points = close_ring(points) if distance_m(points[-1], points[0]) <= CLOSE_TOLERANCE_M \
            else smooth_closed(points)
        ring = points
    else:
        ring = smooth_closed(vertices)
    payload = {"schema": SCHEMA, "coord": "gcj02",
               "vertices": [[round(lat, 7), round(lon, 7)] for lat, lon in vertices],
               "points": [[lat, lon] for lat, lon in ring],
               "length_m": round(route_length_m(ring), 2)}
    return payload
