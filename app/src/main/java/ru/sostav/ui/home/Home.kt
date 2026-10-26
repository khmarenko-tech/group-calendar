package ru.sostav.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import ru.sostav.data.Graph
import ru.sostav.data.Profile
import ru.sostav.logic.DayPart
import ru.sostav.ui.GroupDataViewModel
import ru.sostav.ui.availability.AbsenceFormScreen
import ru.sostav.ui.availability.AvailabilityScreen
import ru.sostav.ui.calendar.CalendarScreen
import ru.sostav.ui.concert.ConcertDetailScreen
import ru.sostav.ui.concert.ConcertFormScreen
import ru.sostav.ui.concert.ConcertListScreen
import ru.sostav.ui.group.GroupScreen
import ru.sostav.ui.inbox.InboxScreen

private val tabs = listOf(
    Tab("calendar", "Календарь", Icons.Filled.DateRange),
    Tab("concerts", "Концерты", Icons.Filled.Event),
    Tab("me", "Я", Icons.Filled.Person),
    Tab("group", "Группа", Icons.Filled.Group),
)

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun Home(profile: Profile, onSignOut: () -> Unit) {
    val data: GroupDataViewModel = viewModel()
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(profile.groupId, profile.uid) {
        data.start(profile.groupId.orEmpty(), profile.uid, profile.displayName)
        onDispose { data.stop() }
    }
    LaunchedEffect(profile.displayName) {
        data.myName = profile.displayName
    }
    LaunchedEffect(profile.groupId) {
        val groupId = profile.groupId ?: return@LaunchedEffect
        runCatching { Graph.groups.heal(profile.uid, groupId) }
    }
    LaunchedEffect(profile.uid) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            Graph.accounts.saveToken(profile.uid, token)
        }
    }
    LaunchedEffect(data.error) {
        val message = data.error ?: return@LaunchedEffect
        snack.showSnackbar(message)
        data.error = null
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route?.substringBefore("?").orEmpty()
    val showBar = route in tabs.map { it.route }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        if (showBar) {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        }
    }) { padding ->
        NavHost(
            navController = nav,
            startDestination = "calendar",
            modifier = Modifier.padding(padding),
        ) {
            composable("calendar") {
                CalendarScreen(
                    data = data,
                    onOpenConcert = { nav.navigate("concert/$it") },
                    onCreateConcert = { date, part -> nav.navigate("concert-create?date=$date&part=${part.wire()}") },
                    onEditAbsence = { nav.navigate("absence?id=$it&date=") },
                    onInbox = { nav.navigate("inbox") },
                )
            }
            composable("concerts") {
                ConcertListScreen(
                    data = data,
                    onOpen = { nav.navigate("concert/$it") },
                    onCreate = { nav.navigate("concert-create?date=&part=") },
                    onInbox = { nav.navigate("inbox") },
                )
            }
            composable("me") {
                AvailabilityScreen(
                    data = data,
                    onCreate = { nav.navigate("absence?id=&date=") },
                    onEdit = { nav.navigate("absence?id=$it&date=") },
                    onInbox = { nav.navigate("inbox") },
                )
            }
            composable("group") {
                GroupScreen(data = data, onSignOut = onSignOut, onInbox = { nav.navigate("inbox") })
            }
            composable("inbox") {
                InboxScreen(
                    data = data,
                    onBack = { nav.popBackStack() },
                    onOpen = { id ->
                        nav.popBackStack()
                        nav.navigate("concert/$id")
                    },
                )
            }
            composable(
                route = "concert/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ConcertDetailScreen(
                    data = data,
                    concertId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("concert/$it/edit") },
                )
            }
            composable(
                route = "concert/{id}/edit",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                ConcertFormScreen(
                    data = data,
                    concertId = entry.arguments?.getString("id"),
                    initialDate = "",
                    initialPart = "",
                    onBack = { nav.popBackStack() },
                    onDone = { _ -> nav.popBackStack() },
                )
            }
            composable(
                route = "concert-create?date={date}&part={part}",
                arguments = listOf(
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                    navArgument("part") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                ConcertFormScreen(
                    data = data,
                    concertId = null,
                    initialDate = entry.arguments?.getString("date").orEmpty(),
                    initialPart = entry.arguments?.getString("part").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onDone = { id ->
                        nav.popBackStack()
                        if (!id.isNullOrBlank()) nav.navigate("concert/$id")
                    },
                )
            }
            composable(
                route = "absence?id={id}&date={date}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                AbsenceFormScreen(
                    data = data,
                    absenceId = entry.arguments?.getString("id").orEmpty(),
                    initialDate = entry.arguments?.getString("date").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}

private fun DayPart.wire(): String = when (this) {
    DayPart.MORNING -> "morning"
    DayPart.AFTERNOON -> "afternoon"
    DayPart.EVENING -> "evening"
}
