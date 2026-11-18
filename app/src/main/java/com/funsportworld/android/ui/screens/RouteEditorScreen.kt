package com.funsportworld.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.funsportworld.android.data.LatLon
import com.funsportworld.android.data.RoutePayload
import com.funsportworld.android.map.AmapMap
import com.funsportworld.android.map.MapCamera
import com.funsportworld.android.map.MapViewport
import com.funsportworld.android.map.fitCamera
import com.funsportworld.android.ui.Format
import com.funsportworld.android.ui.components.Chip
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.ui.theme.BrandGreen
import com.funsportworld.android.ui.theme.BrandRed
import com.funsportworld.android.viewmodel.AppViewModel
import com.funsportworld.android.viewmodel.EditorMode
import com.funsportworld.android.viewmodel.RouteEditorViewModel

/**
 * Touch-first manual route editor.
 *
 * Android interactions (no mouse):
 *  * tap empty map = add a vertex while in "加点" mode
 *  * drag a blue vertex = adjust it (one undo step per drag)
 *  * long-press a blue vertex = delete it (undo available)
 *  * pinch = zoom, one-finger drag = pan
 *  * every edit is auto-saved as a draft; named routes live in the route library
 */
@Composable
fun RouteEditorScreen(
    appVm: AppViewModel,
    initialRouteId: String?,
    startFresh: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    editorVm: RouteEditorViewModel = viewModel(),
) {
    val editor by editorVm.state.collectAsState()
    val appState by appVm.state.collectAsState()
    val context = LocalContext.current

    var camera by remember { mutableStateOf(MapCamera(LatLon(30.6598, 104.0633), 16.0)) }
    var viewport by remember { mutableStateOf(MapViewport(0f, 0f)) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveAsNew by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var draggingVertex by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }

    LaunchedEffect(initialRouteId, startFresh) {
        when {
            startFresh -> editorVm.loadFresh()
            initialRouteId != null -> editorVm.loadRoute(initialRouteId)
            else -> editorVm.loadDraft()
        }
        // A different draft/route was loaded: fit the view to it again.
        fitted = false
    }
    LaunchedEffect(appState.checkpoints) {
        editorVm.setCheckpoints(appState.checkpoints)
    }

    val allPoints = remember(editor.vertices, appState.checkpoints) {
        editor.vertices + appState.checkpoints.map { it.point }
    }
    LaunchedEffect(editor.vertices, viewport.valid) {
        if (!viewport.valid) return@LaunchedEffect
        if (editor.vertices.isEmpty()) {
            val anchorLat = appState.settings?.anchorLat?.toDoubleOrNull()
            val anchorLon = appState.settings?.anchorLon?.toDoubleOrNull()
            if (anchorLat != null && anchorLon != null) {
                camera = MapCamera(LatLon(anchorLat, anchorLon), 16.5)
            }
            // Do not mark as fitted: a draft or route may still be loading and
            // must get a chance to fit the camera to its vertices.
            fitted = false
            return@LaunchedEffect
        }
        if (!fitted) {
            fitCamera(allPoints, viewport)?.let {
                camera = it
                fitted = true
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val text = editorVm.exportText()
            if (text == null) {
                editorVm.dismissMessage()
            } else {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: ""
                if (text.isNotBlank()) editorVm.importText(text)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AmapMap(
            camera = camera,
            onCameraChange = { camera = it },
            checkpoints = appState.checkpoints,
            sketchVertices = editor.vertices,
            sketchRing = editor.ring,
            selectedVertex = editor.selectedVertex,
            vertexDragEnabled = true,
            onViewportChanged = { viewport = it },
            onTap = { point ->
                if (editor.mode == EditorMode.ADD) {
                    editorVm.addVertex(point)
                } else {
                    editorVm.selectVertex(null)
                }
            },
            onTapVertex = { index -> editorVm.selectVertex(index) },
            onLongPressVertex = { index -> editorVm.deleteVertex(index) },
            onVertexDrag = { index, point ->
                if (!draggingVertex) {
                    draggingVertex = true
                    editorVm.beginDrag()
                }
                editorVm.moveVertex(index, point)
            },
            onVertexDragEnd = { draggingVertex = false },
        )

        // Top bar overlay.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = editor.name.ifBlank { "手绘路线" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${editor.vertices.size} 个标点 · " + Format.distance(editor.lengthM) +
                            (if (editor.savedRouteId != null) " · 已保存" else " · 草稿"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showSaveDialog = true; saveAsNew = false }) {
                    Icon(Icons.Filled.Save, contentDescription = "保存路线")
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("另存为新路线") }, onClick = {
                            showMenu = false
                            saveAsNew = true
                            showSaveDialog = true
                        })
                        DropdownMenuItem(text = { Text("导入 JSON 路线") }, onClick = {
                            showMenu = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        })
                        DropdownMenuItem(text = { Text("导出 JSON 路线") }, onClick = {
                            showMenu = false
                            exportLauncher.launch("route_${Format.fileStamp()}.json")
                        })
                        DropdownMenuItem(text = { Text("分享路线 JSON") }, onClick = {
                            showMenu = false
                            val text = editorVm.exportText()
                            if (text != null) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "FunSportWorld 手绘路线")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "分享路线"))
                            }
                        })
                        DropdownMenuItem(text = { Text("清空标点") }, onClick = {
                            showMenu = false
                            showClearDialog = true
                        })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                fitCamera(allPoints, viewport)?.let { camera = it }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 300.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "适应路线")
        }

        // Bottom editing panel.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("${editor.vertices.size} 点", Tone.INFO)
                    Spacer(Modifier.width(6.dp))
                    StatusPill(Format.distance(editor.lengthM), Tone.OK)
                    Spacer(Modifier.width(6.dp))
                    val tone = if (editor.analysis?.problems?.isNotEmpty() == true) Tone.WARN else Tone.OK
                    val text = when {
                        editor.vertices.size < 3 -> "待标点"
                        editor.ring.isEmpty() -> "待平滑"
                        editor.analysis?.problems?.isNotEmpty() == true -> "有提示"
                        else -> "路线就绪"
                    }
                    StatusPill(text, tone)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (editor.lastAutosaveAt > 0) "已自动保存草稿" else "草稿",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip("加点", selected = editor.mode == EditorMode.ADD, onClick = { editorVm.setMode(EditorMode.ADD) })
                    Chip("调整", selected = editor.mode == EditorMode.MOVE, onClick = { editorVm.setMode(EditorMode.MOVE) })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editorVm.undo() }, enabled = editor.canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "撤销")
                    }
                    IconButton(onClick = { editorVm.redo() }, enabled = editor.canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "重做")
                    }
                    IconButton(onClick = { editorVm.deleteSelected() }, enabled = editor.selectedVertex != null) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除选中点")
                    }
                }
                Text(
                    text = when {
                        editor.vertices.isEmpty() -> "点击地图开始标点：至少 3 个点自动闭合平滑；拖动蓝点微调，长按蓝点删除。"
                        editor.mode == EditorMode.ADD -> "继续点击地图加点；在蓝点上长按可删除，拖动蓝点可微调。"
                        else -> "拖动蓝点调整位置；长按蓝点删除；点击空白处取消选择。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (editor.analysis?.let { it.checkpoints.isNotEmpty() } == true) {
                    Spacer(Modifier.height(6.dp))
                    val analysis = editor.analysis!!
                    val nearest = analysis.checkpoints.minOfOrNull { it.nearestM } ?: 0.0
                    val order = when (analysis.orderOk) {
                        true -> "顺序一致"
                        false -> "顺序异常"
                        null -> "未检查顺序"
                    }
                    Text(
                        text = "打卡点：最近 " + String.format(java.util.Locale.US, "%.1f", nearest) +
                            " m · " + order + " · " + analysis.problems.size + " 条提示",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (analysis.problems.isEmpty()) BrandGreen else BrandRed,
                    )
                    analysis.problems.take(2).forEach { problem ->
                        Text(
                            text = "· $problem",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandRed,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (editor.error != null || editor.info != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = editor.error ?: editor.info ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (editor.error != null) BrandRed else BrandGreen,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showSaveDialog = true; saveAsNew = editor.savedRouteId == null },
                        enabled = editor.vertices.size >= 3,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (editor.savedRouteId == null) "保存路线" else "保存修改")
                    }
                    Button(
                        onClick = {
                            val payload = editorVm.activePayload()
                            if (payload == null) {
                                editorVm.dismissMessage()
                            } else {
                                appVm.setActiveRoute(payload, editor.name.ifBlank { "手绘路线" })
                                appVm.refreshRoutes()
                                appVm.refreshDraft()
                                onBack()
                            }
                        },
                        enabled = editor.vertices.size >= 3 && editor.ring.size >= 3,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("应用到方案")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showSaveDialog) {
        val dialogTitle = when {
            editor.savedRouteId == null -> "保存路线"
            saveAsNew -> "另存为新路线"
            else -> "保存路线"
        }
        RouteNameDialog(
            title = dialogTitle,
            initialName = editor.name.ifBlank { "校园路线" },
            initialNote = editor.note,
            saveAsNew = saveAsNew,
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, note ->
                val ok = if (saveAsNew) editorVm.saveAsNew(name, note) else editorVm.saveRoute(name, note)
                if (ok) {
                    appVm.refreshRoutes()
                    appVm.refreshDraft()
                }
                showSaveDialog = false
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空标点？") },
            text = { Text("将删除当前全部标点（可撤销一次），已保存的路线不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    editorVm.clearAll()
                    showClearDialog = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RouteNameDialog(
    title: String,
    initialName: String,
    initialNote: String,
    saveAsNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("路线名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "保存后可在【路线】页随时应用到方案；每次编辑都会自动保存草稿。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), note.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Suppress("unused")
private fun payloadLength(payload: RoutePayload): Double = payload.lengthM
