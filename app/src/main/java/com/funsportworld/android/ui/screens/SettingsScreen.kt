package com.funsportworld.android.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.funsportworld.android.BuildConfig
import com.funsportworld.android.data.AccountRow
import com.funsportworld.android.location.LocationSetup
import com.funsportworld.android.location.PhoneLocation
import com.funsportworld.android.ui.Format
import com.funsportworld.android.ui.components.AppTopBar
import com.funsportworld.android.ui.components.DividerSoft
import com.funsportworld.android.ui.components.InfoRow
import com.funsportworld.android.ui.components.LabeledNumberField
import com.funsportworld.android.ui.components.PrimaryAction
import com.funsportworld.android.ui.components.RowSpace
import com.funsportworld.android.ui.components.SecondaryAction
import com.funsportworld.android.ui.components.SectionCard
import com.funsportworld.android.ui.components.SettingsRow
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.TextAction
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.viewmodel.AppViewModel
import java.util.Locale
import org.json.JSONObject

/**
 * “我的”页：账号状态、本机账号切换、校园坐标与时间窗口、高德 Key、记住密码、关于入口。
 *
 * 保存时组装的字段名与 Python 端 `gui_services.save_settings` 完全一致：
 * username / city / anchor_lat / anchor_lon / start_before_min / start_before_max /
 * remember / password / amap_key / clear_amap。
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onOpenLogin: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val settings = state.settings
    val busy = state.busy
    val snackbarHostState = remember { SnackbarHostState() }
    val messageBaseline = remember { System.nanoTime() }

    var city by remember { mutableStateOf("") }
    var anchorLat by remember { mutableStateOf("") }
    var anchorLon by remember { mutableStateOf("") }
    var startBeforeMin by remember { mutableStateOf("30") }
    var startBeforeMax by remember { mutableStateOf("300") }
    var rememberPassword by remember { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    var amapKeyText by remember { mutableStateOf("") }
    var clearAmap by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<AccountRow?>(null) }

    val context = LocalContext.current
    var locating by remember { mutableStateOf(false) }
    var locationStatus by remember { mutableStateOf<String?>(null) }

    fun startPhoneLocation() {
        locating = true
        locationStatus = "正在获取手机定位…"
        PhoneLocation.locate(context) { latitude, longitude, error ->
            locating = false
            if (latitude != null && longitude != null) {
                anchorLat = formatCoordinate(latitude)
                anchorLon = formatCoordinate(longitude)
                locationStatus = "已填入手机定位，点下方“保存设置”后生效；也可以继续手动修改。"
            } else {
                locationStatus = error ?: "定位失败，请手动填写经纬度"
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startPhoneLocation()
        } else {
            locationStatus = "未授予定位权限，可手动填写经纬度"
        }
    }

    // 手机定位作为默认值：仅在仍是桌面默认坐标、且已授权时自动填入一次；
    // 手动改过坐标后不再覆盖。
    var autoLocated by remember { mutableStateOf(false) }
    LaunchedEffect(settings, anchorLat, anchorLon) {
        val snapshot = settings ?: return@LaunchedEffect
        if (autoLocated || LocationSetup.wasApplied(context)) return@LaunchedEffect
        if (!LocationSetup.isDesktopDefault(snapshot.anchorLat, snapshot.anchorLon)) return@LaunchedEffect
        if (!PhoneLocation.hasPermission(context)) return@LaunchedEffect
        autoLocated = true
        startPhoneLocation()
    }

    // 配置快照变化时回填表单：首次读取、登录/切换/退出账号、保存成功后。
    LaunchedEffect(settings) {
        val snapshot = settings ?: return@LaunchedEffect
        city = snapshot.city
        anchorLat = snapshot.anchorLat
        anchorLon = snapshot.anchorLon
        startBeforeMin = snapshot.startBeforeMin.toString()
        startBeforeMax = snapshot.startBeforeMax.toString()
        rememberPassword = snapshot.remember
        amapKeyText = ""
        clearAmap = false
    }

    // 各操作的提示用页面内 Snackbar 显示；只显示进入本页之后产生的新消息。
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        if (message.id >= messageBaseline) snackbarHostState.showSnackbar(message.text)
    }

    val loggedIn = settings?.loggedIn == true
    val subtitle = if (settings != null && settings.loggedIn) {
        val name = settings.sessionName.ifBlank { settings.username }
        val uid = settings.sessionUid?.let { "UID $it" } ?: "UID 未知"
        if (name.isBlank()) uid else "$name · $uid"
    } else {
        "未登录"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "我的", subtitle = subtitle)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // ── 账号 ────────────────────────────────────────────────
                SectionCard(
                    title = "账号",
                    subtitle = if (loggedIn) "已登录（本机会话，有效性以服务器返回为准）" else "未登录",
                ) {
                    val snapshot = settings
                    if (snapshot != null && snapshot.loggedIn) {
                        InfoRow(label = "账号", value = snapshot.username.ifBlank { "—" })
                        InfoRow(label = "昵称", value = snapshot.sessionName.ifBlank { "—" })
                        InfoRow(label = "UID", value = snapshot.sessionUid?.toString() ?: "—")
                        RowSpace(6)
                        StatusPill(text = "登录状态：已登录", tone = Tone.OK)
                        RowSpace(10)
                        SecondaryAction(
                            text = if (busy) state.busyLabel.ifBlank { "正在退出…" } else "退出登录",
                            onClick = { vm.logout() },
                            enabled = !busy,
                        )
                        if (snapshot.hasPassword) {
                            RowSpace(4)
                            TextAction(
                                text = "用保存的密码重新登录",
                                onClick = {
                                    vm.login(snapshot.username, "", snapshot.remember)
                                },
                                enabled = !busy,
                            )
                            Text(
                                text = "同一账号在其它客户端登录后，本机会话会失效；" +
                                    "这里可免输密码重新登录，并顶掉对方会话。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "当前没有可用会话。登录后才能读取打卡点、生成方案和查看跑步记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RowSpace(12)
                        PrimaryAction(text = "去登录", onClick = onOpenLogin, enabled = !busy)
                    }
                    RowSpace(4)
                    TextAction(text = "登录其他账号", onClick = onOpenLogin, enabled = !busy)
                    Text(
                        text = "退出登录会请求服务器释放当前会话；保存账号不会自动退出其它账号。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 本机账号 ────────────────────────────────────────────
                RowSpace(12)
                SectionCard(
                    title = "本机账号",
                    subtitle = "点击账号切换（只换本机会话，不请求服务器）；“移除”只从本机删除",
                ) {
                    val accounts = settings?.accounts.orEmpty()
                    if (accounts.isEmpty()) {
                        Text(
                            text = "本机还没有保存过账号。登录一次后账号会出现在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        accounts.forEachIndexed { index, account ->
                            if (index > 0) DividerSoft()
                            SettingsAccountRow(
                                row = account,
                                enabled = !busy,
                                onSwitch = { vm.switchAccount(account.username) },
                                onRemove = { pendingRemove = account },
                            )
                        }
                        RowSpace(4)
                        Text(
                            text = "切换只换本机会话，不请求服务器；活动账号的会话被移除后需要重新登录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── 校园与时间 ──────────────────────────────────────────
                RowSpace(12)
                SectionCard(
                    title = "校园与时间",
                    subtitle = "校园基准坐标与有效时段容差；修改城市或坐标会清空点位缓存",
                ) {
                    LabeledNumberField(
                        label = "城市",
                        value = city,
                        onValueChange = { city = it },
                        keyboardType = KeyboardType.Text,
                        supporting = "例如：成都市",
                    )
                    RowSpace(10)
                    SecondaryAction(
                        text = if (locating) "正在定位…" else "使用手机定位",
                        onClick = {
                            if (PhoneLocation.hasPermission(context)) {
                                startPhoneLocation()
                            } else {
                                locationPermissionLauncher.launch(PhoneLocation.requestedPermissions)
                            }
                        },
                        enabled = !busy && !locating,
                    )
                    RowSpace(4)
                    Text(
                        text = locationStatus
                            ?: "默认使用手机定位填写上方经纬度；也可直接手动输入后保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RowSpace(10)
                    LabeledNumberField(
                        label = "纬度",
                        value = anchorLat,
                        onValueChange = { anchorLat = it },
                        supporting = "校园基准点纬度，可与经度同时留空",
                    )
                    RowSpace(10)
                    LabeledNumberField(
                        label = "经度",
                        value = anchorLon,
                        onValueChange = { anchorLon = it },
                        supporting = "校园基准点经度",
                    )
                    RowSpace(10)
                    SecondaryAction(
                        text = "从已读取打卡点计算中心",
                        onClick = {
                            val points = state.checkpoints
                            if (points.isNotEmpty()) {
                                anchorLat = formatCoordinate(points.map { it.lat }.average())
                                anchorLon = formatCoordinate(points.map { it.lon }.average())
                            }
                        },
                        enabled = state.checkpoints.isNotEmpty() && !busy,
                    )
                    RowSpace(6)
                    Text(
                        text = if (state.checkpoints.isEmpty()) {
                            "尚未读取打卡点：先到跑步页读取一次，再回到这里取平均中心。"
                        } else {
                            "已读取 ${state.checkpoints.size} 个打卡点（${Format.relative(state.checkpointsAt)}），" +
                                "取平均值填入上方经纬度；请与桌面端保存的基准坐标核对后再保存。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RowSpace(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledNumberField(
                            label = "最短提前",
                            value = startBeforeMin,
                            onValueChange = { startBeforeMin = it },
                            keyboardType = KeyboardType.Number,
                            supporting = "分钟 · 1–4320",
                            modifier = Modifier.weight(1f),
                        )
                        LabeledNumberField(
                            label = "最长提前",
                            value = startBeforeMax,
                            onValueChange = { startBeforeMax = it },
                            keyboardType = KeyboardType.Number,
                            supporting = "分钟 · 1–4320",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    RowSpace(4)
                    Text(
                        text = "方案开始时间需要落在「最短提前 ~ 最长提前」之间；该范围在生成方案时读取。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 高德 Key ───────────────────────────────────────────
                RowSpace(12)
                SectionCard(
                    title = "高德 Web 服务 Key",
                    subtitle = "只写入不回显；用于把打卡点连成贴合真实道路的路线",
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = if (settings?.hasAmapKey == true) "已配置" else "未配置",
                            tone = if (settings?.hasAmapKey == true) Tone.OK else Tone.INFO,
                        )
                        if (settings?.hasAmapKey == true) {
                            Spacer(Modifier.width(6.dp))
                            TextAction(
                                text = "清除",
                                onClick = {
                                    clearAmap = true
                                    amapKeyText = ""
                                },
                                enabled = !busy,
                            )
                        }
                    }
                    RowSpace(6)
                    OutlinedTextField(
                        value = amapKeyText,
                        onValueChange = {
                            amapKeyText = it
                            if (it.isNotEmpty()) clearAmap = false
                        },
                        label = { Text("新的高德 Key（留空表示不修改）") },
                        singleLine = true,
                        enabled = !busy && !clearAmap,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (clearAmap) {
                        RowSpace(4)
                        Text(
                            text = "保存后会清除本机已保存的高德 Key。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (settings?.hasAmapKey == true) {
                        RowSpace(4)
                        Text(
                            text = "本机已保存 Key；出于安全考虑不会显示原值。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── 记住密码 ────────────────────────────────────────────
                RowSpace(12)
                SectionCard(
                    title = "记住密码",
                    subtitle = "密码只保存在本机；关闭该开关并保存会清除已保存的密码",
                ) {
                    SettingsRow(
                        title = "记住密码",
                        subtitle = if (settings?.hasPassword == true) "本机已保存密码" else "本机尚未保存密码",
                        trailing = {
                            Switch(
                                checked = rememberPassword,
                                onCheckedChange = { rememberPassword = it },
                                enabled = !busy,
                            )
                        },
                    )
                    RowSpace(4)
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        label = { Text("密码（留空表示不修改）") },
                        singleLine = true,
                        enabled = !busy && rememberPassword,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RowSpace(4)
                    Text(
                        text = "勾选后密码以明文写入本机配置；登录时仍会向服务器校验。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 保存 ───────────────────────────────────────────────
                RowSpace(16)
                PrimaryAction(
                    text = if (busy) state.busyLabel.ifBlank { "正在保存…" } else "保存设置",
                    onClick = {
                        val base = settings
                        if (base != null) {
                            val values = JSONObject()
                                .put("username", base.username)
                                .put("city", city.trim())
                                .put("anchor_lat", anchorLat.trim())
                                .put("anchor_lon", anchorLon.trim())
                                .put("start_before_min", startBeforeMin.trim())
                                .put("start_before_max", startBeforeMax.trim())
                                .put("remember", rememberPassword)
                                .put("password", if (rememberPassword) passwordText else "")
                                .put("amap_key", amapKeyText.trim())
                                .put("clear_amap", clearAmap)
                            vm.saveSettings(values)
                        }
                    },
                    enabled = settings != null && !busy,
                )
                RowSpace(6)
                Text(
                    text = "保存会校验城市与时间范围；修改城市或基准坐标后，本地点位缓存会被清空。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── 关于 ───────────────────────────────────────────────
                RowSpace(12)
                SectionCard(title = "关于", subtitle = "版本 ${BuildConfig.VERSION_NAME}") {
                    SettingsRow(
                        title = "关于本应用",
                        subtitle = "开源许可、致谢与使用声明",
                        onClick = onOpenAbout,
                        trailing = {
                            Text(
                                text = "查看",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    val dataDir = settings?.dataDir.orEmpty()
                    if (dataDir.isNotBlank()) {
                        DividerSoft()
                        InfoRow(label = "数据目录", value = dataDir)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    val removing = pendingRemove
    if (removing != null) {
        val activeHint = if (removing.active) {
            "该账号是当前活动账号，移除后本机会话也会被清除。"
        } else {
            ""
        }
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除本机账号") },
            text = {
                Text(
                    text = "将从本机账号库移除 ${removing.username}。" +
                        "这是本机操作，不会请求服务器退出登录。$activeHint",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        vm.removeAccount(removing.username)
                    },
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 本机账号行：点击切换，右侧“移除”按钮走二次确认。 */
@Composable
private fun SettingsAccountRow(
    row: AccountRow,
    enabled: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (row.active) base else base.clickable(enabled = enabled, onClick = onSwitch)
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name.ifBlank { row.username },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.active) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(text = "当前活动", tone = Tone.INFO)
                }
            }
            Text(
                text = row.username,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RowSpace(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = if (row.hasSession) "已有本机会话" else "需重新登录",
                    tone = if (row.hasSession) Tone.OK else Tone.WARN,
                )
                if (row.hasPassword) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(text = "已存密码", tone = Tone.INFO)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        TextAction(text = "移除", onClick = onRemove, enabled = enabled)
    }
}

/** 打卡点平均中心按 6 位小数写入，与桌面端从打卡点推算坐标的精度一致。 */
private fun formatCoordinate(value: Double): String =
    if (!value.isFinite()) "" else String.format(Locale.US, "%.6f", value)
