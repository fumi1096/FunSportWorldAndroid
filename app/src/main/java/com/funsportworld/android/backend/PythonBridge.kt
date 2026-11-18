package com.funsportworld.android.backend

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

/**
 * Thin JSON-only bridge to the reused FunSportWorld Python core.
 *
 * The Python module is started once per process. Every call returns a JSON
 * object; failures from Python are converted into [BackendException] so the UI
 * can show the original message (for example a server business error).
 */
class PythonBridge(context: Context, private val appVersion: String) {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var module: PyObject? = null

    @Volatile
    private var initialized = false

    val dataDir: File get() = File(appContext.filesDir, "funsport")

    /** Start Python and initialise the data directory (idempotent). */
    fun start(): JSONObject = synchronized(lock) {
        if (initialized) {
            return@synchronized JSONObject().put("ok", true)
        }
        val mod = try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            Python.getInstance().getModule("android_backend")
        } catch (t: Throwable) {
            throw BackendException("Python 运行时启动失败：${t.message}", t.javaClass.simpleName)
        }
        val response = try {
            JSONObject(mod.callAttr("init", dataDir.absolutePath, appVersion).toString())
        } catch (t: Throwable) {
            throw BackendException("Python 初始化失败：${t.message ?: t.javaClass.simpleName}", t.javaClass.simpleName)
        }
        if (!response.optBoolean("ok")) {
            throw BackendException(response.optString("error", "Python 初始化失败"), response.optString("error_type"))
        }
        module = mod
        initialized = true
        response
    }

    fun settingsSnapshot(): JSONObject = call("state").optJSONObject("result") ?: JSONObject()

    fun saveSettings(values: JSONObject): JSONObject =
        call("save_settings", values.toString())

    fun login(username: String, password: String): JSONObject =
        call("login", username, password)

    fun saveCredentials(username: String, password: String, remember: Boolean): JSONObject =
        call("save_credentials", username, password, remember)

    fun logout(): JSONObject = call("logout")

    fun switchAccount(username: String): JSONObject = call("switch_account", username)

    fun removeAccount(username: String): JSONObject = call("remove_account", username)

    fun fetchPolicy(): JSONObject = result(call("fetch_policy"))

    fun fetchPoints(fresh: Boolean): JSONObject = result(call("fetch_points", fresh))

    fun preparePlan(params: JSONObject): JSONObject = result(call("prepare_plan", params.toString()))

    fun previewPlan(params: JSONObject): JSONObject = result(call("preview_plan", params.toString()))

    fun submitPlan(planId: String, allowOutside: Boolean): JSONObject =
        result(call("submit_plan", planId, allowOutside))

    fun cancelPlan(planId: String) {
        call("cancel_plan", planId)
    }

    fun fetchRecords(): JSONObject {
        val obj = call("fetch_records")
        return obj.optJSONObject("result") ?: JSONObject()
    }

    fun pollLogs(since: Long): JSONObject = call("poll_logs", since)

    fun state(): JSONObject = settingsSnapshot()

    private fun result(obj: JSONObject): JSONObject = obj.optJSONObject("result") ?: obj

    private fun call(function: String, vararg args: Any?): JSONObject {
        val mod = module ?: throw BackendException("Python 后端尚未启动", "NotStarted")
        val response = try {
            JSONObject(mod.callAttr(function, *args).toString())
        } catch (t: Throwable) {
            throw BackendException(
                "Python 调用 $function 失败：${t.message ?: t.javaClass.simpleName}",
                t.javaClass.simpleName,
            )
        }
        if (!response.optBoolean("ok")) {
            throw BackendException(
                response.optString("error", "未知错误"),
                response.optString("error_type", "Error"),
            )
        }
        return response
    }
}

class BackendException(message: String, val type: String = "Error") : RuntimeException(message)
