package me.crsib.freecartracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.crsib.freecartracker.domain.model.EntryKind
import me.crsib.freecartracker.ui.dashboard.DashboardScreen
import me.crsib.freecartracker.ui.entry.AddEntryScreen
import me.crsib.freecartracker.ui.history.HistoryScreen
import me.crsib.freecartracker.ui.settings.SettingsScreen
import me.crsib.freecartracker.ui.telemetry.LiveTelemetryScreen
import me.crsib.freecartracker.ui.telemetry.ObdConnectionScreen
import me.crsib.freecartracker.ui.telemetry.RouteMapScreen
import me.crsib.freecartracker.ui.telemetry.TripDetailScreen
import me.crsib.freecartracker.ui.telemetry.TripListScreen
import me.crsib.freecartracker.ui.vehicles.VehicleEditScreen
import me.crsib.freecartracker.ui.vehicles.VehicleListScreen
import me.crsib.freecartracker.ui.webdav.WebDavScreen

private enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", "Dashboard", Icons.Filled.Speed),
    History("history", "History", Icons.AutoMirrored.Filled.List),
    Trips("trips", "Trips", Icons.Filled.Timeline),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

object Routes {
    const val VEHICLE_EDIT = "vehicle_edit"
    fun vehicleEdit(id: Long?): String =
        if (id == null) "$VEHICLE_EDIT?id=-1" else "$VEHICLE_EDIT?id=$id"

    const val ADD_ENTRY = "add_entry"
    fun addEntry(vehicleId: Long): String = "$ADD_ENTRY/$vehicleId"

    const val EDIT_ENTRY = "edit_entry"
    fun editEntry(vehicleId: Long, kind: EntryKind, id: Long): String =
        "$EDIT_ENTRY/$vehicleId/${kind.name}/$id"

    const val WEBDAV = "webdav"
    const val VEHICLES = "vehicles"
    const val OBD_CONNECTION = "obd_connection"
    const val TELEMETRY_LIVE = "telemetry_live"

    const val TRIP_DETAIL = "trip_detail"
    fun tripDetail(id: Long): String = "$TRIP_DETAIL/$id"

    const val ROUTE_MAP = "route_map"
    fun routeMap(id: Long): String = "$ROUTE_MAP/$id"
}

@Composable
fun CarTrackerApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val onTopDestination = TopDestination.entries.any { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    Scaffold(
        bottomBar = {
            if (onTopDestination) {
                NavigationBar {
                    TopDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopDestination.Dashboard.route) {
                DashboardScreen(
                    onAddEntry = { vid -> navController.navigate(Routes.addEntry(vid)) },
                    onEditEntry = { vid, kind, id -> navController.navigate(Routes.editEntry(vid, kind, id)) },
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                )
            }
            composable(TopDestination.History.route) {
                HistoryScreen(
                    onEditEntry = { vid, kind, id -> navController.navigate(Routes.editEntry(vid, kind, id)) },
                )
            }
            composable(TopDestination.Trips.route) {
                TripListScreen(
                    onRecord = { navController.navigate(Routes.TELEMETRY_LIVE) },
                    onOpenTrip = { id -> navController.navigate(Routes.tripDetail(id)) },
                    onOpenConnection = { navController.navigate(Routes.OBD_CONNECTION) },
                )
            }
            composable(Routes.VEHICLES) {
                VehicleListScreen(
                    onAddVehicle = { navController.navigate(Routes.vehicleEdit(null)) },
                    onEditVehicle = { id -> navController.navigate(Routes.vehicleEdit(id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TopDestination.Settings.route) {
                SettingsScreen(
                    onOpenWebDav = { navController.navigate(Routes.WEBDAV) },
                    onOpenVehicles = { navController.navigate(Routes.VEHICLES) },
                )
            }
            composable(Routes.WEBDAV) {
                WebDavScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.OBD_CONNECTION) {
                ObdConnectionScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TELEMETRY_LIVE) {
                LiveTelemetryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConnection = { navController.navigate(Routes.OBD_CONNECTION) },
                )
            }
            composable(
                route = "${Routes.TRIP_DETAIL}/{tripId}",
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
            ) { entry ->
                val tripId = entry.arguments?.getLong("tripId") ?: return@composable
                TripDetailScreen(
                    tripId = tripId,
                    onBack = { navController.popBackStack() },
                    onOpenMap = { id -> navController.navigate(Routes.routeMap(id)) },
                )
            }
            composable(
                route = "${Routes.ROUTE_MAP}/{tripId}",
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
            ) { entry ->
                val tripId = entry.arguments?.getLong("tripId") ?: return@composable
                RouteMapScreen(tripId = tripId, onBack = { navController.popBackStack() })
            }

            composable(
                route = "${Routes.VEHICLE_EDIT}?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: -1L
                VehicleEditScreen(
                    vehicleId = id.takeIf { it >= 0 },
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = "${Routes.ADD_ENTRY}/{vehicleId}",
                arguments = listOf(navArgument("vehicleId") { type = NavType.LongType }),
            ) { entry ->
                val vid = entry.arguments?.getLong("vehicleId") ?: return@composable
                AddEntryScreen(
                    vehicleId = vid,
                    editKind = null,
                    editId = null,
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = "${Routes.EDIT_ENTRY}/{vehicleId}/{kind}/{id}",
                arguments = listOf(
                    navArgument("vehicleId") { type = NavType.LongType },
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.LongType },
                ),
            ) { entry ->
                val vid = entry.arguments?.getLong("vehicleId") ?: return@composable
                val kind = entry.arguments?.getString("kind")?.let { EntryKind.valueOf(it) }
                val id = entry.arguments?.getLong("id")
                AddEntryScreen(
                    vehicleId = vid,
                    editKind = kind,
                    editId = id,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
