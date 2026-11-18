package com.funsportworld.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.funsportworld.android.ui.screens.AboutScreen
import com.funsportworld.android.ui.screens.LoginScreen
import com.funsportworld.android.ui.screens.MapScreen
import com.funsportworld.android.ui.screens.PlanScreen
import com.funsportworld.android.ui.screens.RecordsScreen
import com.funsportworld.android.ui.screens.RouteEditorScreen
import com.funsportworld.android.ui.screens.RoutesScreen
import com.funsportworld.android.ui.screens.SettingsScreen
import com.funsportworld.android.viewmodel.AppViewModel

private sealed interface Destination {
    data object Main : Destination
    data object Plan : Destination
    data object Login : Destination
    data object About : Destination
    data class Editor(val routeId: String?, val startFresh: Boolean) : Destination
}

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
fun MainScaffold(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }
    var destination by remember { mutableStateOf<Destination>(Destination.Main) }

    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message.text)
        viewModel.consumeMessage()
    }

    BackHandler(enabled = destination !is Destination.Main) {
        destination = Destination.Main
    }

    when (val current = destination) {
        is Destination.Main -> {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    val tabs = listOf(
                        TabItem("地图", Icons.Filled.Map),
                        TabItem("路线", Icons.Filled.Route),
                        TabItem("记录", Icons.Filled.History),
                        TabItem("我的", Icons.Filled.Person),
                    )
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (state.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    when (tab) {
                        0 -> MapScreen(
                            vm = viewModel,
                            onOpenPlan = { destination = Destination.Plan },
                            onOpenEditor = { destination = Destination.Editor(null, false) },
                            onOpenRoutes = { tab = 1 },
                            onOpenLogin = { destination = Destination.Login },
                        )

                        1 -> RoutesScreen(
                            vm = viewModel,
                            onOpenEditor = { routeId, fresh ->
                                destination = Destination.Editor(routeId, fresh)
                            },
                        )

                        2 -> RecordsScreen(vm = viewModel)
                        else -> SettingsScreen(
                            vm = viewModel,
                            onOpenLogin = { destination = Destination.Login },
                            onOpenAbout = { destination = Destination.About },
                        )
                    }
                }
            }
        }

        is Destination.Plan -> InsetOverlay {
            PlanScreen(
                vm = viewModel,
                onBack = { destination = Destination.Main },
            )
        }

        is Destination.Login -> InsetOverlay {
            LoginScreen(
                vm = viewModel,
                onBack = { destination = Destination.Main },
            )
        }

        is Destination.About -> InsetOverlay {
            AboutScreen(
                onBack = { destination = Destination.Main },
            )
        }

        is Destination.Editor -> InsetOverlay {
            RouteEditorScreen(
                appVm = viewModel,
                initialRouteId = current.routeId,
                startFresh = current.startFresh,
                onBack = {
                    tab = 1
                    destination = Destination.Main
                },
            )
        }
    }
}

/** Full-screen pages opened above the tab scaffold still respect system bars. */
@Composable
private fun InsetOverlay(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        content()
    }
}
