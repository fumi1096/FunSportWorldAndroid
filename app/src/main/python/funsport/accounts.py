"""多账号本地账号库：保存多个账号的凭据、会话与校园设置，支持随时切换。

约定：
- 活动账号的会话仍写入 ``session.json``，``ApiClient``/``with_client`` 等业务
  代码不需要改动；``accounts.json`` 保存每个账号最近一次会话及其它账号信息。
- 没有 ``accounts.json`` 时，从 ``config.json`` / ``session.json`` / ``identity.json``
  推导单账号视图（只读迁移，第一次写操作才落盘）。
- 密码仅在“记住密码”时保存，与 config.json 一样是本机明文；界面快照只返回
  是否保存，不返回密码或令牌。
"""
import time

from . import config

SCHEMA_VERSION = 1
_FILENAME = "accounts.json"


def accounts_path():
    """返回账号库路径；动态读取 config.DATA_DIR，便于测试隔离。"""
    return config.DATA_DIR / _FILENAME


def _now_ms():
    return int(time.time() * 1000)


def _clean_username(username):
    if not isinstance(username, str) or not username.strip():
        raise ValueError("账号不能为空")
    return username.strip()


def _clean_identity(value):
    """只保留校园定位需要的字段，忽略密码等其它内容。"""
    out = {}
    if not isinstance(value, dict):
        return out
    for key in ("anchor_lat", "anchor_lon"):
        number = value.get(key)
        if isinstance(number, (int, float)) and not isinstance(number, bool):
            out[key] = float(number)
    city = value.get("city")
    if isinstance(city, str) and city.strip():
        out["city"] = city.strip()
    return out


def _identity_snapshot(identity=None):
    """从 identity.json 读取当前校园坐标与城市，作为活动账号的备份。"""
    if identity is None:
        identity = config.load_identity()
    return _clean_identity(identity)


def _normalize_entry(username, entry):
    """把单条账号记录收敛成固定结构，坏字段丢弃而不是整库失败。"""
    entry = entry if isinstance(entry, dict) else {}
    session = entry.get("session")
    if not (isinstance(session, dict) and session.get("uid") and session.get("token")):
        session = {}
    stamp = entry.get("updated_at")
    return {
        "username": username,
        "password": entry.get("password") if isinstance(entry.get("password"), str) else "",
        "remember": bool(entry.get("remember")),
        "session": dict(session),
        "identity": _clean_identity(entry.get("identity")),
        "updated_at": stamp if isinstance(stamp, int) else _now_ms(),
    }


def _normalize(data):
    store = {"version": SCHEMA_VERSION, "active": "", "accounts": {}}
    if not isinstance(data, dict):
        return store
    version = data.get("version")
    if isinstance(version, int) and version > 0:
        store["version"] = version
    active = data.get("active")
    if isinstance(active, str):
        store["active"] = active
    accounts = data.get("accounts")
    if isinstance(accounts, dict):
        for name, entry in accounts.items():
            if isinstance(name, str) and name.strip():
                store["accounts"][name.strip()] = _normalize_entry(name.strip(), entry)
    if store["active"] and store["active"] not in store["accounts"]:
        store["active"] = ""
    return store


def _migrate_from_legacy():
    """旧版本升级：把单账号配置、会话和校园坐标映射成账号库（只读）。

    这里不使用 load_identity()，避免只读迁移在启动时创建 identity.json。
    """
    store = _normalize(None)
    cfg = config.load_config()
    session = config.load_session()
    identity = config.load_json(config.IDENTITY_FILE) or {}
    cfg_user = (cfg.get("username") or "").strip()
    session_user = (session.get("username") or "").strip() if isinstance(session, dict) else ""
    username = session_user or cfg_user
    if username:
        entry = _normalize_entry(username, {})
        if cfg_user == username:
            entry["remember"] = bool(cfg.get("remember"))
            entry["password"] = (cfg.get("password") or "") if entry["remember"] else ""
        entry["identity"] = _identity_snapshot(identity)
        store["accounts"][username] = entry
        store["active"] = username
    if isinstance(session, dict) and session.get("uid") and session.get("token"):
        name = session_user or store["active"] or "默认账号"
        entry = store["accounts"].setdefault(name, _normalize_entry(name, {}))
        entry["session"] = dict(session)
        store["active"] = name
        if not entry["identity"]:
            entry["identity"] = _identity_snapshot(identity)
    return store


def load_store():
    """读取账号库；缺失时兼容旧配置，损坏时拒绝覆盖并说明文件。"""
    path = accounts_path()
    if not path.exists():
        return _migrate_from_legacy()
    raw = config.load_json(path)
    if not isinstance(raw, dict) or not isinstance(raw.get("accounts"), dict):
        raise ValueError("accounts.json 无法读取或格式不正确，已保留原文件")
    return _normalize(raw)


def save_store(store):
    """原子写入账号库；调用方负责传入 normalize 后的结构。"""
    normalized = _normalize(store)
    config.save_json(accounts_path(), normalized)
    return normalized


def active_account():
    return load_store()["active"]


def list_accounts():
    """返回界面用的账号摘要；不包含密码、令牌或完整会话。"""
    store = load_store()
    rows = []
    for username, entry in sorted(store["accounts"].items()):
        session = entry.get("session") or {}
        rows.append({
            "username": username,
            "active": username == store["active"],
            "has_session": bool(session.get("uid") and session.get("token")),
            "has_password": bool(entry.get("password")),
            "remember": bool(entry.get("remember")),
            "name": session.get("name") or "",
            "uid": session.get("uid"),
        })
    return rows


def _apply_config(store, username):
    """把指定账号的凭据/城市写回 config.json，作为 CLI 与表单的活动值。"""
    entry = store["accounts"][username]
    cfg = config.load_config()
    cfg["username"] = username
    if entry.get("remember") and entry.get("password"):
        cfg["password"] = entry["password"]
        cfg["remember"] = True
    else:
        cfg["password"] = ""
        cfg["remember"] = False
    city = (entry.get("identity") or {}).get("city")
    if city:
        cfg["city"] = city
    config.save_config(cfg)


def _apply_identity(store, username):
    """把账号保存的校园坐标写回 identity.json；没有备份时保持现状。"""
    identity = (store["accounts"][username].get("identity") or {})
    if not identity:
        return False
    current = config.load_identity()
    changed = False
    for key in ("anchor_lat", "anchor_lon", "city"):
        if key in identity and current.get(key) != identity[key]:
            current[key] = identity[key]
            changed = True
    if changed:
        config.save_identity(current)
    return changed


def _archive_current(store):
    """把当前活动会话写回它所属账号，切换/新登录时不丢旧账号登录状态。"""
    active = store.get("active") or ""
    current = config.load_session()
    if not isinstance(current, dict) or not current.get("uid") or not current.get("token"):
        return
    name = (current.get("username") or active).strip() or active
    if not name:
        return
    entry = store["accounts"].setdefault(name, _normalize_entry(name, {}))
    entry["session"] = dict(current)
    entry["updated_at"] = _now_ms()


def save_login(username, session, password=None, remember=None):
    """登录成功后登记账号：归档旧会话、保存新会话并设为活动账号。"""
    username = _clean_username(username)
    if not isinstance(session, dict) or not session.get("uid") or not session.get("token"):
        raise ValueError("登录会话缺少 uid/token")
    store = load_store()
    _archive_current(store)
    entry = store["accounts"].setdefault(username, _normalize_entry(username, {}))
    entry["username"] = username
    entry["session"] = dict(session)
    entry["updated_at"] = _now_ms()
    if password is not None:
        entry["password"] = password if remember is not False else ""
        entry["remember"] = bool(remember)
    elif remember is not None:
        entry["remember"] = bool(remember)
    if not entry["identity"]:
        entry["identity"] = _identity_snapshot()
    store["active"] = username
    save_store(store)
    config.save_session(dict(session))
    _apply_config(store, username)
    return entry


def switch_account(username):
    """本地切换活动账号：换入目标会话、凭据与校园坐标，不访问网络。"""
    username = _clean_username(username)
    store = load_store()
    if username not in store["accounts"]:
        raise ValueError("账号未保存：{}".format(username))
    current = store.get("active") or ""
    if current and current != username:
        _archive_current(store)
    entry = store["accounts"][username]
    session = entry.get("session") or {}
    if session.get("uid") and session.get("token"):
        config.save_session(dict(session))
    else:
        config.clear_session()
    applied = _apply_identity(store, username)
    store["active"] = username
    save_store(store)
    _apply_config(store, username)
    return {"username": username,
            "has_session": bool(session.get("uid") and session.get("token")),
            "identity_applied": applied}


def clear_active_session():
    """服务器确认退出后清理活动账号的会话，保留凭据便于下次登录。"""
    store = load_store()
    active = store.get("active") or ""
    entry = store["accounts"].get(active)
    if not entry or not entry.get("session"):
        return False
    entry["session"] = {}
    entry["updated_at"] = _now_ms()
    save_store(store)
    return True


def remove_account(username):
    """从本机移除账号；若移除的是活动账号，同时清理 session.json（不请求服务器）。"""
    username = _clean_username(username)
    store = load_store()
    if username not in store["accounts"]:
        raise ValueError("账号未保存：{}".format(username))
    del store["accounts"][username]
    if store["active"] == username:
        store["active"] = ""
    save_store(store)
    if not store["active"]:
        config.clear_session()
    return True


def upsert_credentials(username, password, remember):
    """保存/更新某账号的“记住密码”设置，不改变当前活动账号。"""
    username = _clean_username(username)
    store = load_store()
    entry = store["accounts"].setdefault(username, _normalize_entry(username, {}))
    entry["remember"] = bool(remember)
    entry["password"] = password if remember else ""
    entry["updated_at"] = _now_ms()
    if not entry["identity"]:
        entry["identity"] = _identity_snapshot()
    save_store(store)
    return entry


def remember_identity(username=None):
    """把当前 identity.json 的校园坐标记到指定/活动账号。"""
    store = load_store()
    name = username or store.get("active")
    if not name:
        return None
    if name not in store["accounts"]:
        store["accounts"][name] = _normalize_entry(name, {})
    entry = store["accounts"][name]
    entry["identity"] = _identity_snapshot()
    entry["updated_at"] = _now_ms()
    save_store(store)
    return entry


def sync_settings(username, password, remember, anchor=None, city=None):
    """保存设置后同步到账号库；没有可用会话时把该账号设为活动账号。"""
    username = _clean_username(username)
    store = load_store()
    entry = store["accounts"].setdefault(username, _normalize_entry(username, {}))
    entry["remember"] = bool(remember)
    entry["password"] = password if remember else ""
    identity = dict(anchor or {})
    if city:
        identity["city"] = city
    entry["identity"] = _clean_identity(identity) or entry["identity"]
    entry["updated_at"] = _now_ms()
    session = config.load_session()
    if not (isinstance(session, dict) and session.get("uid") and session.get("token")):
        store["active"] = username
    save_store(store)
    return store
