package app.svan

/** Identity and playback signal only. A media session is NOT an attachable audio session. */
data class MediaPlayer(val packageName: String, val uid: Int, val state: Int?, val local: Boolean) {
    // PlaybackState.STATE_PLAYING. Buffering, connecting and unknown states prove no playback.
    val playingLocally: Boolean get() = local && state == 3
}

data class MediaPlayerSnapshot(
    val access: Boolean = false,
    val available: Boolean = false,
    val players: List<MediaPlayer> = emptyList(),
    val error: String? = null,
) {
    val playing: List<MediaPlayer> get() = if (available) players.filter { it.playingLocally }.distinctBy { it.packageName } else emptyList()
}

object MediaPlayers {
    /** Names only by full UID and only when unambiguous. Never infer a session ID, activity or capture policy. */
    fun name(ledger: Ledger, snapshot: MediaPlayerSnapshot): Ledger {
        if (!snapshot.available) return ledger
        val byUid = snapshot.players.filter { it.local && it.uid >= 0 && it.packageName.isNotBlank() }
            .groupBy { it.uid }.mapValues { (_, players) -> players.map { it.packageName }.distinct().singleOrNull() }
        fun name(s: PlaybackSession): PlaybackSession {
            if (s.packageName.isNotBlank() && !s.packageName.startsWith("uid:")) return s
            val pkg = byUid[s.uid] ?: return s
            return s.copy(packageName = pkg)
        }
        return ledger.copy(sessions = ledger.sessions.map { it.copy(session = name(it.session)) }, unresolved = ledger.unresolved.map(::name))
    }

    fun withoutAudioSession(players: List<MediaPlayer>, sessions: List<LedgerSession>): List<MediaPlayer> {
        val uniqueUids = players.groupBy { it.uid }.filterValues { it.map { p -> p.packageName }.distinct().size == 1 }.keys
        return players.filter { p -> p.playingLocally && sessions.none { s ->
            (s.session.state == "started" || s.serverActive == true) &&
                (s.session.packageName == p.packageName || (p.uid >= 0 && p.uid in uniqueUids && s.session.uid == p.uid &&
                    (s.session.packageName.isBlank() || s.session.packageName.startsWith("uid:"))))
        } }.distinctBy { it.packageName }
    }
}
