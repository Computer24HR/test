package il.nudnik.reminders.ui.popup

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import il.nudnik.reminders.data.NudnikSettings
import il.nudnik.reminders.data.ShakeAction
import il.nudnik.reminders.data.db.AlertEntity
import il.nudnik.reminders.graph
import il.nudnik.reminders.ui.screens.ActiveAlertCard
import il.nudnik.reminders.ui.theme.NudnikTheme
import kotlinx.coroutines.launch

/** Full-screen nag shown over the lock screen. Lists every ringing reminder. */
class PopupActivity : ComponentActivity() {
    private var shake: ShakeDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        val g = graph
        setContent {
            NudnikTheme {
                val all by g.db.alertDao().observeVisible().collectAsStateWithLifecycle(initialValue = null)
                val s by g.settings.flow.collectAsStateWithLifecycle(initialValue = NudnikSettings())
                val ringing = all?.filter {
                    it.state == AlertEntity.STATE_ACTIVE || it.state == AlertEntity.STATE_EXHAUSTED
                }
                LaunchedEffect(ringing) {
                    if (ringing != null && ringing.isEmpty()) finish()
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.safeDrawingPadding().padding(8.dp)) {
                        Text(
                            "נודניק מזכיר לך!",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                        if (s.shakeAction != ShakeAction.NONE) {
                            Text(
                                "נער את המכשיר כדי ל" + if (s.shakeAction == ShakeAction.DISMISS) "סגור הכל" else "דחות הכל",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(ringing.orEmpty(), key = { it.id }) { a ->
                                ActiveAlertCard(
                                    a = a, snoozeMin = s.snoozeMin, big = true,
                                    onDismiss = { lifecycleScope.launch { g.engine.dismiss(a.id) } },
                                    onSnooze = { m -> lifecycleScope.launch { g.engine.snooze(a.id, m) } },
                                )
                            }
                        }
                        if ((ringing?.size ?: 0) > 1) {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { lifecycleScope.launch { g.engine.snoozeAll(s.snoozeMin) } },
                                    modifier = Modifier.weight(1f),
                                ) { Text("נודניק לכולם") }
                                Button(
                                    onClick = { lifecycleScope.launch { g.engine.dismissAll() } },
                                    modifier = Modifier.weight(1f),
                                ) { Text("סגור הכל") }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shake = ShakeDetector(this) { onShake() }.also { it.start() }
    }

    override fun onPause() {
        shake?.stop()
        shake = null
        super.onPause()
    }

    private fun onShake() {
        lifecycleScope.launch {
            val g = graph
            val s = g.settings.get()
            when (s.shakeAction) {
                ShakeAction.DISMISS -> g.engine.dismissAll()
                ShakeAction.SNOOZE -> g.engine.snoozeAll(s.snoozeMin)
                ShakeAction.NONE -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_ALERT_ID = "alert_id"
    }
}
