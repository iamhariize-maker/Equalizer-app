package app.svan

import org.junit.Assert.*
import org.junit.Test

class MusicSourcePolicyTest {
    private val music=PlaybackSession(1,10234,"USAGE_MEDIA","started",0,"com.spotify.music")
    @Test fun rejectsUtilitiesSystemSoundsAndSonificationEvenWhenMislabelledMedia() {
        for(s in listOf(music.copy(packageName="com.rapido.passenger"),music.copy(uid=1000),
            music.copy(uid=101000),music.copy(usage="USAGE_NOTIFICATION"),
            music.copy(playerType="android.media.SoundPool"),music.copy(contentType="CONTENT_TYPE_SONIFICATION"))) {
            assertNotNull(MusicSourcePolicy.exclusion(s))
            assertFalse(MusicSourceGate().admit(s,0,true))
        }
        assertNull(MusicSourcePolicy.exclusion(music.copy(uid=110234)))
    }
    @Test fun shortUnknownPlayersNeverEnterButSustainedPlayersDo() {
        val gate=MusicSourceGate();val source=music.copy(packageName="a.new.player",usage="USAGE_UNKNOWN")
        assertFalse(gate.admit(source,0,false));assertFalse(gate.admit(source,1000,false))
        assertTrue(gate.admit(source,1500,false))
        assertFalse(gate.admit(source.copy(state="paused"),1600,false))
        assertFalse(gate.admit(source,1700,false))
        assertFalse(gate.admit(source.copy(uid=10235),4000,false))
    }
    @Test fun knownMusicAndExplicitMusicStartImmediatelyAndExistingRoutesSurvivePauses() {
        val gate=MusicSourceGate()
        assertTrue(gate.admit(music,0,false))
        assertTrue(gate.admit(music.copy(packageName="new.player",contentType="CONTENT_TYPE_MUSIC"),0,false))
        assertTrue(gate.admit(music.copy(packageName="new.player",state="paused"),0,true))
    }
    @Test fun parsingPreservesMusicAndSonificationContent() {
        for ((raw,expected) in listOf("2" to "CONTENT_TYPE_MUSIC","4" to "CONTENT_TYPE_SONIFICATION","CONTENT_TYPE_SONIFICATION" to "CONTENT_TYPE_SONIFICATION")) {
            val s=PlaybackSessions.parse("piid:1 u/pid:10234/50 state:started usage=MEDIA content=$raw sessionId:2").single()
            assertEquals(expected,s.contentType)
        }
    }
}
