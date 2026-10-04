package app.svan

import android.os.Process
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Runs once under the shell identity explicitly approved through Shizuku. */
class DetectionGrantService : IDetectionGrant.Stub() {
    override fun enableDetection(): String {
        if (Process.myUid() != 2000 && Process.myUid() != 0) return "Android setup service is not running as shell."
        return try {
            // Fixed target and permission: never accept caller-supplied commands.
            val process = ProcessBuilder("/system/bin/pm", "grant", "app.svan", "android.permission.DUMP")
                .redirectErrorStream(true).start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Android took too long to grant detection access. Try again."
            } else {
                val detail = process.inputStream.bufferedReader().use { it.readText().take(800) }
                if (process.exitValue() == 0) "OK" else "Android rejected detection access: $detail"
            }
        } catch (e: Exception) {
            "Detection setup failed: ${e.message}"
        }
    }

    override fun destroy() { exitProcess(0) }
}
