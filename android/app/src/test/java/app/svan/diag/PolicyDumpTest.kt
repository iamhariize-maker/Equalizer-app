package app.svan.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client block layout is from ClientDescriptor::dump / TrackClientDescriptor::dump in android14-release. */
class PolicyDumpTest {
    private val dump = """
Output 29 (AudioOut_1D):
  Clients:
    Port ID: 77; Session ID: 42225; uid 10520; State: Active
      AUDIO_FORMAT_PCM_16_BIT; 48000; Channel mask: 0x3
      Attributes: { Content type: AUDIO_CONTENT_TYPE_MUSIC Usage: AUDIO_USAGE_MEDIA Source: AUDIO_SOURCE_DEFAULT Flags: 0x00000000 Tags: }
      Stream: 3; Flags: 00000000; Refcount: 1
      DAP Primary Mix: 0x0
      DAP Secondary Outputs: 91, 
    Port ID: 78; Session ID: 99; uid 10999; State: Inactive
      AUDIO_FORMAT_PCM_16_BIT; 44100; Channel mask: 0x3
      Attributes: { Content type: AUDIO_CONTENT_TYPE_MUSIC Usage: AUDIO_USAGE_MEDIA Source: AUDIO_SOURCE_DEFAULT Flags: 0x00001400 Tags: }
      Stream: 3; Flags: 00000000; Refcount: 0
      DAP Primary Mix: 0x0

AllowedCapturePolicies:
  - uid=10999 flag_mask=0x1400
""".trimIndent()

    @Test fun parsesEveryClientWithItsFields() {
        val clients = PolicyDump.clients(dump)
        assertEquals(2, clients.size)
        val a = clients[0]
        assertEquals(77, a.portId); assertEquals(42225, a.sessionId); assertEquals(10520, a.uid); assertTrue(a.active)
        assertEquals("AUDIO_USAGE_MEDIA", a.usage)
        assertEquals(0, a.attributeFlags)
        assertEquals(listOf(91), a.secondaryOutputs)
        assertTrue(a.attachedToCaptureMix)
        assertFalse(a.blocksMediaProjection); assertFalse(a.blocksSystemCapture)
    }

    @Test fun readsTheCaptureOptOutFlagsFromTheEffectiveAttributes() {
        val b = PolicyDump.forUid(dump, 10999).single()
        assertEquals(0x1400, b.attributeFlags)
        assertTrue(b.blocksMediaProjection); assertTrue(b.blocksSystemCapture)
        assertFalse(b.active)
    }

    @Test fun aTrackWithoutASecondaryOutputsLineIsNotAttachedToAnyMix() {
        val b = PolicyDump.forUid(dump, 10999).single()
        assertFalse(b.hasSecondaryLine); assertFalse(b.attachedToCaptureMix)
    }

    @Test fun otherUidsAreFilteredOut() {
        assertEquals(emptyList<PolicyDump.Client>(), PolicyDump.forUid(dump, 12345))
    }

    @Test fun unreadableAttributesLeaveFlagsUnknownInsteadOfGuessing() {
        val odd = "Port ID: 1; Session ID: 2; uid 10001; State: Active\n  PCM; 48000\n  Attributes: <redacted by vendor>\n"
        val c = PolicyDump.clients(odd).single()
        assertNull(c.attributeFlags)
        assertFalse(c.blocksMediaProjection)
    }

    @Test fun decimalFlagsAreAccepted() {
        val d = "Port ID: 1; Session ID: 2; uid 10001; State: Active\n  PCM\n  Attributes: { Usage: AUDIO_USAGE_MEDIA flags: 1024 }\n"
        assertEquals(1024, PolicyDump.clients(d).single().attributeFlags)
        assertTrue(PolicyDump.clients(d).single().blocksMediaProjection)
    }

    @Test fun capturePolicyTableIsExtractedRaw() {
        val table = PolicyDump.capturePolicyTable(dump)
        assertTrue(table.single().contains("uid=10999 flag_mask=0x1400"))
    }

    @Test fun numberTokensDoNotMatchInsideLongerNumbers() {
        val t = Excerpt.numberToken(42225)
        assertTrue(t.containsMatchIn(" 42225 "))
        assertFalse(t.containsMatchIn("142225"))
        assertFalse(t.containsMatchIn("422251"))
    }

    @Test fun flingerViewKeepsTheThreadHeaderTrackRowAndEffectChainOfOneSession() {
        val flinger = """
Output thread 0x1, name AudioOut_1D, tid 12, type 0 (MIXER):
  Tracks of which 2 are active
    Type Id Active Client Session
       13 yes 4100 42225 58 A
       14 no 4101 11111 59 I
  1 effects for session 42225
    Effect ID 31:
      - name: Dynamics Processing
Output thread 0x2, name AudioOut_2D, tid 13, type 1 (DIRECT):
  1 Tracks
    15 yes 777 33333
""".trimIndent()
        val lines = FlingerView.forSession(flinger, 42225)
        assertTrue(lines.any { it.startsWith("[thread]") && it.contains("AudioOut_1D") })
        assertTrue(lines.any { it.contains("42225 58 A") })
        assertTrue(lines.any { it.contains("Dynamics Processing") })
        assertFalse(lines.any { it.contains("33333") })
        assertFalse(lines.any { it.contains("11111") })
    }

    @Test fun playbackRecordsAreFilteredByUid() {
        val lines = listOf("AudioPlaybackConfiguration piid:1 u/pid:10520/1234 state:started", "AudioPlaybackConfiguration piid:2 u/pid:10521/999 state:started")
        assertEquals(1, PlaybackRecords.forUid(lines, 10520).size)
    }
}
