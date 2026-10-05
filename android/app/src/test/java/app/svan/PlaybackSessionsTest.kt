package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionsTest {

    // Shape of `dumpsys audio` playback lines on API 31+ (fields matched
    // independently, so ordering differences between releases don't matter).
    private val dump = """
        |  playback activity as reported through PlayerBase:
        |  AudioPlaybackConfiguration piid:15 deviceId:3 type:android.media.AudioTrack u/pid:10234/5512 state:started attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null sessionId:1281 mutedState:
        |  AudioPlaybackConfiguration piid:23 deviceId:3 type:exoplayer u/pid:10187/6620 state:paused attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x400 tags= bundle=null sessionId:2049 mutedState:
        |  AudioPlaybackConfiguration piid:31 deviceId:3 type:android.media.SoundPool u/pid:1000/900 state:idle attr:AudioAttributes: usage=USAGE_ASSISTANCE_SONIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null sessionId:0 mutedState:
        |  AudioPlaybackConfiguration piid:16 deviceId:3 type:android.media.AudioTrack u/pid:10234/5512 state:stopped attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null sessionId:1281 mutedState:
        |  some unrelated line sessionId:77
    """.trimMargin()

    @Test
    fun parsesSessionsAndSkipsInvalid() {
        val s = PlaybackSessions.parse(dump).associateBy { it.sessionId }
        assertEquals(setOf(1281, 2049), s.keys) // sessionId 0 and non-config lines skipped
        assertEquals(10234, s[1281]!!.uid)
        assertEquals("USAGE_MEDIA", s[1281]!!.usage)
        assertEquals("started", s[1281]!!.state) // a stopped sibling must not hide playing audio
        assertTrue(s[1281]!!.usageCapturable)
        assertFalse(s[1281]!!.flagsBlockCapture)
    }

    @Test
    fun detectsPerPlayerCaptureOptOut() {
        val s = PlaybackSessions.parse(dump).first { it.sessionId == 2049 }
        assertEquals("paused", s.state)
        assertTrue(s.flagsBlockCapture) // FLAG_NO_MEDIA_PROJECTION = 0x400
    }

    @Test
    fun nonMediaUsageIsNotCapturable() {
        val line = "AudioPlaybackConfiguration piid:1 u/pid:10001/1 state:started attr:AudioAttributes: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH flags=0x0 sessionId:9"
        assertFalse(PlaybackSessions.parse(line).single().usageCapturable)
    }

    @Test
    fun sharedSessionPreservesCaptureOptOutFromAnyPlayer() {
        val blocked = "AudioPlaybackConfiguration u/pid:10234/4 state:paused usage=USAGE_MEDIA flags=0x400 sessionId:1281"
        val s = PlaybackSessions.parse(dump + "\n" + blocked).first { it.sessionId == 1281 }
        assertEquals("started", s.state)
        assertTrue(s.flagsBlockCapture)
    }

    @Test
    fun acceptsAlternateUidAndFieldSeparators() {
        val line = "AudioPlaybackConfiguration piid:9 clientUid=10109 state=STARTED usage=1 flags:1024 sessionId=65535"
        val s = PlaybackSessions.parse(line).single()
        assertEquals(10109, s.uid)
        assertEquals(65535, s.sessionId)
        assertEquals("started", s.state)
        assertEquals("USAGE_MEDIA", s.usage)
        assertTrue(s.flagsBlockCapture)
    }

    @Test
    fun ignoresSessionlessDirectTrackAndSessionZero() {
        val dump = """
            |AudioPlaybackConfiguration piid:1 u/pid:10109/22 state:started usage=USAGE_MEDIA flags=0x0
            |AudioPlaybackConfiguration piid:2 u/pid:10109/22 state:started usage=USAGE_MEDIA flags=0x0 sessionId:0
        """.trimMargin()
        assertTrue(PlaybackSessions.parse(dump).isEmpty())
    }
}
