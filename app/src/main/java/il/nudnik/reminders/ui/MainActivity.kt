package il.nudnik.reminders.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import il.nudnik.reminders.data.db.TaskEntity
import il.nudnik.reminders.graph
import il.nudnik.reminders.ui.screens.AlertsScreen
import il.nudnik.reminders.ui.screens.SettingsScreen
import il.nudnik.reminders.ui.screens.TaskEditorDialog
import il.nudnik.reminders.ui.screens.TasksScreen
import il.nudnik.reminders.ui.theme.NudnikTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NudnikTheme { MainScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("נודניק") },
                actions = {
                    Text(if (settings.enabled) "פעיל" else "כבוי", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(enabled = on) } },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Notifications, null) }, label = { Text("תזכורות") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, null) }, label = { Text("משימות") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("הגדרות") },
                )
            }
        },
        floatingActionButton = {
            if (tab == 1) {
                FloatingActionButton(onClick = { editing = TaskEntity(title = "") }) {
                    Icon(Icons.Filled.Add, "משימה חדשה")
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (tab) {
            0 -> AlertsScreen(vm, mod, header = { PermissionsCard(onChanged = vm::refresh) })
            1 -> TasksScreen(vm, mod, onEdit = { editing = it })
            else -> SettingsScreen(vm, mod)
        }
    }

    editing?.let { t ->
        TaskEditorDialog(
            initial = t,
            onDismiss = { editing = null },
            onSave = { vm.saveTask(it); editing = null },
        )
    }
}

private data class PermState(
    val calendar: Boolean,
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val battery: Boolean,
) {
    val allGood get() = calendar && notifications && exactAlarms && fullScreen && battery
}

private fun readPerms(ctx: Context): PermState {
    val g = ctx.graph
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val pm: PowerManager = requireNotNull(ctx.getSystemService(PowerManager::class.java))
    return PermState(
        calendar = g.calendar.hasPermission(),
        notifications = notif,
        exactAlarms = g.scheduler.canExact(),
        fullScreen = g.notifier.canFullScreen(),
        battery = pm.isIgnoringBatteryOptimizations(ctx.packageName),
    )
}

@Composable
private fun PermissionsCard(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(readPerms(ctx)) }
    LifecycleResumeEffect(Unit) {
        state = readPerms(ctx)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state = readPerms(ctx)
        onChanged()
    }
    if (state.allGood) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("כדי שנודניק יוכל להציק כמו שצריך:", style = MaterialTheme.typography.titleSmall)
            }
            if (!state.calendar || !state.notifications) {
                PermRow("גישה ליומן ולהתראות", Icons.Filled.DateRange) {
                    val perms = buildList {
                        add(Manifest.permission.READ_CALENDAR)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    launcher.launch(perms.toTypedArray())
                }
            }
            if (!state.exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermRow("התראות בזמן מדויק", Icons.Filled.Notifications) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri())
                    )
                }
            }
            if (!state.fullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PermRow("חלון קופץ על מסך הנעילה", Icons.Filled.Notifications) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${ctx.packageName}".toUri())
                    )
                }
            }
            if (!state.battery) {
                PermRow("החרגה מחיסכון בסוללה (מומלץ)", Icons.Filled.Settings) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
