package com.funsportworld.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.funsportworld.android.data.PlanCheckpoint
import com.funsportworld.android.data.PlanParams
import com.funsportworld.android.data.PlanUi
import com.funsportworld.android.data.RoutePayload
import com.funsportworld.android.data.StartTimeMode
import com.funsportworld.android.data.SubmitResult
import com.funsportworld.android.data.polylineLength
import com.funsportworld.android.ui.Format
import com.funsportworld.android.ui.components.AppTopBar
import com.funsportworld.android.ui.components.Chip
import com.funsportworld.android.ui.components.DividerSoft
import com.funsportworld.android.ui.components.InfoRow
import com.funsportworld.android.ui.components.LabeledNumberField
import com.funsportworld.android.ui.components.LogPanel
import com.funsportworld.android.ui.components.PrimaryAction
import com.funsportworld.android.ui.components.RowSpace
import com.funsportworld.android.ui.components.SecondaryAction
import com.funsportworld.android.ui.components.SectionCard
import com.funsportworld.android.ui.components.SettingsRow
import com.funsportworld.android.ui.components.StatTile
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.TextAction
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.ui.theme.BrandRed
import com.funsportworld.android.viewmodel.AppViewModel
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生成方案页：表单收集参数，调用 [AppViewModel.generatePlan] / [AppViewModel.previewPlan]，
 * 展示冻结的 [PlanUi] 并完成二次确认提交。
 *
 * 所有网络与 Python 调用都由 ViewModel 发起；本页只读 `vm.state` 并派生界面状态。
 */
@Composable
fun PlanScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 表单状态（只在本页保存，不写入 ViewModel）─────────────────────────
    var auto by rememberSaveable { mutableStateOf(true) }
    var distText by rememberSaveable { mutableStateOf("2.0") }
    var paceText by rememberSaveable { mutableStateOf("420") }
    var cadenceText by rememberSaveable { mutableStateOf("160") }
    var seedText by rememberSaveable { mutableStateOf("0") }
    var startMode by remember { mutableStateOf(StartTimeMode.CONFIG) }
    var beforeText by rememberSaveable { mutableStateOf("60") }
    var startAtText by rememberSaveable { mutableStateOf(defaultStartAtText()) }
    var useSketch by rememberSaveable { mutableStateOf(false) }
    var allowOutside by rememberSaveable { mutableStateOf(false) }
    var showSubmitDialog by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    // ── 由 vm.state 派生的登录信息 ────────────────────────────────────────
    val settings = state.settings
    val loggedIn = settings?.loggedIn == true
    val accountLabel = if (!loggedIn) {
        "未登录"
    } else {
        val name = settings?.sessionName.orEmpty().ifBlank { settings?.username.orEmpty() }
        name.ifBlank { "已登录" }
    }

    // ── 参数校验：失败时给出页面内提示并禁用生成 ──────────────────────────
    val draft = buildFormDraft(
        auto = auto,
        distText = distText,
        paceText = paceText,
        cadenceText = cadenceText,
        seedText = seedText,
        startMode = startMode,
        beforeText = beforeText,
        startAtText = startAtText,
        useSketch = useSketch,
        activeRoute = state.activeRoute,
        allowOutside = allowOutside,
    )
    val params = draft.params
    val formError = draft.error
    val activeRoute = state.activeRoute
    // 打开手绘路线却没有选中路线时不能生成：参数本身合法，但缺少路线数据。
    val sketchMissing = useSketch && activeRoute == null
    val canGenerate = !state.busy && params != null && !sketchMissing
    val busyLabel = state.busyLabel.ifBlank { "处理中…" }

    // ── 一次性消息：Snackbar 展示后立刻消费，避免重复弹出 ──────────────────
    val message = state.message
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = current.text,
            withDismissAction = current.isError,
            duration = if (current.isError) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        vm.consumeMessage()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "生成方案",
                subtitle = accountLabel,
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // ── 未登录提示：只提示，不阻止浏览 ──────────────────────────
                if (!loggedIn) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "请先在【我的】中登录",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            RowSpace(2)
                            Text(
                                text = "未登录仍可浏览本页；在线生成与提交需要有效的登录会话。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    RowSpace(12)
                }

                // ── 参数表单 ────────────────────────────────────────────────
                SectionCard(
                    title = "方案参数",
                    subtitle = "参数含义与桌面端一致；自动模式下由学校策略决定距离与配速",
                ) {
                    SettingsRow(
                        title = "自动模式",
                        subtitle = if (auto) {
                            "距离按学校要求+10%~30%冗余，配速/步频随机"
                        } else {
                            "手动指定距离、配速和步频"
                        },
                        trailing = {
                            Switch(checked = auto, onCheckedChange = { auto = it })
                        },
                    )

                    if (!auto) {
                        LabeledNumberField(
                            label = "距离",
                            value = distText,
                            onValueChange = { distText = it },
                            suffix = "km",
                            supporting = "0.1 – 99 km",
                        )
                        RowSpace(8)
                        LabeledNumberField(
                            label = "配速",
                            value = paceText,
                            onValueChange = { paceText = it },
                            suffix = "秒/km",
                            supporting = "160 – 520 秒/km",
                        )
                        RowSpace(8)
                        LabeledNumberField(
                            label = "步频",
                            value = cadenceText,
                            onValueChange = { cadenceText = it },
                            suffix = "步/分钟",
                            supporting = "60 – 220 步/分钟",
                        )
                        RowSpace(8)
                    }

                    LabeledNumberField(
                        label = "随机种子",
                        value = seedText,
                        onValueChange = { seedText = it },
                        keyboardType = KeyboardType.Number,
                        supporting = "0 = 自动",
                    )

                    RowSpace(10)
                    DividerSoft()
                    RowSpace(8)

                    Text(
                        text = "开始时间",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    RowSpace(6)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip(
                            text = "按配置随机",
                            selected = startMode == StartTimeMode.CONFIG,
                            onClick = { startMode = StartTimeMode.CONFIG },
                        )
                        Chip(
                            text = "指定提前分钟",
                            selected = startMode == StartTimeMode.BEFORE,
                            onClick = { startMode = StartTimeMode.BEFORE },
                        )
                        Chip(
                            text = "指定历史开始时间",
                            selected = startMode == StartTimeMode.AT,
                            onClick = { startMode = StartTimeMode.AT },
                        )
                    }
                    RowSpace(8)

                    if (startMode == StartTimeMode.BEFORE) {
                        LabeledNumberField(
                            label = "提前分钟",
                            value = beforeText,
                            onValueChange = { beforeText = it },
                            keyboardType = KeyboardType.Number,
                            suffix = "分钟",
                            supporting = "1 – 4320 分钟",
                        )
                    } else if (startMode == StartTimeMode.AT) {
                        LabeledNumberField(
                            label = "历史开始时间",
                            value = startAtText,
                            onValueChange = { startAtText = it },
                            keyboardType = KeyboardType.Text,
                            supporting = "格式 YYYY-MM-DD HH:MM，须在过去 3 天内",
                        )
                    } else {
                        Text(
                            text = "使用配置的提前范围（由【设置与登录】中的提前区间决定）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    RowSpace(10)
                    DividerSoft()
                    RowSpace(8)

                    SettingsRow(
                        title = "使用手绘路线",
                        subtitle = "关闭时使用高德步行路线",
                        trailing = {
                            Switch(checked = useSketch, onCheckedChange = { useSketch = it })
                        },
                    )
                    if (activeRoute != null) {
                        InfoRow(
                            label = "当前路线",
                            value = state.activeRouteLabel.ifBlank { "手绘路线" },
                        )
                        InfoRow(label = "路线长度", value = Format.distance(routeLengthMeters(activeRoute)))
                        InfoRow(label = "顶点数", value = "${activeRoute.vertices.size}")
                    } else {
                        Text(
                            text = "尚未选择手绘路线，可在【路线】页选择或绘制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (useSketch && activeRoute == null) {
                        RowSpace(6)
                        WarningNote(
                            text = "已打开手绘路线但尚未选择路线，生成与预览都不可用。",
                            background = MaterialTheme.colorScheme.errorContainer,
                            foreground = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }

                    RowSpace(10)
                    DividerSoft()
                    RowSpace(8)

                    SettingsRow(
                        title = "时间外测试",
                        subtitle = "只跳过本地时间检查，服务器仍可能拒绝（11016）",
                        trailing = {
                            Switch(checked = allowOutside, onCheckedChange = { allowOutside = it })
                        },
                    )

                    if (formError != null) {
                        RowSpace(8)
                        WarningNote(
                            text = "参数错误：$formError",
                            background = MaterialTheme.colorScheme.errorContainer,
                            foreground = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }

                    RowSpace(10)
                    PrimaryAction(
                        text = "在线生成方案",
                        onClick = { params?.let { vm.generatePlan(it) } },
                        enabled = canGenerate,
                    )
                    RowSpace(8)
                    SecondaryAction(
                        text = "本地轨迹预览",
                        onClick = {
                            // 本地预览不读学校策略：后端预览接口要求明确的距离/配速/步频，
                            // 自动模式只把这三项从桥接参数里省略，因此这里补成手动参数副本。
                            params?.let { p -> vm.previewPlan(if (p.auto) p.copy(auto = false) else p) }
                        },
                        enabled = canGenerate,
                    )
                    RowSpace(2)
                    Text(
                        text = "本地轨迹预览按上方距离/配速/步频模拟；自动模式下使用默认 2.0 km / 420 秒每公里 / 160。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.busy) {
                        RowSpace(10)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = busyLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RowSpace(8)
                        LogPanel(lines = state.logs)
                    }
                }

                // ── 提交结果 ────────────────────────────────────────────────
                val submission = state.submission
                if (submission != null) {
                    RowSpace(12)
                    SubmissionSection(submission)
                }

                // ── 方案结果 ────────────────────────────────────────────────
                val plan = state.plan
                if (plan != null) {
                    RowSpace(12)
                    PlanResultSection(
                        plan = plan,
                        busy = state.busy,
                        onRequestSubmit = { showSubmitDialog = true },
                        onRequestClear = { showClearDialog = true },
                    )
                }

                RowSpace(24)
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )

        val submittingPlan = state.plan
        if (showSubmitDialog && submittingPlan != null) {
            SubmitConfirmDialog(
                plan = submittingPlan,
                allowOutside = allowOutside,
                onDismiss = { showSubmitDialog = false },
                onConfirm = {
                    showSubmitDialog = false
                    vm.submitPlan(allowOutside)
                },
            )
        }

        if (showClearDialog) {
            ClearConfirmDialog(
                onDismiss = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    vm.clearPlan()
                },
            )
        }
    }
}

// ── 方案结果 ──────────────────────────────────────────────────────────────

@Composable
private fun PlanResultSection(
    plan: PlanUi,
    busy: Boolean,
    onRequestSubmit: () -> Unit,
    onRequestClear: () -> Unit,
) {
    val frozen = plan.freezeRunTime > 0
    val completion = plan.completionComplete
    val completionText = if (completion == null) "未知" else if (completion) "满足" else "未满足"
    val completionTone = if (completion == null) Tone.INFO else if (completion) Tone.OK else Tone.ERROR
    val passedCount = plan.passedCount
    val checkpointTotal = plan.checkpoints.size

    SectionCard(
        title = "方案结果",
        subtitle = (if (plan.previewOnly) "仅轨迹预览，不可提交" else "在线生成方案，可提交") + " · " + plan.planId,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "距离",
                value = Format.distance(plan.totalDistance),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "时长",
                value = Format.duration(plan.totalTime),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "配速",
                value = Format.paceClock(plan.paceSecPerKm),
                modifier = Modifier.weight(1f),
            )
        }
        RowSpace(10)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "步频",
                value = Format.cadence(plan.cadence),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "打卡点",
                value = "$checkpointTotal",
                hint = if (passedCount != null) "本地通过 $passedCount" else "本地模拟未提供",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "种子",
                value = plan.seed.toString(),
                modifier = Modifier.weight(1f),
            )
        }

        RowSpace(10)
        InfoRow(label = "开始时间", value = Format.fullDateTime(plan.startMs))
        InfoRow(label = "结束时间", value = Format.fullDateTime(planEndMs(plan)))
        InfoRow(label = "路线来源", value = providerLabel(plan.provider))
        InfoRow(label = "时间窗口", value = Format.windowReason(plan.windowOk, plan.windowReason))
        InfoRow(
            label = "时间外标记",
            value = if (plan.allowOutsideWindow) "已允许时间外" else "仅有效时段内",
        )

        RowSpace(4)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                text = if (plan.windowOk) "有效时段内" else "有效时段外",
                tone = if (plan.windowOk) Tone.OK else Tone.WARN,
            )
            StatusPill(text = "完成条件：$completionText", tone = completionTone)
            if (plan.previewOnly) {
                StatusPill(text = "仅预览", tone = Tone.INFO)
            }
        }

        if (frozen) {
            RowSpace(8)
            WarningNote(
                text = "服务器报告跑步冻结，剩余 ${formatFreezeDuration(plan.freezeRunTime)}；" +
                    "冻结结束前禁止提交，解冻后请重新生成在线方案确认状态。",
                background = MaterialTheme.colorScheme.errorContainer,
                foreground = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        if (checkpointTotal > 0) {
            RowSpace(12)
            DividerSoft()
            RowSpace(8)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "打卡点",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (passedCount != null) {
                        "本地模拟通过 $passedCount/$checkpointTotal"
                    } else {
                        "本地模拟未提供"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowSpace(4)
            for (checkpoint in plan.checkpoints) {
                CheckpointRow(checkpoint)
            }
        }

        if (plan.details.isNotBlank()) {
            RowSpace(10)
            DividerSoft()
            RowSpace(8)
            PlanDetails(plan)
        }

        if (plan.provider == "user_sketch" || plan.sketchVertices != null) {
            RowSpace(10)
            DividerSoft()
            RowSpace(8)
            Text(
                text = "手绘路线",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InfoRow(label = "手绘顶点", value = plan.sketchVertices?.toString() ?: "—")
            InfoRow(label = "平滑坐标点", value = plan.sketchPoints?.toString() ?: "—")
            val orderOk = plan.sketchOrderOk
            InfoRow(
                label = "与打卡点顺序",
                value = if (orderOk == null) "未知" else if (orderOk) "一致" else "不一致",
            )
            InfoRow(label = "起点旋转", value = yesNoUnknown(plan.sketchRotated))
            InfoRow(label = "反向", value = yesNoUnknown(plan.sketchReversed))
            InfoRow(label = "吸附打卡点", value = yesNoUnknown(plan.sketchSnapped))
            val ringLength = plan.ringLengthM
            if (ringLength != null) {
                InfoRow(label = "完整环长", value = Format.distance(ringLength))
            }
        }

        RowSpace(12)
        DividerSoft()
        RowSpace(8)

        if (plan.previewOnly) {
            WarningNote(
                text = "轨迹预览不可提交；请在开放时段重新在线生成方案。",
                background = MaterialTheme.colorScheme.secondaryContainer,
                foreground = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            PrimaryAction(
                text = if (frozen) "冻结期间禁止提交" else "提交记录",
                onClick = onRequestSubmit,
                enabled = !busy && !frozen,
            )
        }
        RowSpace(2)
        TextAction(text = "清除当前方案", onClick = onRequestClear, enabled = !busy)
    }
}

@Composable
private fun CheckpointRow(checkpoint: PlanCheckpoint) {
    val nearest = checkpoint.nearestM
    val title = checkpoint.name.ifBlank { "未命名打卡点" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${checkpoint.index + 1}. $title",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "position ${checkpoint.position}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            text = if (nearest != null) String.format(Locale.US, "最近 %.1f m", nearest) else "最近距离未知",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlanDetails(plan: PlanUi) {
    var expanded by rememberSaveable(plan.planId) { mutableStateOf(false) }
    Text(
        text = "方案明细",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    RowSpace(4)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = plan.details,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
    }
    TextAction(
        text = if (expanded) "收起明细" else "展开明细",
        onClick = { expanded = !expanded },
    )
}

// ── 提交结果卡片 ──────────────────────────────────────────────────────────

@Composable
private fun SubmissionSection(result: SubmitResult) {
    SectionCard(title = "提交结果", subtitle = "以服务器记录页为准，不要重复提交") {
        InfoRow(label = "记录 ID", value = result.rrid.toString())
        InfoRow(label = "OBS 成功", value = "${result.obsOk}/2")
        InfoRow(label = "详情读取", value = if (result.detailOk) "成功" else "失败")
        RowSpace(6)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                label = "距离",
                value = Format.distance(result.dist),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "时长",
                value = Format.duration(result.dur),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "步频",
                value = Format.cadence(result.cadence),
                modifier = Modifier.weight(1f),
            )
        }
        RowSpace(6)
        val passed = result.checkpointPassed?.toString() ?: "—"
        val total = result.checkpointCount?.toString() ?: "—"
        InfoRow(label = "打卡点本地通过", value = "$passed/$total")
        RowSpace(8)
        WarningNote(
            text = "不要因为界面缺显示而重复提交；请到【记录】页核对服务器结果。",
            background = MaterialTheme.colorScheme.secondaryContainer,
            foreground = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (result.uuid.isNotBlank()) {
            RowSpace(4)
            Text(
                text = "uuid：${result.uuid}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 二次确认弹窗 ──────────────────────────────────────────────────────────

@Composable
private fun SubmitConfirmDialog(
    plan: PlanUi,
    allowOutside: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }
    val frozen = plan.freezeRunTime > 0
    val needsAck = !plan.windowOk
    val confirmEnabled = !frozen && (!needsAck || acknowledged)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "确认提交方案",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                RowSpace(10)
                InfoRow(label = "开始时间", value = Format.fullDateTime(plan.startMs))
                InfoRow(label = "结束时间", value = Format.fullDateTime(planEndMs(plan)))
                InfoRow(label = "距离", value = Format.distance(plan.totalDistance))
                InfoRow(label = "时长", value = Format.duration(plan.totalTime))
                InfoRow(label = "打卡点", value = "${plan.checkpoints.size} 个")

                if (frozen) {
                    RowSpace(8)
                    WarningNote(
                        text = "服务器报告跑步冻结，剩余 ${formatFreezeDuration(plan.freezeRunTime)}，禁止提交。",
                        background = MaterialTheme.colorScheme.errorContainer,
                        foreground = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                if (needsAck) {
                    RowSpace(10)
                    WarningNote(
                        text = "本地时段检查未通过。继续提交时服务器仍可能拒绝（11016）。",
                        background = MaterialTheme.colorScheme.errorContainer,
                        foreground = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = acknowledged,
                            onCheckedChange = { acknowledged = it },
                        )
                        Text(
                            text = "我了解服务器可能拒绝",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (allowOutside) {
                    RowSpace(4)
                    Text(
                        text = "已开启时间外测试：只跳过本地检查，服务器限制仍生效，可能返回 11016。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RowSpace(10)
                Text(
                    text = "提交后不可撤销，请勿重复提交。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandRed,
                )
                RowSpace(14)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm, enabled = confirmEnabled) {
                        Text("确认提交")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "清除当前方案",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                RowSpace(8)
                Text(
                    text = "清除后需要重新生成方案；已提交的记录不受影响，请以服务器记录页为准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowSpace(14)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("清除") }
                }
            }
        }
    }
}

// ── 小工具 ────────────────────────────────────────────────────────────────

@Composable
private fun WarningNote(text: String, background: Color, foreground: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = background,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private data class FormDraft(val params: PlanParams?, val error: String?)

/** 校验表单并生成 [PlanParams]；任一字段不合格时返回错误文案且不产出参数。 */
private fun buildFormDraft(
    auto: Boolean,
    distText: String,
    paceText: String,
    cadenceText: String,
    seedText: String,
    startMode: StartTimeMode,
    beforeText: String,
    startAtText: String,
    useSketch: Boolean,
    activeRoute: RoutePayload?,
    allowOutside: Boolean,
): FormDraft {
    val errors = ArrayList<String>()

    var distKm = 2.0
    var pace = 420.0
    var cadence = 160.0
    if (!auto) {
        val dist = distText.trim().toDoubleOrNull()
        if (dist == null || !dist.isFinite() || dist < 0.1 || dist > 99.0) {
            errors.add("距离需为 0.1–99 km 之间的数字")
        } else {
            distKm = dist
        }
        val paceValue = paceText.trim().toDoubleOrNull()
        if (paceValue == null || !paceValue.isFinite() || paceValue < 160.0 || paceValue > 520.0) {
            errors.add("配速需为 160–520 秒/km 之间的数字")
        } else {
            pace = paceValue
        }
        val cadenceValue = cadenceText.trim().toDoubleOrNull()
        if (cadenceValue == null || !cadenceValue.isFinite() || cadenceValue < 60.0 || cadenceValue > 220.0) {
            errors.add("步频需为 60–220 之间的数字")
        } else {
            cadence = cadenceValue
        }
    }

    val seed = seedText.trim().toLongOrNull()
    if (seed == null || seed < 0L || seed > 2147483647L) {
        errors.add("随机种子需为 0–2147483647 之间的整数")
    }

    var beforeMinutes = 60
    var startAtMs: Long? = null
    if (startMode == StartTimeMode.BEFORE) {
        val before = beforeText.trim().toIntOrNull()
        if (before == null || before < 1 || before > 4320) {
            errors.add("提前分钟需为 1–4320 之间的整数")
        } else {
            beforeMinutes = before
        }
    } else if (startMode == StartTimeMode.AT) {
        val parsed = parseStartAtMillis(startAtText)
        if (parsed == null) {
            errors.add("开始时间格式应为 YYYY-MM-DD HH:MM")
        } else {
            val age = System.currentTimeMillis() - parsed
            when {
                age < 0L -> errors.add("开始时间不能晚于当前时间")
                age > 3L * 86_400_000L -> errors.add("开始时间必须在过去三天内")
                else -> startAtMs = parsed
            }
        }
    }

    // 已打开手绘路线但未选择路线时参数仍然合法；禁用按钮与路线提示由 PlanScreen 负责。
    return if (errors.isEmpty()) {
        FormDraft(
            params = PlanParams(
                auto = auto,
                distKm = distKm,
                paceSecPerKm = pace,
                cadence = cadence,
                seed = seed ?: 0L,
                startMode = startMode,
                beforeMinutes = beforeMinutes,
                startAtMs = startAtMs,
                useSketch = useSketch,
                route = if (useSketch) activeRoute else null,
                allowOutside = allowOutside,
            ),
            error = null,
        )
    } else {
        FormDraft(params = null, error = errors.joinToString("；"))
    }
}

/** 解析 “YYYY-MM-DD HH:MM” 为本地时区毫秒；格式或日期非法时返回 null。 */
private fun parseStartAtMillis(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    format.isLenient = false
    val position = ParsePosition(0)
    var parsed: Date? = null
    try {
        parsed = format.parse(trimmed, position)
    } catch (error: RuntimeException) {
        return null
    }
    val value = parsed ?: return null
    if (position.index != trimmed.length) return null
    return value.time
}

/** 默认给出 1 小时前的本地时间文本，与桌面端默认值一致。 */
private fun defaultStartAtText(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() - 3_600_000L))

private fun planEndMs(plan: PlanUi): Long = plan.startMs + plan.totalTime.toLong() * 1000L

private fun routeLengthMeters(route: RoutePayload): Double =
    if (route.lengthM > 0) route.lengthM else polylineLength(route.ring)

private fun providerLabel(provider: String): String = when (provider) {
    "amap_walking" -> "高德步行路线"
    "user_sketch" -> "手绘路线"
    "" -> "未知"
    else -> "未知（$provider）"
}

private fun yesNoUnknown(value: Boolean?): String =
    if (value == null) "未知" else if (value) "是" else "否"

/** 冻结剩余时间的可读文本，例如 “1 天 2 小时 3 分 4 秒”。 */
private fun formatFreezeDuration(seconds: Int): String {
    var rest = seconds.coerceAtLeast(0)
    val days = rest / 86400
    rest %= 86400
    val hours = rest / 3600
    rest %= 3600
    val minutes = rest / 60
    rest %= 60
    val parts = ArrayList<String>(4)
    if (days > 0) parts.add("$days 天")
    if (hours > 0 || days > 0) parts.add("$hours 小时")
    if (minutes > 0 || hours > 0 || days > 0) parts.add("$minutes 分")
    parts.add("$rest 秒")
    return parts.joinToString(" ")
}
