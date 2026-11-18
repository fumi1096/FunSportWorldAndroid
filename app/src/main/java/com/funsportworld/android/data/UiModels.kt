package com.funsportworld.android.data

import org.json.JSONObject

data class UiMessage(val id: Long, val text: String, val isError: Boolean)

enum class StartTimeMode { CONFIG, BEFORE, AT }

/** Parameters collected by the plan sheet before generating a run plan. */
data class PlanParams(
    val auto: Boolean = true,
    val distKm: Double = 2.0,
    val paceSecPerKm: Double = 420.0,
    val cadence: Double = 160.0,
    val seed: Long = 0,
    val startMode: StartTimeMode = StartTimeMode.CONFIG,
    val beforeMinutes: Int = 60,
    val startAtMs: Long? = null,
    val useSketch: Boolean = false,
    val route: RoutePayload? = null,
    val allowOutside: Boolean = false,
) {
    fun toBridgeJson(): JSONObject = JSONObject().apply {
        put("auto", auto)
        if (!auto) {
            put("dist", distKm)
            put("pace", paceSecPerKm)
            put("cadence", cadence)
        }
        put("seed", seed)
        put("allow_outside", allowOutside)
        when (startMode) {
            StartTimeMode.BEFORE -> put("before", beforeMinutes)
            StartTimeMode.AT -> startAtMs?.let { put("start_ms", it) }
            StartTimeMode.CONFIG -> Unit
        }
        if (useSketch && route != null) put("route", route.toJson())
    }
}

data class RecordRow(
    val rrid: Long,
    val totalDis: Double,
    val totalTime: Int,
    val startTime: Long,
    val complete: Boolean?,
    val avgStepFreq: Int,
    val calorie: Int,
    val totalSteps: Int,
)

data class SubmitResult(
    val rrid: Long,
    val uuid: String,
    val obsOk: Int,
    val detailOk: Boolean,
    val dist: Double,
    val dur: Int,
    val steps: Int,
    val cadence: Double,
    val checkpointPassed: Int?,
    val checkpointCount: Int?,
)

data class AppUiState(
    val pythonReady: Boolean = false,
    val startupError: String? = null,
    val settings: AppSettings? = null,
    val checkpoints: List<Checkpoint> = emptyList(),
    val checkpointsAt: Long = 0,
    val plan: PlanUi? = null,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val logs: List<String> = emptyList(),
    val message: UiMessage? = null,
    val routes: List<RouteMeta> = emptyList(),
    val draft: RouteStore.Draft? = null,
    val records: List<RecordRow> = emptyList(),
    val recordsLoading: Boolean = false,
    val submission: SubmitResult? = null,
    val activeRoute: RoutePayload? = null,
    val activeRouteLabel: String = "",
)
