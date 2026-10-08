package app.svan

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import app.svan.ui.EqScreen
import app.svan.ui.Svan
import app.svan.ui.SvanTheme
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Process

/** Standard player EQ-panel entry point; never accepts Svan's scripted command extras. */
class EffectControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.action == AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) {
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage.orEmpty()
            val uid = runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
            val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val callerMatches = callingPackage == null || callingPackage == pkg
            SvanRepository.init(this)
            SessionRouter.init(this)
            if (callerMatches && SessionAnnouncement.valid(sid, pkg, uid, Process.myUid()) &&
                intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC) != AudioEffect.CONTENT_TYPE_VOICE) {
                SystemEqService.openEffectPanel(this, sid, pkg, uid)
            }
            setContent {
                SvanTheme {
                    Column(Modifier.fillMaxSize().background(Svan.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Box(Modifier.weight(1f)) {
                            EqScreen(onOpenDetection = { startActivity(Intent(this@EffectControlActivity, MainActivity::class.java)) })
                        }
                        TextButton(onClick = { setResult(RESULT_OK); finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
            }
            return
        }
        finish()
    }
}
