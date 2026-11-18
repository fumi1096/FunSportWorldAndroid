"""Android bridge for the FunSportWorld Python core.

This module is loaded by Chaquopy from the Android app. It reuses the desktop
project's protocol, track-generation and route-validation code (copied into
this app's ``python`` source set) and exposes a small JSON-only API to Kotlin.

Design notes
------------
* ``init()`` must be called once before anything else. It sets
  ``FUNSPORT_DATA_DIR`` to the app's private files directory *before* the
  ``funsport`` package is imported, so every persistence path stays inside the
  Android sandbox and nothing touches the desktop data directory.
* Every public function returns a JSON string, never a PyObject. That keeps the
  Kotlin bridge simple and avoids holding Chaquopy references across threads.
* Long-running calls (login, plan, submit) append human-readable progress lines
  to a ring buffer. Kotlin polls ``poll_logs()`` to show stages in the UI.
* Plans are kept in memory by ``plan_id`` so the exact previewed snapshot is the
  one submitted (the same single-use guard as the desktop GUI).
"""

import json
import logging
import os
import threading
import time
import traceback

_DATA_DIR = None
_READY = False
_MODS = None
_LOGS = []
_LOG_SEQ = 0
_LOG_LOCK = threading.Lock()
_PLANS = {}
_PLANS_LOCK = threading.Lock()

_ANSI_RE = None


# ── helpers ────────────────────────────────────────────────────────────────
def _json(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), default=str)


def _strip_ansi(text):
    global _ANSI_RE
    if _ANSI_RE is None:
        import re
        _ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")
    return _ANSI_RE.sub("", str(text))


def _append_log(message):
    """Append one progress line; keep a bounded ring so polling stays cheap."""
    global _LOG_SEQ
    text = _strip_ansi(message).strip()
    if not text:
        return
    with _LOG_LOCK:
        _LOG_SEQ += 1
        _LOGS.append({"seq": _LOG_SEQ, "time": int(time.time() * 1000), "text": text})
        if len(_LOGS) > 500:
            del _LOGS[:250]


def poll_logs(since=0):
    """Return progress lines newer than ``since`` plus the next cursor."""
    try:
        since = int(since)
    except (TypeError, ValueError):
        since = 0
    with _LOG_LOCK:
        lines = [item for item in _LOGS if item["seq"] > since]
        next_seq = _LOG_SEQ
    return _json({"ok": True, "next": next_seq, "lines": lines})


class _CaptureHandler(logging.Handler):
    def emit(self, record):
        try:
            _append_log(record.getMessage())
        except Exception:
            pass


def _ensure():
    global _MODS, _READY
    if _MODS is not None:
        return _MODS
    if not _READY:
        raise RuntimeError("backend not initialized")
    from funsport import accounts, completion, config, coordinates, diagnostics
    from funsport import gui_services, logger, route_sketch, run_plan
    from funsport.api import client, flow, login as api_login, obs, points, policy
    from funsport.api import records, submit
    from funsport.track import calorie, checkpoints, generator, geom, pace, postfix, wire

    _MODS = {
        "accounts": accounts, "completion": completion, "config": config,
        "coordinates": coordinates, "diagnostics": diagnostics,
        "gui_services": gui_services, "logger": logger,
        "route_sketch": route_sketch, "run_plan": run_plan,
        "client": client, "flow": flow, "api_login": api_login, "obs": obs,
        "points": points, "policy": policy, "records": records,
        "submit": submit, "calorie": calorie, "checkpoints": checkpoints,
        "generator": generator, "geom": geom, "pace": pace,
        "postfix": postfix, "wire": wire,
    }
    logger.log.addHandler(_CaptureHandler())
    logger.log.setLevel(logging.DEBUG)
    return _MODS


def _error(exc):
    try:
        message = str(exc)
    except Exception:
        message = repr(exc)
    _append_log("× " + message)
    return _json({
        "ok": False,
        "error": message,
        "error_type": type(exc).__name__,
        "trace": traceback.format_exc()[-2000:],
    })


def _ok(**payload):
    result = {"ok": True}
    result.update(payload)
    return _json(result)


def _call(fn):
    try:
        return _ok(result=fn())
    except Exception as exc:  # noqa: BLE001 - surfaced to the UI verbatim
        return _error(exc)


# ── lifecycle ──────────────────────────────────────────────────────────────
def init(data_dir, app_version=""):
    """Initialise the data directory, capture logs and import the core."""
    global _DATA_DIR, _READY
    try:
        path = str(data_dir)
        os.makedirs(path, exist_ok=True)
        os.environ["FUNSPORT_DATA_DIR"] = path
        _DATA_DIR = path
        _READY = True
        mods = _ensure()
        snapshot = _snapshot(mods)
        return _json({
            "ok": True,
            "python": os.sys.version.split()[0],
            "data_dir": path,
            "app_version": app_version,
            "state": snapshot,
        })
    except Exception as exc:  # noqa: BLE001
        return _error(exc)


def version():
    return _ok(python=os.sys.version.split()[0], data_dir=_DATA_DIR or "")


def _snapshot(mods):
    """Settings snapshot without passwords, tokens or the AMap key itself."""
    gui = mods["gui_services"]
    config = mods["config"]
    snapshot = gui.settings_snapshot()
    identity = config.load_json(config.IDENTITY_FILE) or {}
    session = config.load_session() or {}
    snapshot.update({
        "anchor_lat": str(identity.get("anchor_lat", "")),
        "anchor_lon": str(identity.get("anchor_lon", "")),
        "device_name": identity.get("device_name", ""),
        "platform": identity.get("platform", "ios"),
        "session_name": session.get("name", ""),
        "session_uid": session.get("uid"),
        "has_session": bool(session.get("uid") and session.get("token")),
        "data_dir": _DATA_DIR or "",
    })
    cache = config.load_points_cache(with_metadata=True)
    if isinstance(cache, dict):
        snapshot["points_cache"] = {
            "count": len(cache.get("points") or []),
            "ts": cache.get("ts", 0),
            "context": cache.get("context"),
        }
    else:
        snapshot["points_cache"] = None
    return snapshot


def state():
    def run():
        return _snapshot(_ensure())
    return _call(run)


# ── settings / accounts ────────────────────────────────────────────────────
def save_settings(values_json):
    def run():
        values = json.loads(values_json or "{}")
        mods = _ensure()
        result = mods["gui_services"].save_settings(values)
        result["state"] = _snapshot(mods)
        return result
    return _call(run)


def save_credentials(username, password, remember):
    """Persist the optional "remember password" choice without touching campus settings."""
    def run():
        mods = _ensure()
        config = mods["config"]
        accounts = mods["accounts"]
        username = str(username or "").strip()
        if not username:
            raise ValueError("账号不能为空")
        cfg = config.load_config()
        cfg["username"] = username
        cfg["remember"] = bool(remember)
        cfg["password"] = password if remember else ""
        config.save_config(cfg)
        accounts.upsert_credentials(username, password if remember else "", bool(remember))
        return {"remember": bool(remember), "saved": bool(remember and password)}
    return _call(run)


def login(username, password):
    def run():
        mods = _ensure()
        _append_log("→ 正在登录 {}…".format(username))
        result = mods["gui_services"].login_account(username, password or "")
        _append_log("√ " + str(result))
        return {"message": result, "state": _snapshot(mods)}
    return _call(run)


def logout():
    def run():
        mods = _ensure()
        _append_log("→ 正在请求服务器退出…")
        result = mods["gui_services"].logout_account()
        state = _snapshot(mods)
        return {"result": result, "state": state}
    return _call(run)


def switch_account(username):
    def run():
        mods = _ensure()
        result = mods["gui_services"].switch_account(username)
        result["state"] = _snapshot(mods)
        return result
    return _call(run)


def remove_account(username):
    def run():
        mods = _ensure()
        result = mods["gui_services"].remove_account(username)
        result["state"] = _snapshot(mods)
        return result
    return _call(run)


# ── read-only API ──────────────────────────────────────────────────────────
def fetch_policy():
    def run():
        mods = _ensure()
        with_client = mods["gui_services"].with_client
        pol = with_client(lambda client: mods["policy"].fetch_policy(client))
        return {
            "timestamp": pol.timestamp,
            "policy": pol.policy,
            "min_distance": pol.min_distance,
            "valid_time": pol.valid_time,
            "freeze_run_time": pol.freeze_run_time,
            "message": pol.message,
            "run_rules": pol.run_rules,
            "geo_fence": pol.geo_fence,
            "run_area_models": pol.run_area_models,
        }
    return _call(run)


def fetch_points(fresh=False):
    def run():
        mods = _ensure()
        with_client = mods["gui_services"].with_client
        pts, metadata = with_client(
            lambda client: mods["points"].fetch_points(
                client, with_metadata=True, fresh=bool(fresh), allow_stale=not fresh))
        report = mods["coordinates"].coordinate_report(pts) if pts else None
        checkpoints = []
        for index, point in enumerate(pts):
            try:
                _, gcj, source, delta = mods["coordinates"].checkpoint_coordinates(point)
            except ValueError:
                continue
            checkpoints.append({
                "index": index,
                "id": point.get("id"),
                "name": point.get("pointName", ""),
                "lat": gcj[0], "lon": gcj[1],
                "bd_lat": point.get("lat"), "bd_lon": point.get("lon"),
                "position": point.get("position", 999),
                "isFixed": point.get("isFixed", 0),
                "source": source,
                "delta_m": delta,
            })
        return {
            "points": pts,
            "metadata": metadata,
            "coordinate_report": report,
            "checkpoints": checkpoints,
            "count": len(pts),
        }
    return _call(run)


def fetch_records():
    def run():
        mods = _ensure()
        rows = mods["gui_services"].with_client(
            lambda client: mods["records"].fetch_records(client))
        return {"records": rows}
    return _call(run)


def get_record(rrid):
    def run():
        mods = _ensure()
        detail = mods["gui_services"].with_client(
            lambda client: mods["records"].fetch_one_record(client, int(rrid)))
        report = mods["diagnostics"].analyze_checkpoints(detail)
        return {"detail": detail, "report": report}
    return _call(run)


# ── plan / submit ──────────────────────────────────────────────────────────
def _track_preview(mods, plan_data, max_points=520):
    """Downsample the frozen track to GCJ points for the map."""
    locations = plan_data["track"]["locations"]
    coords = mods["coordinates"].bd09_to_gcj02
    count = len(locations)
    step = max(1, (count + max_points - 1) // max_points)
    out = []
    for index in range(0, count, step):
        point = locations[index]
        if point.get("type") == -1:
            continue
        lat, lon = coords(point["gLat"], point["gLng"])
        out.append([round(lat, 7), round(lon, 7)])
    if locations:
        first = locations[0]
        last = locations[-1]
        for point in (first, last):
            lat, lon = coords(point["gLat"], point["gLng"])
            pair = [round(lat, 7), round(lon, 7)]
            if not out or out[-1] != pair:
                out.append(pair)
    return out


def _plan_response(mods, plan, route_payload=None):
    data = plan.data()
    track = data["track"]
    run_plan = mods["run_plan"]
    points = data.get("points") or []
    checkpoints = data.get("checkpoint_distances") or []
    checkpoint_rows = []
    for index, point in enumerate(points):
        try:
            _, gcj, _, _ = mods["coordinates"].checkpoint_coordinates(point)
        except ValueError:
            continue
        checkpoint_rows.append({
            "index": index,
            "name": point.get("pointName", ""),
            "lat": gcj[0], "lon": gcj[1],
            "position": point.get("position", 999),
            "nearest_m": checkpoints[index] if index < len(checkpoints) else None,
        })
    route = data.get("route") or {}
    policy = data.get("policy")
    freeze = data.get("freeze_run_time") or 0
    track_preview = _track_preview(mods, data)
    completion = data.get("completion") or {}
    evaluation = data.get("checkpoint_evaluation") or {}
    payload = {
        "plan_id": plan.plan_id,
        "preview_only": bool(data.get("preview_only")),
        "details": run_plan.format_plan(plan),
        "provider": route.get("provider", ""),
        "route_meta": route,
        "route_payload": route_payload,
        "policy": policy,
        "min_distance": data.get("min_distance"),
        "start_ms": track["startTime"],
        "total_time": track["totalTime"],
        "total_distance": track["totalDistance"],
        "total_steps": track["totalSteps"],
        "pace_s_per_km": (track["totalTime"] * 1000 / track["totalDistance"]
                          if track["totalDistance"] else 0),
        "cadence": (track["totalSteps"] * 60 / track["totalTime"]
                    if track["totalTime"] else 0),
        "window_ok": bool(data.get("window_ok")),
        "window_reason": data.get("window_reason", ""),
        "freeze_run_time": freeze,
        "allow_outside_window": bool(data.get("allow_outside_window")),
        "seed": data.get("seed"),
        "checkpoints": checkpoint_rows,
        "checkpoint_evaluation": {
            "supported": evaluation.get("supported"),
            "all_passed": evaluation.get("all_passed"),
            "passed_count": evaluation.get("passed_count"),
            "event_count": len(evaluation.get("events") or []),
            "skipped_counts": evaluation.get("skipped_counts"),
        },
        "completion": {
            "supported": completion.get("supported"),
            "complete": completion.get("complete"),
            "reason": completion.get("unCompleteReason"),
        },
        "coordinate_report": data.get("coordinate_report"),
        "checkpoint_order": data.get("checkpoint_order"),
        "track_preview": track_preview,
        "location_count": len(track["locations"]),
    }
    if route.get("provider") == "user_sketch":
        payload["sketch"] = route.get("sketch")
    return payload


def _store_plan(plan):
    with _PLANS_LOCK:
        _PLANS[plan.plan_id] = plan
        # Keep at most 4 plans; old previews are not needed after a new one.
        while len(_PLANS) > 4:
            oldest = next(iter(_PLANS))
            if oldest == plan.plan_id:
                break
            _PLANS.pop(oldest, None)


def prepare_plan(params_json):
    def run():
        mods = _ensure()
        params = json.loads(params_json or "{}")
        route = params.get("route") or None
        flow = mods["flow"]
        kwargs = {
            "auto": bool(params.get("auto")),
            "seed": int(params.get("seed") or 0),
            "start_ms": int(params.get("start_ms") or 0),
            "before": params.get("before"),
            "min_dist": params.get("min_dist"),
            "allow_outside_window": bool(params.get("allow_outside")),
            "route": route,
        }
        if not kwargs["auto"]:
            kwargs.update(
                dist=float(params.get("dist") or 0),
                pace=float(params.get("pace") or 0),
                cadence=float(params.get("cadence") or 0),
            )
        with_client = mods["gui_services"].with_client
        _append_log("→ 生成跑步方案（不提交）…")
        plan = with_client(lambda client: flow.prepare_run_plan(client, **kwargs))
        _store_plan(plan)
        result = _plan_response(mods, plan, route_payload=route)
        return result
    return _call(run)


def preview_plan(params_json):
    def run():
        mods = _ensure()
        params = json.loads(params_json or "{}")
        route = params.get("route") or None
        flow = mods["flow"]
        with_client = mods["gui_services"].with_client
        _append_log("→ 生成本地轨迹预览（不访问运动服务）…")
        plan = with_client(lambda client: flow.prepare_route_preview(
            client,
            float(params.get("dist") or 0),
            float(params.get("pace") or 0),
            float(params.get("cadence") or 0),
            start_ms=int(params.get("start_ms") or 0),
            before=params.get("before") or 0,
            seed=int(params.get("seed") or 0),
            route=route,
        ))
        _store_plan(plan)
        return _plan_response(mods, plan, route_payload=route)
    return _call(run)


def submit_plan(plan_id, allow_outside=False):
    def run():
        mods = _ensure()
        with _PLANS_LOCK:
            plan = _PLANS.get(str(plan_id))
        if plan is None:
            raise ValueError("方案已失效（应用可能已重启或已重新生成），请重新生成方案后再提交")
        flow = mods["flow"]
        result = mods["gui_services"].with_client(
            lambda client: flow.submit_run_plan(
                client, plan, allow_outside_window=bool(allow_outside)))
        with _PLANS_LOCK:
            _PLANS.pop(str(plan_id), None)
        report = result.pop("checkpoint_report", None)
        if report:
            result["checkpoint_report"] = {
                "all_passed": report.get("all_passed"),
                "passed_count": report.get("passed_count"),
                "mode": report.get("mode"),
                "events": len(report.get("events") or []),
            }
        _append_log("√ 提交完成 rrid={}".format(result.get("rrid")))
        return result
    return _call(run)


def cancel_plan(plan_id):
    with _PLANS_LOCK:
        _PLANS.pop(str(plan_id), None)
    return _ok(removed=True)


def plan_details(plan_id):
    def run():
        mods = _ensure()
        with _PLANS_LOCK:
            plan = _PLANS.get(str(plan_id))
        if plan is None:
            raise ValueError("方案已失效，请重新生成")
        return _plan_response(mods, plan)
    return _call(run)
