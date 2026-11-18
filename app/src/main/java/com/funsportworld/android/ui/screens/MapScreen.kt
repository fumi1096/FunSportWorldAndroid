package com.funsportworld.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.funsportworld.android.data.Checkpoint
import com.funsportworld.android.data.LatLon
import com.funsportworld.android.data.PlanUi
import com.funsportworld.android.map.AmapMap
import com.funsportworld.android.map.MapCamera
import com.funsportworld.android.map.MapViewport
import com.funsportworld.android.map.fitCamera
import com.funsportworld.android.ui.Format
import com.funsportworld.android.ui.components.Chip
import com.funsportworld.android.ui.components.LogPanel
import com.funsportworld.android.ui.components.PrimaryAction
import com.funsportworld.android.ui.components.SecondaryAction
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.viewmodel.AppViewModel

@Composable
fun MapScreen(
    vm: AppViewModel,
    onOpenPlan: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var viewport by remember { mutableStateOf(MapViewport(0f, 0f)) }
    var camera by remember {
        mutableStateOf(MapCamera(LatLon(30.6598, 104.0633), 15.5))
    }
    var fittedKey by remember { mutableStateOf<String?>(null) }
    var selectedCheckpoint by remember { mutableStateOf<Checkpoint?>(null) }
    var tileCount by remember { mutableStateOf(0) }
    var baseMapFailed by remember { mutableStateOf(false) }
    LaunchedEffect(tileCount) {
        if (tileCount == 0) {
            kotlinx.coroutines.delay(7000)
            if (tileCount == 0) baseMapFailed = true
        } else {
            baseMapFailed = false
        }
    }

    val focusPoints: List<LatLon> = remember(state.checkpoints, state.plan, state.activeRoute) {
        buildList {
            addAll(state.checkpoints.map { it.point })
            state.plan?.trackPreview?.let { addAll(it) }
            state.activeRoute?.ring?.let { addAll(it) }
        }
    }
    val fitKey = remember(
        focusPoints,
        state.plan?.planId,
        state.activeRouteLabel,
        state.settings?.anchorLat,
        state.settings?.anchorLon,
    ) {
        "${focusPoints.size}:${state.plan?.planId ?: ""}:${state.activeRouteLabel}:" +
            "${focusPoints.firstOrNull()}:${state.settings?.anchorLat}:${state.settings?.anchorLon}"
    }
    LaunchedEffect(fitKey, viewport.valid) {
        if (!viewport.valid) return@LaunchedEffect
        if (fittedKey == fitKey) return@LaunchedEffect
        val fitted = fitCamera(focusPoints, viewport)
        if (fitted != null) {
            camera = fitted
            fittedKey = fitKey
        } else {
            val lat = state.settings?.anchorLat?.toDoubleOrNull()
            val lon = state.settings?.anchorLon?.toDoubleOrNull()
            if (lat != null && lon != null) {
                camera = MapCamera(LatLon(lat, lon), 15.5)
                fittedKey = fitKey
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AmapMap(
            camera = camera,
            onCameraChange = { camera = it },
            checkpoints = state.checkpoints,
            planTrack = state.plan?.trackPreview ?: emptyList(),
            sketchRing = state.activeRoute?.ring ?: emptyList(),
            showPlanStartEnd = state.plan != null,
            selectedCheckpoint = selectedCheckpoint?.index,
            onViewportChanged = { viewport = it },
            onTileCountChanged = { tileCount = it },
            onTap = { point ->
                selectedCheckpoint = nearestCheckpoint(state.checkpoints, point)
            },
        )
        if (baseMapFailed) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("底图暂时加载失败", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "高德瓦片需要网络连接。手绘路线、打卡点和轨迹仍可正常使用；请检查网络后重进本页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "FunSportWorld",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusPill(
                            text = if (state.settings?.loggedIn == true) {
                                state.settings?.sessionName?.ifBlank { "已登录" } ?: "已登录"
                            } else "未登录",
                            tone = if (state.settings?.loggedIn == true) Tone.OK else Tone.WARN,
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.pythonReady) {
                            StatusPill(
                                text = if (state.checkpoints.isEmpty()) "无点位" else "${state.checkpoints.size} 个点位",
                                tone = if (state.checkpoints.isEmpty()) Tone.INFO else Tone.OK,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.fetchPoints(true) },
                            enabled = !state.busy && state.settings?.loggedIn == true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("读取点位", maxLines = 1)
                        }
                        Button(
                            onClick = onOpenEditor,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("手绘路线", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenPlan,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("生成方案", maxLines = 1)
                    }
                    if (state.busy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.busyLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LogPanel(state.logs)
                    }
                }
            }

            if (state.startupError != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = "Python 后端启动失败：" + state.startupError,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (state.settings?.loggedIn != true) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "登录后可以读取校园打卡点并生成方案",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenLogin, shape = RoundedCornerShape(10.dp)) {
                            Text("去登录")
                        }
                    }
                }
            }

            if (selectedCheckpoint != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "打卡点 ${selectedCheckpoint!!.index + 1}" +
                                (selectedCheckpoint!!.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "坐标 ${String.format(java.util.Locale.US, "%.6f, %.6f", selectedCheckpoint!!.lat, selectedCheckpoint!!.lon)}" +
                                " · 序号 ${selectedCheckpoint!!.position}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.activeRoute != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusPill("当前路线", Tone.INFO)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.activeRouteLabel + " · " + Format.distance(state.activeRoute!!.lengthM),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chip("路线库", selected = false, onClick = onOpenRoutes)
                    }
                }
            }
            state.plan?.let { plan -> PlanSummaryBar(plan = plan, onOpen = onOpenPlan) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = if (state.plan != null) 156.dp else 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(
                onClick = {
                    val fitted = fitCamera(focusPoints, viewport)
                    if (fitted != null) camera = fitted
                },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "定位到校园")
            }
        }
    }
}

@Composable
private fun PlanSummaryBar(plan: PlanUi, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = when (plan.provider) {
                        "user_sketch" -> "手绘方案"
                        "amap_walking" -> "高德方案"
                        else -> "方案"
                    },
                    tone = Tone.INFO,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Format.distance(plan.totalDistance) + " · " + Format.duration(plan.totalTime) +
                        " · " + Format.pace(plan.paceSecPerKm),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(
                    text = if (plan.windowOk) "时段内" else "时段外",
                    tone = if (plan.windowOk) Tone.OK else Tone.WARN,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (plan.previewOnly) "轨迹预览，不可提交" else
                        "本地模拟打卡 ${plan.passedCount ?: 0}/${plan.checkpoints.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpen, shape = RoundedCornerShape(10.dp)) {
                    Text("查看方案")
                }
            }
        }
    }
}

private fun nearestCheckpoint(checkpoints: List<Checkpoint>, point: LatLon): Checkpoint? {
    var best: Checkpoint? = null
    var bestDistance = 120.0
    checkpoints.forEach { checkpoint ->
        val distance = haversineMetersSafe(checkpoint.point, point)
        if (distance < bestDistance) {
            bestDistance = distance
            best = checkpoint
        }
    }
    return best
}

private fun haversineMetersSafe(a: LatLon, b: LatLon): Double =
    com.funsportworld.android.data.haversineMeters(a, b)
