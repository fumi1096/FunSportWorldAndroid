package com.funsportworld.android.data

import android.content.Context
import com.funsportworld.android.route.RouteJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent route library and editor draft.
 *
 * Android-friendly behaviour:
 *  * every editor change is auto-saved as a draft (survives process death),
 *  * named routes are first-class records that can be renamed, duplicated,
 *    reordered by last use and set as the default,
 *  * payloads keep the desktop app's JSON schema so files can move both ways.
 */
class RouteStore(context: Context) {

    private val root = File(context.filesDir, "routes").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val draftFile = File(root, "draft.json")
    private val lock = Any()

    data class Draft(
        val vertices: List<LatLon>,
        val ring: List<LatLon>,
        val name: String,
        val note: String,
        val updatedAt: Long,
        val savedRouteId: String?,
    )

    fun listRoutes(): List<RouteMeta> = synchronized(lock) {
        readIndex().let { index ->
            index.routes.sortedWith(
                compareByDescending<RouteMeta> { it.isDefault }
                    .thenByDescending { it.lastUsedAt }
                    .thenByDescending { it.updatedAt }
            )
        }
    }

    fun defaultRouteId(): String? = synchronized(lock) {
        readIndex().routes.firstOrNull { it.isDefault }?.id
    }

    fun loadPayload(routeId: String): RoutePayload? = synchronized(lock) {
        val file = File(root, "$routeId.json")
        if (!file.isFile) return null
        try {
            RoutePayload.fromJson(JSONObject(file.readText()))
        } catch (t: Throwable) {
            null
        }
    }

    fun saveRoute(name: String, note: String, payload: RoutePayload, existingId: String? = null): RouteMeta =
        synchronized(lock) {
            val index = readIndex()
            val now = System.currentTimeMillis()
            val existing = existingId?.let { id -> index.routes.firstOrNull { it.id == id } }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val meta = RouteMeta(
                id = id,
                name = name.trim().ifEmpty { "未命名路线" },
                note = note.trim(),
                lengthM = payload.lengthM,
                vertexCount = payload.vertices.size,
                checkpointCount = existing?.checkpointCount,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastUsedAt = now,
                isDefault = existing?.isDefault ?: index.routes.isEmpty(),
            )
            writeAtomically(File(root, "$id.json"), payload.toJson().toString())
            val routes = index.routes.filterNot { it.id == id } + meta
            val defaultId = when {
                meta.isDefault -> id
                index.defaultId == id -> null
                else -> index.defaultId
            }
            writeIndex(Index(routes, defaultId))
            meta
        }

    fun deleteRoute(routeId: String) = synchronized(lock) {
        val index = readIndex()
        val routes = index.routes.filterNot { it.id == routeId }
        File(root, "$routeId.json").delete()
        var defaultId = index.defaultId
        if (defaultId == routeId) defaultId = routes.firstOrNull()?.id
        writeIndex(Index(routes, defaultId))
        Unit
    }

    fun renameRoute(routeId: String, newName: String): RouteMeta? = synchronized(lock) {
        val index = readIndex()
        val meta = index.routes.firstOrNull { it.id == routeId } ?: return null
        val updated = meta.copy(name = newName.trim().ifEmpty { meta.name }, updatedAt = System.currentTimeMillis())
        writeIndex(Index(index.routes.map { if (it.id == routeId) updated else it }, index.defaultId))
        updated
    }

    fun duplicateRoute(routeId: String, newName: String): RouteMeta? = synchronized(lock) {
        val payload = loadPayload(routeId) ?: return null
        val meta = readIndex().routes.firstOrNull { it.id == routeId } ?: return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val copy = meta.copy(
            id = id,
            name = newName.trim().ifEmpty { meta.name + " 副本" },
            createdAt = now,
            updatedAt = now,
            lastUsedAt = now,
            isDefault = false,
        )
        writeAtomically(File(root, "$id.json"), payload.toJson().toString())
        writeIndex(Index(readIndex().routes + copy, readIndex().defaultId))
        copy
    }

    fun setDefault(routeId: String) = synchronized(lock) {
        val index = readIndex()
        writeIndex(Index(index.routes.map { it.copy(isDefault = it.id == routeId) }, routeId))
        Unit
    }

    fun touch(routeId: String) = synchronized(lock) {
        val index = readIndex()
        val now = System.currentTimeMillis()
        writeIndex(Index(index.routes.map { if (it.id == routeId) it.copy(lastUsedAt = now) else it }, index.defaultId))
        Unit
    }

    fun clearDefaultIfMissing() = synchronized(lock) {
        val index = readIndex()
        if (index.defaultId != null && index.routes.none { it.id == index.defaultId }) {
            writeIndex(Index(index.routes, index.routes.firstOrNull()?.id))
        }
        Unit
    }

    // ── draft ────────────────────────────────────────────────────────────
    fun saveDraft(draft: Draft) = synchronized(lock) {
        val obj = JSONObject()
            .put("vertices", LatLon.toJsonArray(draft.vertices))
            .put("ring", LatLon.toJsonArray(draft.ring))
            .put("name", draft.name)
            .put("note", draft.note)
            .put("updated_at", draft.updatedAt)
        draft.savedRouteId?.let { obj.put("saved_route_id", it) }
        writeAtomically(draftFile, obj.toString())
        Unit
    }

    fun loadDraft(): Draft? = synchronized(lock) {
        if (!draftFile.isFile) return null
        try {
            val obj = JSONObject(draftFile.readText())
            val vertices = LatLon.listFromJson(obj.optJSONArray("vertices"))
            val ring = LatLon.listFromJson(obj.optJSONArray("ring"))
            if (vertices.size < 3) return null
            Draft(
                vertices = vertices,
                ring = ring,
                name = obj.optString("name", ""),
                note = obj.optString("note", ""),
                updatedAt = obj.optLong("updated_at"),
                savedRouteId = obj.optString("saved_route_id").ifBlank { null },
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun clearDraft() = synchronized(lock) {
        if (draftFile.exists()) draftFile.delete()
        Unit
    }

    // ── import / export ──────────────────────────────────────────────────
    fun importText(text: String): RoutePayload = RouteJson.parse(text)

    fun exportText(payload: RoutePayload): String = RouteJson.export(payload)

    // ── internals ────────────────────────────────────────────────────────
    private data class Index(val routes: List<RouteMeta>, val defaultId: String?)

    private fun readIndex(): Index {
        if (!indexFile.isFile) return Index(emptyList(), null)
        return try {
            val obj = JSONObject(indexFile.readText())
            val array = obj.optJSONArray("routes") ?: JSONArray()
            val routes = ArrayList<RouteMeta>(array.length())
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { routes.add(RouteMeta.fromJson(it)) }
            }
            Index(routes, obj.optString("default_id").ifBlank { null })
        } catch (t: Throwable) {
            Index(emptyList(), null)
        }
    }

    private fun writeIndex(index: Index) {
        val array = JSONArray()
        index.routes.forEach { array.put(it.toJson()) }
        val obj = JSONObject()
            .put("version", 1)
            .put("routes", array)
        index.defaultId?.let { obj.put("default_id", it) }
        writeAtomically(indexFile, obj.toString())
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }
}
