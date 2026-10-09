package app.svan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.svan.AppCaptureStatus
import app.svan.CaptureStanding
import app.svan.CaptureStatusBoard

/**
 * Which of your players can use the audiophile engine, and which stay on system effects because the app itself
 * forbids capture. Evidence only: nothing here is inferred from an app's name or from silence.
 */
@Composable
fun CaptureStatusCard() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppCaptureStatus>()) }
    ObserveWhileVisible { apps = CaptureStatusBoard.collect(context) }
    if (apps.isEmpty()) return
    SectionLabel("Which apps can use the audiophile engine")
    SvanCard {
        Column {
            Text("The audiophile engine needs an app to allow capture. Apps that don't are still equalised through system effects.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            apps.forEachIndexed { index, app ->
                Spacer(Modifier.height(if (index == 0) 8.dp else 10.dp))
                Text(app.label + (app.versionName?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(app.headline, style = MaterialTheme.typography.bodySmall, color = when (app.standing) {
                    CaptureStanding.FULL -> Svan.Tulsi
                    CaptureStanding.SYSTEM_ONLY_BY_APP -> Svan.Ash
                    CaptureStanding.UNCONFIRMED -> Svan.Gold
                    else -> Svan.TextMuted
                })
                Text(app.detail, style = MaterialTheme.typography.labelSmall, color = Svan.TextFaint)
            }
        }
    }
}
