package com.funsportworld.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.funsportworld.android.data.RouteMeta
import com.funsportworld.android.ui.Format
import com.funsportworld.android.ui.components.AppTopBar
import com.funsportworld.android.ui.components.EmptyState
import com.funsportworld.android.ui.components.SectionCard
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RoutesScreen(
    vm: AppViewModel,
    onOpenEditor: (routeId: String?, startFresh: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<RouteMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<RouteMeta?>(null) }
    var pendingExportText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshRoutes(); vm.refreshDraft() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri != null && text != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: ""
                    vm.routeStore.importText(text)
                }.getOrNull()
            }
            if (payload != null) {
                withContext(Dispatchers.IO) {
                    vm.routeStore.saveRoute(
                        name = "导入路线 ${Format.fileStamp()}",
                        note = "从 JSON 文件导入",
                        payload = payload,
                    )
                }
                vm.refreshRoutes()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(
            title = "路线",
            subtitle = "手绘路线库 · ${state.routes.size} 条",
            actions = {
                IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "导入路线")
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(
                    title = "新建与导入",
                    subtitle = "点击地图标点，拖动微调，长按删除；自动平滑并保存草稿",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenEditor(null, true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("新建路线")
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("导入 JSON")
                        }
                    }
                }
            }

            state.draft?.let { draft ->
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill("草稿", Tone.WARN)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = draft.name.ifBlank { "未命名手绘草稿" } +
                                        " · " + draft.vertices.size + " 点",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = Format.relative(draft.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onOpenEditor(null, false) },
                                    shape = RoundedCornerShape(10.dp),
                                ) { Text("继续编辑") }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) { vm.routeStore.clearDraft() }
                                    vm.refreshDraft()
                                }) { Text("丢弃草稿") }
                            }
                        }
                    }
                }
            }

            if (state.routes.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Route,
                        title = "还没有保存的路线",
                        description = "画一条覆盖校园打卡点的闭合路线，保存后即可在生成方案时反复使用，也可以导出 JSON 分享。",
                        actionLabel = "开始手绘",
                        onAction = { onOpenEditor(null, true) },
                    )
                }
            } else {
                items(state.routes, key = { it.id }) { route ->
                    RouteCard(
                        route = route,
                        active = state.activeRouteLabel == route.name || state.activeRoute != null && state.activeRouteLabel == route.name,
                        onApply = {
                            scope.launch {
                                val payload = withContext(Dispatchers.IO) { vm.routeStore.loadPayload(route.id) }
                                if (payload != null) {
                                    vm.routeStore.touch(route.id)
                                    vm.setActiveRoute(payload, route.name)
                                    vm.refreshRoutes()
                                }
                            }
                        },
                        onEdit = { onOpenEditor(route.id, false) },
                        onRename = { renameTarget = route },
                        onDuplicate = {
                            scope.launch {
                                withContext(Dispatchers.IO) { vm.routeStore.duplicateRoute(route.id, route.name + " 副本") }
                                vm.refreshRoutes()
                            }
                        },
                        onDefault = {
                            scope.launch {
                                withContext(Dispatchers.IO) { vm.routeStore.setDefault(route.id) }
                                vm.refreshRoutes()
                            }
                        },
                        onExport = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    val payload = vm.routeStore.loadPayload(route.id) ?: return@withContext null
                                    vm.routeStore.exportText(payload)
                                }
                                if (text != null) {
                                    pendingExportText = text
                                    exportLauncher.launch("${route.name.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]"), "_")}.json")
                                }
                            }
                        },
                        onShare = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    val payload = vm.routeStore.loadPayload(route.id) ?: return@withContext null
                                    vm.routeStore.exportText(payload)
                                }
                                if (text != null) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, route.name)
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "分享路线"))
                                }
                            }
                        },
                        onDelete = { deleteTarget = route },
                    )
                }
            }
        }
    }

    renameTarget?.let { route ->
        var name by remember(route.id) { mutableStateOf(route.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名路线") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("路线名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { vm.routeStore.renameRoute(route.id, name) }
                        vm.refreshRoutes()
                    }
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { route ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除路线？") },
            text = { Text("将从本机路线库删除「${route.name}」，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { vm.routeStore.deleteRoute(route.id) }
                        vm.refreshRoutes()
                        if (vm.state.value.activeRouteLabel == route.name) vm.clearActiveRoute()
                    }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun RouteCard(
    route: RouteMeta,
    active: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDefault: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (route.isDefault) StatusPill("默认", Tone.OK)
                if (active) {
                    Spacer(Modifier.width(4.dp))
                    StatusPill("使用中", Tone.INFO)
                }
                IconButton(onClick = { menu = true }, modifier = Modifier) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("设为默认") }, onClick = { menu = false; onDefault() })
                    DropdownMenuItem(text = { Text("导出 JSON") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("分享") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
            if (route.note.isNotBlank()) {
                Text(
                    text = route.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Format.distance(route.lengthM) + " · " + route.vertexCount + " 个标点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "更新于 " + Format.relative(route.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("应用到方案")
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(10.dp)) { Text("编辑") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
