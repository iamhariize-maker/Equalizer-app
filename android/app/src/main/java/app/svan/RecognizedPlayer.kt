package app.svan

/** Identity/playback evidence only; never supplies a made-up audio session or capture verdict. */
data class RecognizedPlayer(val packageName: String, val playing: Boolean)

fun mergeRecognizedPlayers(observed: List<WorkingPlayer>, recognized: List<RecognizedPlayer>, label: (String) -> String): List<WorkingPlayer> {
    val known = observed.map { it.key }.toSet()
    return observed + recognized.filter { it.packageName !in known }.distinctBy { it.packageName }.map {
        WorkingPlayer(it.packageName, label(it.packageName), it.playing, false,
            reason = "Player recognized. Waiting for its audio connection; restart the song or try enhanced detection.")
    }
}
