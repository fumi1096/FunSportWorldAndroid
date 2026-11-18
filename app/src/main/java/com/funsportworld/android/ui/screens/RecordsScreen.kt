package com.funsportworld.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.funsportworld.android.data.RecordRow
import com.funsportworld.android.ui.Format
import com.funsportworld.android.ui.components.AppTopBar
import com.funsportworld.android.ui.components.EmptyState
import com.funsportworld.android.ui.components.InfoRow
import com.funsportworld.android.ui.components.RowSpace
import com.funsportworld.android.ui.components.SectionCard
import com.funsportworld.android.ui.components.StatTile
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.TextAction
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.viewmodel.AppViewModel

/**
 * 跑步记录页：读取服务器上的历史记录。
 *
 * 未登录时给出“请先登录”的空态；进入页面且已登录时自动读取一次，
 * 顶部“刷新”始终可以手动重新拉取。
 */
@Composable
fun RecordsScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val loggedIn = state.settings?.loggedIn == true
    val loading = state.recordsLoading
    val records = state.records

    var errorText by remember { mutableStateOf<String?>(null) }
    val messageBaseline = remember { System.nanoTime() }

    // 只提示进入本页之后产生的错误消息，避免重复弹上一页的旧提示。
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        if (message.id >= messageBaseline && message.isError) errorText = message.text
    }

    // 已登录且还没有数据时自动读取一次；失败会走上面的错误提示。
    LaunchedEffect(loggedIn, state.pythonReady) {
        if (loggedIn && state.pythonReady && records.isEmpty() && !loading) vm.fetchRecords()
    }

    // 新一轮读取开始时收起旧的错误条。
    LaunchedEffect(loading) {
        if (loading) errorText = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppTopBar(
            title = "跑步记录",
            subtitle = when {
                !loggedIn -> "未登录"
                records.isEmpty() -> "尚未读取到记录"
                else -> "共 ${records.size} 条记录"
            },
            actions = {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextAction(
                    text = "刷新",
                    onClick = { vm.fetchRecords() },
                    enabled = loggedIn && !loading && !state.busy,
                )
            },
        )
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val currentError = errorText
            if (currentError != null) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RecordsBanner(
                        text = currentError,
                        onDismiss = { errorText = null },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !loggedIn -> EmptyState(
                        title = "请先登录",
                        description = "登录账号后才能从服务器读取跑步记录。",
                        icon = Icons.Filled.Lock,
                    )

                    records.isEmpty() && loading -> CircularProgressIndicator()

                    records.isEmpty() -> EmptyState(
                        title = "还没有跑步记录",
                        description = "完成一次跑步提交后，记录会显示在这里；也可以点击下方按钮重新读取。",
                        actionLabel = "刷新",
                        onAction = { vm.fetchRecords() },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(records) { record ->
                            RecordItem(record = record)
                        }
                    }
                }
            }
        }
    }
}

/** 一条跑步记录：开始时间、距离、时长、完成状态与 rrid。 */
@Composable
private fun RecordItem(record: RecordRow, modifier: Modifier = Modifier) {
    val statusText = when {
        record.complete == true -> "已完成"
        record.complete == false -> "未完成"
        else -> "状态未知"
    }
    val statusTone = when {
        record.complete == true -> Tone.OK
        record.complete == false -> Tone.WARN
        else -> Tone.INFO
    }
    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Format.fullDateTime(record.startTime),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = statusText, tone = statusTone)
        }
        RowSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile(
                label = "距离",
                value = Format.distance(record.totalDis),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "时长",
                value = Format.duration(record.totalTime),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "步数",
                value = if (record.totalSteps > 0) record.totalSteps.toString() else "—",
                modifier = Modifier.weight(1f),
            )
        }
        RowSpace(8)
        InfoRow(label = "记录号", value = "rrid ${record.rrid}")
        InfoRow(
            label = "平均步频",
            value = if (record.avgStepFreq > 0) "${record.avgStepFreq} 步/分" else "—",
        )
        InfoRow(
            label = "消耗",
            value = if (record.calorie > 0) "${record.calorie} 千卡" else "—",
        )
    }
}

/** 页面内错误条；读取失败时停在这里，用户可关闭或点“刷新”重试。 */
@Composable
private fun RecordsBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            TextButton(onClick = onDismiss) {
                Text(text = "关闭", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
