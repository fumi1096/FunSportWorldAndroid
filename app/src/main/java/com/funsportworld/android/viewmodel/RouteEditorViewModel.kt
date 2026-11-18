package com.funsportworld.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.funsportworld.android.data.Checkpoint
import com.funsportworld.android.data.LatLon
import com.funsportworld.android.data.RoutePayload
import com.funsportworld.android.data.RouteStore
import com.funsportworld.android.route.RouteAnalysis
import com.funsportworld.android.route.RouteMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorMode { ADD, MOVE }

data class EditorState(
    val vertices: List<LatLon> = emptyList(),
    val ring: List<LatLon> = emptyList(),
    val selectedVertex: Int? = null,
    val mode: EditorMode = EditorMode.ADD,
    val lengthM: Double = 0.0,
    val analysis: RouteAnalysis? = null,
    val checkpointCount: Int = 0,
    val requireOrder: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val name: String = "",
    val note: String = "",
    val savedRouteId: String? = null,
    val restoredDraft: Boolean = false,
    val lastAutosaveAt: Long = 0,
    val error: String? = null,
    val info: String? = null,
)

class RouteEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RouteStore(app)
    private val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(EditorState())
    val state = stateFlow

    private val undoStack = ArrayDeque<List<LatLon>>()
    private val redoStack = ArrayDeque<List<LatLon>>()
    private var checkpoints: List<LatLon> = emptyList()
    private var autosaveJob: Job? = null

    fun loadFresh() {
        undoStack.clear()
        redoStack.clear()
        // Do not clear an existing draft here: opening a new canvas must not
        // silently delete unsaved work. The draft is only replaced after the
        // user actually edits the new route.
        stateFlow.value = EditorState(mode = EditorMode.ADD)
    }

    fun loadDraft() {
        revertStacks()
        val draft = store.loadDraft()
        if (draft == null) {
            loadFresh()
            return
        }
        stateFlow.value = EditorState(
            vertices = draft.vertices,
            name = draft.name,
            note = draft.note,
            savedRouteId = draft.savedRouteId,
            restoredDraft = true,
            mode = EditorMode.ADD,
        )
        recompute()
        scheduleAutosave(immediate = false)
    }

    fun loadRoute(routeId: String) {
        revertStacks()
        val payload = store.loadPayload(routeId)
        val meta = store.listRoutes().firstOrNull { it.id == routeId }
        if (payload == null) {
            stateFlow.value = stateFlow.value.copy(error = "路线文件不存在或已损坏")
            return
        }
        store.touch(routeId)
        stateFlow.value = EditorState(
            vertices = payload.vertices,
            ring = payload.ring,
            name = meta?.name ?: "已保存路线",
            note = meta?.note ?: "",
            savedRouteId = routeId,
            mode = EditorMode.ADD,
        )
        recompute()
        scheduleAutosave(immediate = true)
    }

    fun importPayload(payload: RoutePayload, sourceName: String) {
        revertStacks()
        stateFlow.value = EditorState(
            vertices = payload.vertices,
            ring = payload.ring,
            name = sourceName.removeSuffix(".json").ifBlank { "导入路线" },
            savedRouteId = null,
            info = "已导入路线：${payload.vertices.size} 个标点，${"%.0f".format(payload.lengthM)} m",
            mode = EditorMode.ADD,
        )
        recompute()
        scheduleAutosave(immediate = true)
    }

    fun setCheckpoints(list: List<Checkpoint>) {
        checkpoints = list
            .sortedBy { it.position }
            .map { it.point }
            .filter { it.isValid() }
        val ordered = list.map { it.position }
        val requireOrder = ordered.isNotEmpty() &&
            ordered.all { it in 0 until ordered.size } &&
            ordered.sorted() == ordered.indices.toList()
        stateFlow.value = stateFlow.value.copy(
            checkpointCount = checkpoints.size,
            requireOrder = requireOrder,
        )
        recompute()
    }

    fun setMode(mode: EditorMode) {
        stateFlow.value = stateFlow.value.copy(mode = mode)
    }

    fun selectVertex(index: Int?) {
        val safe = index?.takeIf { it in stateFlow.value.vertices.indices }
        stateFlow.value = stateFlow.value.copy(selectedVertex = safe)
    }

    fun addVertex(point: LatLon) {
        if (!point.isValid()) {
            stateFlow.value = stateFlow.value.copy(error = "该位置坐标无效，无法标点")
            return
        }
        pushUndo()
        val vertices = stateFlow.value.vertices + point
        stateFlow.value = stateFlow.value.copy(
            vertices = vertices,
            selectedVertex = vertices.lastIndex,
        )
        recompute()
        scheduleAutosave()
    }

    fun moveVertex(index: Int, point: LatLon) {
        if (!point.isValid() || index !in stateFlow.value.vertices.indices) return
        val vertices = stateFlow.value.vertices.toMutableList()
        if (vertices[index] == point) return
        vertices[index] = point
        stateFlow.value = stateFlow.value.copy(vertices = vertices, selectedVertex = index)
        recompute()
        scheduleAutosave()
    }

    /** Start a drag operation so the whole drag becomes one undo step. */
    fun beginDrag() {
        pushUndo()
    }

    fun deleteVertex(index: Int) {
        if (index !in stateFlow.value.vertices.indices) return
        pushUndo()
        val vertices = stateFlow.value.vertices.toMutableList().also { it.removeAt(index) }
        stateFlow.value = stateFlow.value.copy(
            vertices = vertices,
            selectedVertex = null,
            info = "已删除一个标点，可撤销",
        )
        recompute()
        scheduleAutosave()
    }

    fun deleteSelected() {
        stateFlow.value.selectedVertex?.let { deleteVertex(it) }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(stateFlow.value.vertices)
        stateFlow.value = stateFlow.value.copy(vertices = previous, selectedVertex = null)
        recompute()
        scheduleAutosave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(stateFlow.value.vertices)
        stateFlow.value = stateFlow.value.copy(vertices = next, selectedVertex = null)
        recompute()
        scheduleAutosave()
    }

    fun clearAll() {
        if (stateFlow.value.vertices.isEmpty()) return
        pushUndo()
        stateFlow.value = stateFlow.value.copy(
            vertices = emptyList(),
            ring = emptyList(),
            analysis = null,
            selectedVertex = null,
            lengthM = 0.0,
            info = "已清空，可撤销",
        )
        scheduleAutosave()
    }

    fun dismissMessage() {
        stateFlow.value = stateFlow.value.copy(error = null, info = null)
    }

    fun saveRoute(name: String, note: String): Boolean {
        val current = stateFlow.value
        if (current.vertices.size < RouteMath.MIN_VERTICES) {
            stateFlow.value = current.copy(error = "至少需要 3 个标点才能保存路线")
            return false
        }
        val ring = current.ring.ifEmpty {
            runCatching { RouteMath.smoothClosed(current.vertices) }.getOrElse {
                stateFlow.value = current.copy(error = it.message ?: "路线平滑失败")
                return false
            }
        }
        val payload = RoutePayload(
            vertices = current.vertices,
            ring = ring,
            lengthM = RouteMath.routeLengthM(ring),
        )
        return runCatching {
            val meta = store.saveRoute(
                name = name.ifBlank { "未命名路线" },
                note = note,
                payload = payload,
                existingId = current.savedRouteId,
            )
            stateFlow.value = current.copy(
                name = meta.name,
                note = meta.note,
                savedRouteId = meta.id,
                restoredDraft = false,
                info = "已保存「${meta.name}」",
                lastAutosaveAt = System.currentTimeMillis(),
            )
            scheduleAutosave(immediate = true)
            true
        }.getOrElse {
            stateFlow.value = current.copy(error = "保存路线失败：${it.message}")
            false
        }
    }

    fun saveAsNew(name: String, note: String): Boolean {
        val current = stateFlow.value
        if (current.vertices.size < RouteMath.MIN_VERTICES) {
            stateFlow.value = current.copy(error = "至少需要 3 个标点才能保存路线")
            return false
        }
        val ring = current.ring.ifEmpty { runCatching { RouteMath.smoothClosed(current.vertices) }.getOrNull() ?: return false }
        val payload = RoutePayload(current.vertices, ring, RouteMath.routeLengthM(ring))
        return runCatching {
            val meta = store.saveRoute(name.ifBlank { "未命名路线" }, note, payload, null)
            stateFlow.value = current.copy(
                name = meta.name,
                note = meta.note,
                savedRouteId = meta.id,
                restoredDraft = false,
                info = "已另存为「${meta.name}」",
            )
            scheduleAutosave(immediate = true)
            true
        }.getOrElse {
            stateFlow.value = current.copy(error = "另存失败：${it.message}")
            false
        }
    }

    fun importText(text: String): Boolean {
        return runCatching {
            val payload = store.importText(text)
            importPayload(payload, "导入路线")
            true
        }.getOrElse {
            stateFlow.value = stateFlow.value.copy(error = it.message ?: "路线导入失败")
            false
        }
    }

    fun exportText(): String? {
        val payload = activePayload() ?: return null
        return runCatching { store.exportText(payload) }.getOrNull()
    }

    fun exportPayload(): RoutePayload? {
        val current = stateFlow.value
        if (current.ring.size < 3) return null
        return RoutePayload(
            vertices = current.vertices,
            ring = current.ring,
            lengthM = RouteMath.routeLengthM(current.ring),
        )
    }

    fun activePayload(): RoutePayload? {
        val current = stateFlow.value
        if (current.ring.size < 3 || current.vertices.size < 3) return null
        return RoutePayload(
            vertices = current.vertices,
            ring = current.ring,
            lengthM = RouteMath.routeLengthM(current.ring),
        )
    }

    // ── internals ────────────────────────────────────────────────────────
    private fun pushUndo() {
        undoStack.addLast(stateFlow.value.vertices)
        while (undoStack.size > 80) undoStack.removeFirst()
        redoStack.clear()
        stateFlow.value = stateFlow.value.copy(
            canUndo = true,
            canRedo = false,
        )
    }

    private fun revertStacks() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun recompute() {
        val vertices = stateFlow.value.vertices
        if (vertices.size < RouteMath.MIN_VERTICES) {
            stateFlow.value = stateFlow.value.copy(
                ring = emptyList(),
                analysis = null,
                lengthM = 0.0,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
            return
        }
        runCatching {
            var ring = RouteMath.smoothClosed(vertices)
            var analysis = RouteMath.analyzeRoute(
                ring,
                checkpoints.takeIf { it.isNotEmpty() },
                requireOrder = stateFlow.value.requireOrder,
            )
            if (checkpoints.isNotEmpty()) {
                val aligned = RouteMath.alignRoute(
                    ring,
                    checkpoints,
                    requireOrder = stateFlow.value.requireOrder,
                )
                ring = aligned.ring
                analysis = aligned.analysis
            }
            stateFlow.value = stateFlow.value.copy(
                ring = ring,
                analysis = analysis,
                lengthM = RouteMath.routeLengthM(ring),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                error = null,
            )
        }.onFailure { error ->
            stateFlow.value = stateFlow.value.copy(
                ring = emptyList(),
                analysis = null,
                lengthM = 0.0,
                error = error.message ?: "路线平滑失败",
            )
        }
    }

    private fun scheduleAutosave(immediate: Boolean = false) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            if (!immediate) delay(500)
            val current = stateFlow.value
            if (current.vertices.isEmpty()) {
                withContext(Dispatchers.IO) { store.clearDraft() }
                return@launch
            }
            val draft = RouteStore.Draft(
                vertices = current.vertices,
                ring = current.ring,
                name = current.name,
                note = current.note,
                updatedAt = System.currentTimeMillis(),
                savedRouteId = current.savedRouteId,
            )
            withContext(Dispatchers.IO) {
                runCatching { store.saveDraft(draft) }
            }
            stateFlow.value = stateFlow.value.copy(lastAutosaveAt = System.currentTimeMillis())
        }
    }
}
