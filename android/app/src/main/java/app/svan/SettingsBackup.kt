package app.svan

import android.content.Context
import app.svan.model.*
import app.svan.svaramanas.SmartRequest
import app.svan.svaramanas.Svaramanas
import app.svan.svaramanas.TuningSignature
import app.svan.svaramanas.TuningSignatures
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** User-selected settings migration. Contains no recordings, credentials or Android grants. */
object SettingsBackup {
    const val MAX_BYTES = 3_000_000
    /** [signatures] is null for a backup made before tuning signatures existed: restoring it leaves them untouched. */
    data class Snapshot(val eq: EqState, val settings: AudioSettings, val request: SmartRequest,
        val presets: List<Preset>, val curves: Map<String,String>,
        val signatures: List<TuningSignature?>? = null) {
        fun encode(): String = JSONObject().put("format","svan-settings").put("version",1)
            .put("eq",eq.toJson()).put("settings",settings.toJson()).put("request",request.toJson())
            .put("presets",JSONArray().apply { presets.forEach { put(it.toJson()) } })
            .put("curves",JSONObject(curves)).also { o -> signatures?.let { o.put("signatures",TuningSignatures.toJson(it)) } }.toString()
    }
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun decode(text: String): Snapshot {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Backup is too large" }
        val root=JSONObject(text)
        require(root.getString("format")=="svan-settings" && root.getInt("version")==1) { "Unsupported settings backup" }
        validateJson(root)
        val eq=EqState.fromJson(root.getJSONObject("eq"))
        val settings=AudioSettings.fromJson(root.getJSONObject("settings"))
        val request=SmartRequest.fromJson(root.getJSONObject("request"))
        require(request.strength in 0.0..1.5) { "Invalid sound-guide strength" }
        val p=root.getJSONArray("presets");require(p.length()<=100) { "Too many presets" }
        val presets=List(p.length()) { Preset.fromJson(p.getJSONObject(it)) }
        require(presets.map {it.name}.distinct().size==presets.size) { "Duplicate presets" }
        fun bands(list: List<Band>) {
            require(list.size<=256) { "Too many filters" }
            require(list.all { it.freqHz in 10.0..24000.0 && it.gainDb in -60.0..24.0 && it.q in .05..50.0 }) { "Invalid filter" }
        }
        bands(eq.bands);eq.tuning?.let {bands(it.bands)};presets.forEach { bands(it.bands);require(it.preampDb in -60.0..24.0) }
        eq.tuning?.let { t ->
            require(t.fitRmsDb.isFinite() && t.bassDb.isFinite() && t.tiltDbPerOct.isFinite())
            t.calibration?.let {require(it.maxErrorDb.isFinite() && it.lowHz.isFinite() && it.highHz.isFinite())}
        }
        require(eq.preampDb in -60.0..24.0 && eq.graphicGains.all {it in -24.0..24.0}) { "Invalid EQ gain" }
        require(eq.bass.amountDb in -6.0..12.0 && eq.bass.focusHz in 40.0..160.0 && eq.bass.character in -1.0..1.0)
        require(eq.vocal.intimacy in 0.0..1.0 && eq.vocal.warmth in -1.0..1.0 && eq.vocal.smoothness in 0.0..1.0)
        require(eq.instrument.space in -1.0..1.0 && eq.instrument.instruments in 0.0..1.0)
        val c=root.getJSONObject("curves");require(c.length()<=2)
        val curves=c.keys().asSequence().associateWith { key ->
            require(key.matches(Regex("[0-9a-f]{64}"))) { "Invalid curve identity" }
            c.getString(key).also { require(it.length<=1_000_000 && hash(it)==key) { "Damaged calibration curve" } }
        }
        val signatures=root.optJSONArray("signatures")?.let { TuningSignatures.fromJsonStrict(it) }
        return Snapshot(eq,settings,request,presets,curves,signatures)
    }
    private fun validateJson(value: Any?, depth: Int=0) {
        require(depth<=12) { "Backup nesting is too deep" }
        when(value) {
            is JSONObject -> { require(value.length()<=512);value.keys().forEach {validateJson(value.get(it),depth+1)} }
            is JSONArray -> { require(value.length()<=512);repeat(value.length()){validateJson(value.get(it),depth+1)} }
            is Number -> require(value.toDouble().isFinite()) { "Non-finite setting" }
            is String -> require(value.length<=1_000_000)
        }
    }
    fun export(context: Context): String {
        val eq=SvanRepository.eq.value
        val hashes=eq.tuning?.calibration?.let {listOf(it.measurementHash,it.targetHash)} ?: emptyList()
        val curves=hashes.distinct().filter {it.matches(Regex("[0-9a-f]{64}"))}.mapNotNull {h ->
            File(context.filesDir,"calibration/$h.txt").takeIf {it.isFile}?.readText()?.let {h to it}
        }.toMap()
        val text=Snapshot(eq,SvanRepository.settings.value,Svaramanas.request.value,SvanRepository.userPresets.value,curves,
            Svaramanas.signatures.value).encode()
        decode(text) // Never produce a backup that cannot be restored.
        return text
    }
    fun restore(context: Context,text: String) {
        val s=decode(text) // Validate every field before changing any sound setting.
        val dir=File(context.filesDir,"calibration");check(dir.isDirectory || dir.mkdirs())
        s.curves.forEach {(hash,curve)->File(dir,"$hash.txt").writeText(curve)}
        Svaramanas.update {it.copy(enabled=false)}
        SvanRepository.update {s.eq.copy(smart=null,smartBypass=false)}
        SvanRepository.updateSettings {s.settings}
        SvanRepository.restoreUserPresets(s.presets)
        s.signatures?.let { Svaramanas.restoreSignatures(it) }
        Svaramanas.update {s.request}
    }
}
