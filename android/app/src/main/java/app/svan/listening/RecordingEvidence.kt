package app.svan.listening

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import app.svan.BuildConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Invoked only by the existing debug command harness; production commands remain disabled. */
internal object RecordingEvidence {
    fun write(context: Context) {
        check(BuildConfig.DEBUG)
        val state = ProofRecorder.state.value
        val data = JSONObject().put("state", state.javaClass.simpleName)
        if (state is ProofRecorder.State.Done) {
            val result = state.result
            data.put("report", result.report).put("directory", result.directory.name)
            val files = JSONArray()
            result.directory.listFiles()?.filter { it.extension in setOf("wav", "m4a", "json", "png") }?.forEach { file ->
                val audio = file.extension in setOf("wav", "m4a")
                val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val folder = "${if (audio) "Music" else "Download"}/Svan Proof/${result.directory.name}/"
                val row = JSONObject().put("name", file.name).put("privateBytes", file.length())
                context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                    arrayOf(file.name, folder), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        row.put("mediaStoreBytes", cursor.getLong(1))
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            val bytes = ByteArray(65536)
                            while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) }
                        }
                        val privateHash = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input ->
                            val bytes = ByteArray(65536)
                            while (true) { val n = input.read(bytes); if (n < 0) break; privateHash.update(bytes, 0, n) }
                        }
                        row.put("bytesEqual", md.digest().contentEquals(privateHash.digest()))
                    }
                }
                files.put(row)
            }
            data.put("files", files)
            val aac = File(result.directory, "svan-processed-output.m4a")
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(aac.path)
                val f = extractor.getTrackFormat(0); extractor.selectTrack(0)
                data.put("aac", JSONObject().put("mime", f.getString(MediaFormat.KEY_MIME))
                    .put("rate", f.getInteger(MediaFormat.KEY_SAMPLE_RATE)).put("channels", f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    .put("durationUs", f.getLong(MediaFormat.KEY_DURATION))
                    .put("aacObjectType", (f.getByteBuffer("csd-0")!!.get(0).toInt() and 255) ushr 3)
                    .put("firstPacketBytes", extractor.readSampleData(java.nio.ByteBuffer.allocate(65536), 0)))
            } finally { extractor.release() }
        }
        File(context.filesDir, "recording-evidence.json").writeText(data.toString(2))
    }
}
