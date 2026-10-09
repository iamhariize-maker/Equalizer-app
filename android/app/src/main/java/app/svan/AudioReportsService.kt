package app.svan

import android.os.ParcelFileDescriptor
import android.os.Process
import rikka.shizuku.SystemServiceHelper

/** Shizuku constructs this Binder in a shell process, not an Android Service. */
class AudioReportsService : IAudioReports.Stub() {
    override fun destroy() { kotlin.system.exitProcess(0) }

    override fun openReport(report: Int): ParcelFileDescriptor {
        check(Process.myUid() == 2000 || Process.myUid() == 0) { "Audio reports require shell identity" }
        val name = when (report) {
            0 -> "audio"
            1 -> "media.audio_flinger"
            2 -> "media.audio_policy"
            else -> throw IllegalArgumentException("Unsupported audio report")
        }
        val binder = SystemServiceHelper.getSystemService(name) ?: error("Audio service unavailable")
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            pipe[1].use { binder.dumpAsync(it.fileDescriptor, emptyArray()) }
            return pipe[0]
        } catch (e: Exception) {
            pipe[0].close()
            throw e
        }
    }
}
