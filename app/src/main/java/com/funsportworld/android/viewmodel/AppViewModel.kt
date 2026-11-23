package com.funsportworld.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.funsportworld.android.BuildConfig
import com.funsportworld.android.backend.BackendException
import com.funsportworld.android.backend.PythonBridge
import com.funsportworld.android.data.AppSettings
import com.funsportworld.android.data.AppUiState
import com.funsportworld.android.data.Checkpoint
import com.funsportworld.android.data.Parse
import com.funsportworld.android.data.PlanParams
import com.funsportworld.android.data.PlanUi
import com.funsportworld.android.data.RecordRow
import com.funsportworld.android.data.RouteMeta
import com.funsportworld.android.data.RoutePayload
import com.funsportworld.android.data.RouteStore
import com.funsportworld.android.data.SubmitResult
import com.funsportworld.android.data.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val bridge = PythonBridge(app, BuildConfig.VERSION_NAME)
    val routeStore = RouteStore(app)

    private val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(AppUiState())
    val state = stateFlow

    private val operationMutex = Mutex()
    private var logCursor = 0L

    init {
        initialize()
    }

    // ── lifecycle ────────────────────────────────────────────────────────
    fun initialize() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { bridge.start() }
            }
            result.onSuccess { init ->
                stateFlow.value = stateFlow.value.copy(pythonReady = true, startupError = null)
                refreshSettings()
                refreshRoutes()
                refreshDraft()
                init.optJSONObject("state")?.let { applySettings(it) }
            }.onFailure { error ->
                stateFlow.value = stateFlow.value.copy(
                    pythonReady = false,
                    startupError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun consumeMessage() {
        stateFlow.value = stateFlow.value.copy(message = null)
    }

    private fun notify(text: String, isError: Boolean = false) {
        stateFlow.value = stateFlow.value.copy(
            message = UiMessage(System.nanoTime(), text, isError),
        )
    }

    private fun io(block: suspend () -> Unit): Job = viewModelScope.launch {
        withContext(Dispatchers.IO) { block() }
    }

    // ── settings / accounts ──────────────────────────────────────────────
    fun refreshSettings() {
        io {
            runCatching { bridge.settingsSnapshot() }
                .onSuccess { applySettings(it) }
                .onFailure { notify(it.message ?: "读取配置失败", true) }
        }
    }

    private fun applySettings(snapshot: JSONObject) {
        stateFlow.value = stateFlow.value.copy(settings = Parse.settings(snapshot))
    }

    /**
     * `PythonBridge.call` returns the whole `{ok, result}` envelope; some
     * methods also include the refreshed settings snapshot inside `result`.
     */
    private fun stateFrom(response: JSONObject): JSONObject? {
        val payload = response.optJSONObject("result") ?: response
        return payload.optJSONObject("state")
    }

    fun refreshRoutes() {
        io {
            val routes = routeStore.listRoutes()
            stateFlow.value = stateFlow.value.copy(routes = routes)
        }
    }

    fun refreshDraft() {
        io {
            val draft = routeStore.loadDraft()
            stateFlow.value = stateFlow.value.copy(draft = draft)
        }
    }

    fun login(username: String, password: String, remember: Boolean) {
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) {
            notify("请填写账号", true)
            return
        }
        val current = stateFlow.value.settings
        // The Python core can fall back to the password saved on this device
        // when the caller passes an empty password for the same account.
        val savedPasswordAvailable = password.isBlank() &&
            current?.hasPassword == true &&
            current?.remember == true &&
            cleanUsername == current?.username
        if (password.isBlank() && !savedPasswordAvailable) {
            notify("请填写密码；已保存密码的账号可留空直接登录", true)
            return
        }
        val usedSavedPassword = password.isBlank()
        runBusy(if (usedSavedPassword) "正在使用保存的密码登录…" else "正在登录…") {
            val result = bridge.login(cleanUsername, password)
            val snapshot = stateFrom(result)
            snapshot?.let { applySettings(it) }
            // Never overwrite a stored password with an empty one. If the user
            // explicitly unchecked "remember", clear the old password instead.
            if (!usedSavedPassword) {
                runCatching { bridge.saveCredentials(cleanUsername, password, remember) }
            } else if (!remember) {
                runCatching { bridge.saveCredentials(cleanUsername, "", false) }
            }
            notify(if (usedSavedPassword) "已使用保存的密码登录" else "登录成功")
            refreshSettings()
        }
    }

    fun logout() {
        runBusy("正在退出登录…") {
            bridge.logout()
            notify("已退出登录")
            refreshSettings()
        }
    }

    fun switchAccount(username: String) {
        runBusy("正在切换账号…") {
            val result = bridge.switchAccount(username)
            stateFrom(result)?.let { applySettings(it) }
            if (!result.optBoolean("has_session", true)) {
                notify("已切换账号，但该账号需要重新登录")
            } else {
                notify("已切换到 $username")
            }
            refreshSettings()
        }
    }

    fun removeAccount(username: String) {
        runBusy("正在移除本机账号…") {
            bridge.removeAccount(username)
            notify("已从本机移除 $username（未请求服务器退出）")
            refreshSettings()
        }
    }

    fun saveSettings(values: JSONObject, onDone: (() -> Unit)? = null) {
        runBusy("正在保存设置…") {
            val result = bridge.saveSettings(values)
            stateFrom(result)?.let { applySettings(it) }
            notify("设置已保存")
            onDone?.invoke()
        }
    }

    /**
     * Persist a phone-location fix as the campus anchor while keeping every
     * other setting untouched. The coordinates remain manually editable in
     * Settings afterwards.
     */
    fun updateAnchor(latitude: Double, longitude: Double, onDone: (() -> Unit)? = null) {
        val snapshot = stateFlow.value.settings
        if (snapshot == null) {
            notify("配置尚未加载完成，请稍后再试", true)
            return
        }
        val values = JSONObject()
            .put("username", snapshot.username)
            .put("city", snapshot.city.ifBlank { "成都市" })
            .put("anchor_lat", String.format(java.util.Locale.US, "%.6f", latitude))
            .put("anchor_lon", String.format(java.util.Locale.US, "%.6f", longitude))
            .put("start_before_min", snapshot.startBeforeMin)
            .put("start_before_max", snapshot.startBeforeMax)
            .put("remember", snapshot.remember)
        saveSettings(values, onDone)
    }

    // ── checkpoints / policy ─────────────────────────────────────────────
    fun fetchPoints(fresh: Boolean) {
        runBusy("正在读取打卡点…") {
            val response = bridge.fetchPoints(fresh)
            val points = Parse.checkpoints(response)
            stateFlow.value = stateFlow.value.copy(
                checkpoints = points,
                checkpointsAt = System.currentTimeMillis(),
            )
            if (points.isEmpty()) {
                notify("接口没有返回打卡点，请检查账号与校园坐标", true)
            } else {
                notify("已读取 ${points.size} 个打卡点")
            }
            refreshSettings()
        }
    }

    // ── plan / submit ────────────────────────────────────────────────────
    fun generatePlan(params: PlanParams, onReady: (PlanUi) -> Unit = {}) {
        runBusy("正在生成方案…") {
            val response = bridge.preparePlan(params.toBridgeJson())
            val plan = Parse.plan(response)
            stateFlow.value = stateFlow.value.copy(plan = plan, submission = null)
            notify("方案已生成，尚未提交")
            onReady(plan)
        }
    }

    fun previewPlan(params: PlanParams, onReady: (PlanUi) -> Unit = {}) {
        runBusy("正在生成轨迹预览…") {
            val response = bridge.previewPlan(params.toBridgeJson())
            val plan = Parse.plan(response)
            stateFlow.value = stateFlow.value.copy(plan = plan, submission = null)
            notify("轨迹预览已生成（不可提交）")
            onReady(plan)
        }
    }

    fun submitPlan(allowOutside: Boolean, onDone: (SubmitResult) -> Unit = {}) {
        val plan = stateFlow.value.plan
        if (plan == null) {
            notify("请先生成并预览方案", true)
            return
        }
        if (plan.previewOnly) {
            notify("轨迹预览不能提交；请重新在线生成方案", true)
            return
        }
        runBusy("正在提交跑步记录…") {
            val result = bridge.submitPlan(plan.planId, allowOutside)
            val submit = SubmitResult(
                rrid = result.optLong("rrid"),
                uuid = result.optString("uuid"),
                obsOk = result.optInt("obs_ok"),
                detailOk = result.optBoolean("detail_ok"),
                dist = result.optDouble("dist"),
                dur = result.optInt("dur"),
                steps = result.optInt("steps"),
                cadence = result.optDouble("cadence_avg"),
                checkpointPassed = result.optJSONObject("checkpoint_report")?.optInt("passed_count"),
                checkpointCount = stateFlow.value.plan?.checkpoints?.size,
            )
            stateFlow.value = stateFlow.value.copy(plan = null, submission = submit)
            notify("提交完成，rrid=${submit.rrid}")
            onDone(submit)
        }
    }

    fun clearPlan() {
        val plan = stateFlow.value.plan ?: return
        io { runCatching { bridge.cancelPlan(plan.planId) } }
        stateFlow.value = stateFlow.value.copy(plan = null)
    }

    // ── records ──────────────────────────────────────────────────────────
    fun fetchRecords() {
        if (stateFlow.value.recordsLoading) return
        stateFlow.value = stateFlow.value.copy(recordsLoading = true)
        io {
            runCatching { bridge.fetchRecords() }
                .onSuccess { response ->
                    val array = response.optJSONArray("records") ?: JSONArray()
                    val rows = ArrayList<RecordRow>(array.length())
                    for (i in 0 until array.length()) {
                        val row = array.optJSONObject(i) ?: continue
                        rows.add(
                            RecordRow(
                                rrid = row.optLong("rrid"),
                                totalDis = row.optDouble("total_dis"),
                                totalTime = row.optInt("total_time"),
                                startTime = row.optLong("start_time"),
                                complete = if (row.has("complete") && !row.isNull("complete")) row.optBoolean("complete") else null,
                                avgStepFreq = row.optInt("avg_step_freq"),
                                calorie = row.optInt("calorie"),
                                totalSteps = row.optInt("total_steps"),
                            )
                        )
                    }
                    stateFlow.value = stateFlow.value.copy(records = rows)
                }
                .onFailure { error ->
                    notify(error.message ?: "读取记录失败", true)
                }
            stateFlow.value = stateFlow.value.copy(recordsLoading = false)
        }
    }

    // ── shared operation runner ──────────────────────────────────────────
    private fun runBusy(label: String, block: suspend () -> Unit) {
        if (!stateFlow.value.pythonReady) {
            notify("Python 后端尚未就绪，请稍候或重启应用", true)
            return
        }
        if (stateFlow.value.busy) return
        viewModelScope.launch {
            operationMutex.withLock {
                stateFlow.value = stateFlow.value.copy(
                    busy = true,
                    busyLabel = label,
                    logs = emptyList(),
                )
                val poller = launch(Dispatchers.IO) {
                    while (isActive) {
                        drainLogs()
                        delay(320)
                    }
                }
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (error: BackendException) {
                    notify(error.message ?: "操作失败", true)
                } catch (error: Throwable) {
                    notify(error.message ?: error.javaClass.simpleName, true)
                } finally {
                    poller.cancel()
                    drainLogs()
                    stateFlow.value = stateFlow.value.copy(busy = false, busyLabel = "")
                }
            }
        }
    }

    private suspend fun drainLogs() {
        val response = runCatching { bridge.pollLogs(logCursor) }.getOrNull() ?: return
        val (next, lines) = Parse.logLines(response)
        logCursor = next
        if (lines.isNotEmpty()) {
            val merged = (stateFlow.value.logs + lines.map { it.text }).takeLast(80)
            stateFlow.value = stateFlow.value.copy(logs = merged)
        }
    }

    fun rememberCheckpointState(points: List<Checkpoint>) {
        stateFlow.value = stateFlow.value.copy(checkpoints = points)
    }

    fun storeRoutes(routes: List<RouteMeta>) {
        stateFlow.value = stateFlow.value.copy(routes = routes)
    }

    fun updateDraft(draft: RouteStore.Draft?) {
        stateFlow.value = stateFlow.value.copy(draft = draft)
    }

    fun setActiveRoute(payload: RoutePayload?, label: String) {
        stateFlow.value = stateFlow.value.copy(
            activeRoute = payload,
            activeRouteLabel = if (payload == null) "" else label,
        )
    }

    fun clearActiveRoute() {
        stateFlow.value = stateFlow.value.copy(activeRoute = null, activeRouteLabel = "")
    }

    fun useDraftAsActiveRoute() {
        val draft = stateFlow.value.draft ?: return
        if (draft.ring.size < 3) return
        val payload = RoutePayload(
            vertices = draft.vertices,
            ring = draft.ring,
            lengthM = com.funsportworld.android.data.polylineLength(draft.ring),
        )
        setActiveRoute(payload, draft.name.ifBlank { "草稿路线" })
    }

    fun settingsOrNull(): AppSettings? = stateFlow.value.settings
}
