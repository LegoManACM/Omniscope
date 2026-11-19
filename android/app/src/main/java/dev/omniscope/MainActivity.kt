package dev.omniscope

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.omniscope.ui.ArScreen
import dev.omniscope.ui.FilterSheet
import dev.omniscope.ui.ItemPage
import dev.omniscope.ui.ListScreen
import dev.omniscope.ui.MapScreen
import dev.omniscope.ui.NavScreen
import dev.omniscope.ui.PhIcon
import dev.omniscope.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0E0F11), surface = Color(0xFF16181B))) { Root(app) }
        }
    }
}

private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private enum class Tab(@DrawableRes val icon: Int, val label: String) {
    AR(R.drawable.ph_scan, "AR"), MAP(R.drawable.ph_map_pin, "Map"), LIST(R.drawable.ph_list_bullets, "List"),
    NAV(R.drawable.ph_steering_wheel, "Nav"), SETTINGS(R.drawable.ph_gear_six, "Settings"),
}

@Composable
private fun Root(app: App) {
    val context = LocalContext.current
    var permsVersion by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsVersion++ }
    val hasLocation = remember(permsVersion) {
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val hasCamera = remember(permsVersion) { granted(context, Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) { if (!hasLocation || !hasCamera) launcher.launch(PERMISSIONS) }

    // Sensors, GPS and fetching run only while the app is on screen. Re-registering after a permission grant
    // replays ON_START, which restarts location with the new permission.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, permsVersion) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> app.resume()
                Lifecycle.Event.ON_STOP -> app.pause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var tab by rememberSaveable { mutableStateOf(Tab.AR) }
    var filterOpen by remember { mutableStateOf(false) }
    val opened by app.opened.collectAsState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Scan mode belongs to the AR view.
    LaunchedEffect(tab) { if (tab != Tab.AR) { app.scanMode.value = false; app.scan.clear() } }

    // AR and Nav are used hands-up or on a mount: keep the screen on while they're showing.
    val view = LocalView.current
    DisposableEffect(tab) {
        view.keepScreenOn = tab == Tab.AR || tab == Tab.NAV
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color(0xFF0E0F11),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF121416)) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { PhIcon(t.icon, if (tab == t) Color.White else Color(0xFF9E9E9E), 24.dp) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF2E48DD).copy(alpha = 0.45f)),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                when (tab) {
                    Tab.AR -> if (hasCamera) ArScreen(app, statusTop) { filterOpen = true }
                        else NeedPermission("AR needs the camera.") { launcher.launch(PERMISSIONS) }
                    Tab.MAP -> MapScreen(app, statusTop) { filterOpen = true }
                    Tab.LIST -> ListScreen(app, statusTop)
                    Tab.NAV -> NavScreen()
                    Tab.SETTINGS -> SettingsScreen(app, statusTop)
                }
                if (!hasLocation && tab != Tab.SETTINGS && tab != Tab.NAV) {
                    Box(Modifier.align(Alignment.Center).background(Color(0xCC000000))) {
                        NeedPermission("Location is needed to find things around you.") { launcher.launch(PERMISSIONS) }
                    }
                }
            }
        }
        if (filterOpen) FilterSheet(app, onManagePlugins = { filterOpen = false; tab = Tab.SETTINGS }) { filterOpen = false }
        opened?.let { item -> ItemPage(app, item) { app.opened.value = null } }
    }
}

@Composable
private fun NeedPermission(text: String, onAsk: () -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White)
        Button(onClick = onAsk, modifier = Modifier.padding(top = 8.dp)) { Text("Grant permissions") }
    }
}
