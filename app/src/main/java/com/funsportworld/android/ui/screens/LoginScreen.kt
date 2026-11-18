package com.funsportworld.android.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.funsportworld.android.BuildConfig
import com.funsportworld.android.data.UiMessage
import com.funsportworld.android.location.LocationSetup
import com.funsportworld.android.location.PhoneLocation
import com.funsportworld.android.ui.components.AppTopBar
import com.funsportworld.android.ui.components.PrimaryAction
import com.funsportworld.android.ui.components.RowSpace
import com.funsportworld.android.ui.components.SecondaryAction
import com.funsportworld.android.ui.components.SectionCard
import com.funsportworld.android.ui.components.SettingsRow
import com.funsportworld.android.ui.components.StatusPill
import com.funsportworld.android.ui.components.Tone
import com.funsportworld.android.viewmodel.AppViewModel
import java.util.Locale

/**
 * 登录页：账号、密码、记住密码。
 *
 * 密码只在本机保存，并且只有勾选“记住密码”时才写入本地配置；
 * 登录成功后自动返回上一页，用本地 [submitted] 标记避免“进入时已登录”造成误返回。
 */
@Composable
fun LoginScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val settings = state.settings
    val busy = state.busy

    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var submittedAt by remember { mutableStateOf(0L) }
    var notice by remember { mutableStateOf<UiMessage?>(null) }
    val noticeBaseline = remember { System.nanoTime() }

    val context = LocalContext.current
    var locating by remember { mutableStateOf(false) }
    var locationStatus by remember { mutableStateOf<String?>(null) }
    var autoAskedLocation by rememberSaveable { mutableStateOf(false) }

    fun locationText(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    fun startPhoneLocation() {
        locating = true
        locationStatus = "正在获取手机定位…"
        PhoneLocation.locate(context) { latitude, longitude, error ->
            locating = false
            if (latitude != null && longitude != null) {
                locationStatus = "已获取 ${locationText(latitude, longitude)}，正在保存为登录位置…"
                vm.updateAnchor(latitude, longitude) {
                    LocationSetup.markApplied(context)
                    locationStatus = "已使用手机定位更新校园基准点；不合适可在【我的】手动修改。"
                }
            } else {
                locationStatus = error ?: "定位失败，可到【我的】手动填写经纬度"
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
            LocationSetup.markApplied(context)
            locationStatus = "未授予定位权限；可到【我的】手动填写经纬度"
        }
    }

    // 首次进入登录页时请求一次定位权限：手机位置作为校园基准点默认值。
    LaunchedEffect(settings, autoAskedLocation) {
        val snapshot = settings ?: return@LaunchedEffect
        if (autoAskedLocation || LocationSetup.wasApplied(context)) return@LaunchedEffect
        val replaceable = LocationSetup.isDesktopDefault(snapshot.anchorLat, snapshot.anchorLon)
        if (!replaceable) return@LaunchedEffect
        autoAskedLocation = true
        if (!PhoneLocation.hasPermission(context)) {
            locationPermissionLauncher.launch(PhoneLocation.requestedPermissions)
        } else {
            startPhoneLocation()
        }
    }

    // 配置读取完成后回填账号与“记住密码”状态，只回填一次，避免覆盖用户输入。
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        val snapshot = settings ?: return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        if (username.isBlank()) username = snapshot.username
        rememberPassword = snapshot.remember
        seeded = true
    }

    // 只展示进入本页之后产生的提示，避免把上一页的旧消息重复提示一次。
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        if (message.id >= noticeBaseline) notice = message
    }

    // 登录成功后自动返回：必须是本次提交之后产生、且不是错误的提示，
    // 这样“进入时已有会话 + 密码输错”不会被误判为登录成功。
    LaunchedEffect(submitted, busy, submittedAt, state.settings?.loggedIn, state.message?.id) {
        if (!submitted || busy) return@LaunchedEffect
        val message = state.message ?: return@LaunchedEffect
        if (message.id < submittedAt || message.isError) return@LaunchedEffect
        if (state.settings?.loggedIn != true) return@LaunchedEffect
        submitted = false
        onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppTopBar(
            title = "登录",
            subtitle = if (settings?.loggedIn == true) {
                "当前已有本机会话 · v${BuildConfig.VERSION_NAME}"
            } else {
                "登录运动世界校园版账号 · v${BuildConfig.VERSION_NAME}"
            },
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "FunSportWorld",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "运动世界校园版 · 第三方客户端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowSpace(14)

            val startupError = state.startupError
            if (startupError != null) {
                LoginBanner(text = "后台尚未就绪：$startupError", isError = true)
                RowSpace(10)
            }
            if (settings?.loggedIn == true) {
                StatusPill(text = "已有本机会话", tone = Tone.INFO)
                RowSpace(10)
            }

            SectionCard(
                title = "账号登录",
                subtitle = "密码仅保存在本机，且仅在勾选“记住密码”时保存",
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号 / 手机号") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                RowSpace(10)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(text = if (passwordVisible) "隐藏" else "显示")
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                RowSpace(2)
                SettingsRow(
                    title = "记住密码",
                    subtitle = "仅勾选时在本机保存密码，可随时在设置页清除",
                    trailing = {
                        Switch(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it },
                            enabled = !busy,
                        )
                    },
                )
            }

            RowSpace(12)
            SectionCard(
                title = "登录位置（校园基准点）",
                subtitle = "默认使用手机定位；读取打卡点与生成方案会以该坐标为中心",
            ) {
                val lat = settings?.anchorLat.orEmpty()
                val lon = settings?.anchorLon.orEmpty()
                Text(
                    text = if (lat.isBlank() || lon.isBlank()) {
                        "当前未设置校园基准点，建议使用手机定位"
                    } else {
                        "当前基准点：$lat, $lon"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowSpace(8)
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
                        ?: "定位只用于填写校园基准点；也可以到【我的】→【校园与时间】手动修改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val currentNotice = notice
            if (currentNotice != null) {
                RowSpace(10)
                LoginBanner(
                    text = currentNotice.text,
                    isError = currentNotice.isError,
                    onDismiss = {
                        notice = null
                        vm.consumeMessage()
                    },
                )
            }

            RowSpace(16)
            PrimaryAction(
                text = if (busy) state.busyLabel.ifBlank { "正在登录…" } else "登录",
                onClick = {
                    notice = null
                    submittedAt = System.nanoTime()
                    submitted = true
                    vm.login(username.trim(), password, rememberPassword)
                },
                enabled = !busy,
            )
            RowSpace(8)
            SecondaryAction(text = "返回", onClick = onBack, enabled = !busy)
            RowSpace(12)
            Text(
                text = "登录会向运动世界校园版服务器校验账号；本应用不是官方客户端，仅供学习研究。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 页面内提示条；[onDismiss] 为空时表示不可关闭（例如后台启动失败）。 */
@Composable
private fun LoginBanner(
    text: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null,
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = if (onDismiss == null) 12.dp else 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(text = "关闭", color = contentColor)
                }
            }
        }
    }
}
