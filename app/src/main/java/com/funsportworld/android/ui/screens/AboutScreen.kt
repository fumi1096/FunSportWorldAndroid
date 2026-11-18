package com.funsportworld.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.funsportworld.android.BuildConfig
import com.funsportworld.android.ui.components.AppTopBar
import com.funsportworld.android.ui.components.DividerSoft
import com.funsportworld.android.ui.components.InfoRow
import com.funsportworld.android.ui.components.RowSpace
import com.funsportworld.android.ui.components.SectionCard

/**
 * 关于页：上游来源与许可、致谢与使用声明。
 *
 * 只陈述可核对的事实：参考 FunSportWorld（CC BY-NC 4.0）实现，核心协议与轨迹算法来自该项目；
 * 不声称通过任何学校的验证，也不声称与官方有关联。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AppTopBar(title = "关于", subtitle = "版本 ${BuildConfig.VERSION_NAME}", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionCard(
                title = "FunSportWorld Android",
                subtitle = "运动世界校园版 · 第三方客户端",
            ) {
                Text(
                    text = "本应用参考 YanamiNeko 的 FunSportWorld 桌面项目（CC BY-NC 4.0）实现，" +
                        "核心通讯协议、信封加密、轨迹生成与业务逻辑来自该项目。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RowSpace(8)
                Text(
                    text = "这是学习性质的重实现，不是官方客户端，也不代表上游项目作者的立场。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RowSpace(12)
            SectionCard(title = "来源与许可", subtitle = "署名 · 非商业性使用") {
                InfoRow(label = "上游作者", value = "YanamiNeko（GitHub: Funrestan）")
                InfoRow(label = "上游项目", value = "FunSportWorld（Python 桌面端）")
                InfoRow(label = "许可协议", value = "CC BY-NC 4.0（署名 · 非商业）")
                InfoRow(label = "应用版本", value = BuildConfig.VERSION_NAME)
                InfoRow(label = "本移植版源码", value = "github.com/<your-github>/FunSportWorldAndroid")
                DividerSoft()
                RowSpace(8)
                Text(
                    text = "本应用是 FunSportWorld 的安卓二次开发版本：界面与触屏路线交互为新增，" +
                        "Python 核心经过 Android 适配；上游核心逻辑、加密与轨迹算法仍归上游作者所有。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowSpace(6)
                Text(
                    text = "按 CC BY-NC 4.0：使用、分发或修改本作品时必须注明 YanamiNeko 与上游项目地址，" +
                        "并保留 NekoSportsWorldTool / @L1Xu4n 的署名；禁止任何商业用途。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RowSpace(12)
            SectionCard(title = "致谢") {
                Text(
                    text = "YanamiNeko / Funrestan",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "FunSportWorld 桌面端与 NekoSportsWorldTool 的作者；" +
                        "本项目的协议层、信封加密、轨迹生成器与卡路里公式来自其上游工作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowSpace(10)
                Text(
                    text = "@L1Xu4n",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "提出接入地图 API 生成更真实道路的改进方案；本项目据此用步行路径规划把打卡点连成闭合环。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RowSpace(12)
            SectionCard(title = "使用声明") {
                DeclarationLine("仅供学习研究与技术交流，不用于任何商业用途。")
                DeclarationLine("本应用不是官方客户端，与运动世界校园版官方没有任何关联。")
                DeclarationLine("请遵守所在学校的规定，不要用于任何违规场景。")
                DeclarationLine("请勿倒卖或用于收费代做。")
                DeclarationLine("使用本应用产生的一切后果由使用者自行承担。")
            }

            RowSpace(12)
            SectionCard(title = "版本信息") {
                InfoRow(label = "应用版本", value = BuildConfig.VERSION_NAME)
                InfoRow(label = "客户端", value = "Android · Kotlin + Jetpack Compose")
                InfoRow(label = "上游许可", value = "CC BY-NC 4.0")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DeclarationLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
    RowSpace(4)
}
