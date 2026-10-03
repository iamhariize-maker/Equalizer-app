package app.svan.tuning

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Headphone measurements, published corrections and target curves from
 * AutoEq (github.com/jaakkopasanen/AutoEq, MIT licence), fetched on demand
 * and cached on the device.
 */
object AutoEqSource {

    private const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"
    private val ENTRY = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?)(?: on (.+))?$""")
    private val FORMS = listOf("over-ear", "in-ear", "earbud")
    /** Rigs whose data need rig-specific targets AutoEq doesn't publish for every signature. */
    private val SPECIAL_RIGS = setOf("Bruel & Kjaer 5128", "HMS II.3")

    data class Entry(
        val name: String,
        val source: String,
        val form: String,     // over-ear / in-ear / earbud
        val rig: String?,     // e.g. "GRAS RA0045", null = source default
        val resultPath: String, // results/<...>/<name>
    ) {
        val label: String get() = "$source" + (rig?.let { " · $it" } ?: "") + " · $form"
        val measurementPath: String
            get() = "measurements/$source/data/$form/" + (rig?.let { "$it/" } ?: "") + "$name.csv"
        /** Raw data exists for most sources; crinacle's isn't redistributed. */
        val hasRawData: Boolean get() = source != "crinacle"
        val supportsCustomTargets: Boolean get() = hasRawData && rig !in SPECIAL_RIGS
    }

    @Volatile private var index: List<Entry>? = null

    /** All ~9k entries. Network on first use, then cached for a week. */
    fun index(context: Context): List<Entry> {
        index?.let { return it }
        val text = cached(context, "results/INDEX.md", maxAgeMs = 7L * 24 * 3600 * 1000)
        val parsed = text.lineSequence().mapNotNull { parse(it.trim()) }.toList()
        index = parsed
        return parsed
    }

    fun search(context: Context, query: String, limit: Int = 60): List<Entry> {
        val all = index(context)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        return all.asSequence()
            .filter { e -> words.all { w -> e.name.lowercase().contains(w) } }
            .sortedWith(compareBy({ !it.name.lowercase().startsWith(words.first()) }, { it.name.length }))
            .take(limit).toList()
    }

    fun parse(line: String): Entry? {
        val m = ENTRY.matchEntire(line) ?: return null
        val name = m.groupValues[1]
        val path = URLDecoder.decode(m.groupValues[2].replace("+", "%2B"), "UTF-8")
        val parts = path.split('/')
        if (parts.size < 3) return null
        val source = parts[0]
        val rigForm = parts[1]
        val form = FORMS.firstOrNull { rigForm.endsWith(it) } ?: return null
        val rig = rigForm.removeSuffix(form).trim().ifEmpty { null }
        return Entry(name, source, form, rig, "results/$path")
    }

    fun measurement(context: Context, e: Entry): String = cached(context, e.measurementPath)

    /** AutoEq's published correction (its default target for that rig). */
    fun publishedCorrection(context: Context, e: Entry): String =
        cached(context, "${e.resultPath}/${e.name} GraphicEQ.txt")

    fun target(context: Context, file: String): String = cached(context, "targets/$file.csv")

    private fun cached(context: Context, path: String, maxAgeMs: Long = Long.MAX_VALUE): String {
        val dir = File(context.cacheDir, "autoeq").apply { mkdirs() }
        val f = File(dir, path.hashCode().toUInt().toString(16) + "-" + path.substringAfterLast('/').take(60).replace(Regex("[^A-Za-z0-9._-]"), "_"))
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readText()
        val text = try {
            download("$RAW/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
        } catch (e: Exception) {
            if (f.exists()) return f.readText() // offline: stale cache beats nothing
            throw e
        }
        f.writeText(text)
        return text
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Sound signatures offered to the user, mapped to AutoEq target files. */
enum class Signature(val title: String, val detail: String) {
    PUBLISHED("Reviewer's profile", "AutoEq's published correction for this exact measurement (Harman-based)."),
    HARMAN("Harman", "The research target most listeners prefer: warm, full bass, smooth treble."),
    HARMAN_LIGHT_BASS("Harman · lighter bass", "Harman without its bass boost — cleaner, more neutral low end."),
    NEUTRAL("Neutral", "Diffuse-field studio reference. Accurate, less bass."),
    ORATORY("oratory1990 optimum", "Tuning by renowned measurer oratory1990."),
    AUTOEQ_IN_EAR("AutoEq in-ear", "AutoEq's own balanced in-ear target."),
    CUSTOM("Imported target", "Any target curve file — e.g. downloaded from a Squiglink site.");

    /** AutoEq target file for this signature and headphone, or null if not offered. */
    fun targetFile(e: AutoEqSource.Entry): String? {
        val over = e.form == "over-ear"
        val prefix = when (e.source) {
            "Innerfidelity" -> "Innerfidelity "
            "Headphone.com Legacy" -> "Headphone.com Legacy "
            else -> ""
        }
        return when (this) {
            PUBLISHED, CUSTOM -> null
            HARMAN -> if (over) "${prefix}Harman over-ear 2018" else "${prefix}Harman in-ear 2019"
            HARMAN_LIGHT_BASS -> if (over) "${prefix}Harman over-ear 2018 without bass" else "${prefix}Harman in-ear 2019 without bass"
            NEUTRAL -> if (over && prefix.isEmpty()) "Diffuse field ISO 11904-1" else null
            ORATORY -> if (prefix.isNotEmpty()) null else if (over) "oratory1990 optimum hifi over-ear" else "oratory1990 in-ear"
            AUTOEQ_IN_EAR -> if (over) null else "${prefix}AutoEq in-ear"
        }
    }

    companion object {
        fun available(e: AutoEqSource.Entry): List<Signature> = buildList {
            add(PUBLISHED)
            if (e.supportsCustomTargets) {
                entries.filter { it != PUBLISHED && it != CUSTOM && it.targetFile(e) != null }.forEach(::add)
                add(CUSTOM)
            }
        }
    }
}
