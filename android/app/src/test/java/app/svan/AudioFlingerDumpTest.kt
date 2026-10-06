package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layouts follow the android14-release AudioFlinger sources (Tracks.cpp, Threads.cpp, Effects.cpp). */
class AudioFlingerDumpTest {
    companion object {
        const val OWN_PID = 777

        /** A phone playing YouTube Music (pid 4100) through Bluetooth, Neutron (pid 5200) on a DIRECT output. */
        val DUMP = """
Client Allocators:
Client: 4100
Notification Clients:
   pid    uid  name
  4100  10187  com.google.android.apps.youtube.music
Global session refs:
  session  cnt     pid    uid  name
     1281    1    4100  10187  com.google.android.apps.youtube.music
     2049    1    5200  10250  com.neutroncode.mp
     3001    2     777  10399  app.svan
      901    1    1000   1000  android

Output thread 0x7b3a4e2c40, name AudioOut_1D, tid 1234, type 0 (MIXER):
  I/O handle: 29
  Standby: no
  Output devices: {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP} (BLUETOOTH_A2DP)
  Input device: 0 (NONE)
  AudioStreamOut: 0x7b11 flags 0x4 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)
  Stream volumes in dB: 0:-inf, 3:-6.2
  4 Tracks of which 2 are active
    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db  L dB  R dB  VS dB   Server FrmCnt  FrmRdy F Underruns  Flushed BitPerfect
       F1 P     12     yes     777     3001     57 A 0x000 00000005 00000003  48000  3   1  2   -6   -6   -6  -inf 00000000   1920     960 A         0       0         no
            13     yes    4100    1281     58 A 0x000 00000005 00000003  44100  3   1  2   -6   -6   -6  -inf 00000000   1920     960 A         0       0         no
            14      no    4100    1282     59 I 0x000 00000005 00000003  44100  3   1  2   -6   -6   -6  -inf 00000000   1920     960 A         0       0         no
            15     yes    1000     901     60 A 0x000 00000005 00000003  48000  3   d  4   -6   -6   -6  -inf 00000000   1920     960 A         0       0         no
  2 Effect Chains
    1 effects for session 1281
	In buffer    Out buffer      Active tracks:
	0x7b00   0x7b00   1
	Effect ID 31:
		Session State Registered Enabled Suspended:
		01281   003   y          y       n
		Descriptor:
		- UUID: e0e6539b-1bb7-4b7c-8c6f-1c4f9a1c5e2d
		- TYPE: 7261676f-6d75-7369-6364-28e2fd3aac22
		- apiVersion: 00000100
		- flags: 00000003 (insert)
		- name: Dynamics Processing
		- implementor: The Android Open Source Project
		1 Clients:
			  Pid Priority Ctrl Locked client server
			  777    2147483647  yes     no      1      1
    0 effects for session 1282

Output thread 0x7b3a4e3000, name AudioOut_2D, tid 1300, type 1 (DIRECT):
  I/O handle: 41
  Output devices: {AUDIO_DEVICE_OUT_USB_HEADSET} (USB_HEADSET)
  AudioStreamOut: 0x7b22 flags 0x2 (AUDIO_OUTPUT_FLAG_DIRECT)
  1 Tracks of which 1 are active
    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db  L dB  R dB  VS dB   Server FrmCnt  FrmRdy F Underruns  Flushed BitPerfect
              7     yes    5200    2049     71 A 0x000 00000005 00000003  96000  3   1  2    0    0    0  -inf 00000000   1920     960 A         0       0        yes
  0 Effect Chains

Output thread 0x7b3a4e3400, name AudioOut_3D, tid 1400, type 4 (OFFLOAD):
  Output devices: {AUDIO_DEVICE_OUT_SPEAKER} (SPEAKER)
  0 Tracks
  0 Effect Chains

Input thread 0x7b3a4e4000, name AudioIn_04, tid 1500, type 3 (RECORD):
  1 Tracks of which 1 are active
    Active Client Session S  Flags Format Chn mask  SRate Source  Server FrmCnt  Overruns  Flushed
       yes   777    5000 A 0x000 00000001 00000010  48000      5 00000000   1920         0       0

  Orphan Effect Chains
    1 effects for session 2049
	In buffer    Out buffer      Active tracks:
	0x7b00   0x7b00   0
	Effect ID 44:
		Session State Registered Enabled Suspended:
		02049   001   y          n       n
		Descriptor:
		- TYPE: 7261676f-6d75-7369-6364-28e2fd3aac22
		- name: Dynamics Processing
		1 Clients:
			  Pid Priority Ctrl Locked client server
			  777    2147483647  yes     no      1      1
"""
    }

    private val af = AudioFlingerDump.parse(DUMP)

    @Test fun parsesThreadsWithTypeAndDevices() {
        assertEquals(listOf("MIXER", "DIRECT", "OFFLOAD", "RECORD"), af.threads.map { it.type })
        assertTrue(af.threads[0].devices.contains("BLUETOOTH_A2DP"))
        assertTrue(af.threads[1].devices.contains("USB_HEADSET"))
        assertTrue(af.threads[0].supportsSessionEffects)
        assertFalse(af.threads[1].supportsSessionEffects)
    }

    @Test fun parsesPlaybackTracksIncludingFastPatchAndUsage() {
        val byId = af.tracks.associateBy { it.sessionId }
        assertEquals(setOf(3001, 1281, 1282, 901, 2049), byId.keys)
        assertTrue(byId[3001]!!.patch)
        assertEquals(4100, byId[1281]!!.pid)
        assertTrue(byId[1281]!!.active)
        assertFalse(byId[1282]!!.active)
        assertEquals(1, byId[1281]!!.usage)
        assertEquals(0xD, byId[901]!!.usage)
        assertEquals("DIRECT", byId[2049]!!.thread.type)
        // input thread tracks are not playback
        assertTrue(af.tracks.none { it.sessionId == 5000 })
    }

    @Test fun parsesGlobalSessionRefsWithPackageNames() {
        val ref = af.refs.first { it.sessionId == 2049 }
        assertEquals(5200, ref.pid)
        assertEquals(10250, ref.uid)
        assertEquals("com.neutroncode.mp", ref.packageName)
    }

    @Test fun parsesEffectsOnThreadsAndOrphans() {
        val live = af.effects.first { it.sessionId == 1281 }
        assertTrue(live.isDynamicsProcessing)
        assertEquals("AudioOut_1D", live.threadName)
        assertTrue(live.enabled)
        assertFalse(live.suspended)
        assertEquals(listOf(OWN_PID), live.clientPids)
        val orphan = af.effects.first { it.sessionId == 2049 }
        assertNull(orphan.threadName)
        assertFalse(orphan.enabled)
    }

    @Test fun excerptKeepsRoutingLinesOnly() {
        val ex = AudioFlingerDump.excerpt(DUMP)
        assertTrue(ex.contains("com.neutroncode.mp"))
        assertTrue(ex.contains("Output thread"))
        assertTrue(ex.contains("Dynamics Processing"))
        assertFalse(ex.contains("Stream volumes"))
    }

    @Test fun olderLayoutsWithoutPortIdOrUidStillParse() {
        val old = """
Global session refs:
  session   pid  count
      301  4100      1

Output thread 0x1, name AudioOut_1, tid 5, type 0 (MIXER):
  3 Tracks of which 1 are active
    Name Active Client Session S  Flags   Format Chn mask  SRate ST Usg CT  G db  L dB  R dB  VS dB   Server FrmCnt  FrmRdy F Underruns  Flushed
       1     yes   4100     301 A 0x000 00000001 00000003  44100  3   1  2   -6   -6   -6  -inf 00000000   1920     960 A         0       0
        """.trimIndent()
        val p = AudioFlingerDump.parse(old)
        assertEquals(301, p.tracks.single().sessionId)
        assertEquals(4100, p.tracks.single().pid)
        assertEquals(4100, p.refs.single().pid)
        assertEquals(-1, p.refs.single().uid)
        assertNotNull(p.threads.singleOrNull())
    }

    @Test fun garbageProducesAnUnusableSnapshotNotACrash() {
        val p = AudioFlingerDump.parse("hello\nworld\n\n  nothing here")
        assertFalse(p.usable)
    }

    @Test fun mmapTracksAreRecognised() {
        val d = """
Output thread 0x9, name AudioMmapPlayback, tid 9, type 6 (MMAP_PLAYBACK):
  Output devices: {AUDIO_DEVICE_OUT_USB_HEADSET} (USB)
  Client Session Port Id  Format Chn mask  SRate Flags Usg CT
    5200    2049      71 00000005 00000003  48000 0x000   1  2
        """.trimIndent()
        val t = AudioFlingerDump.parse(d).tracks.single()
        assertEquals(2049, t.sessionId)
        assertEquals("MMAP_PLAYBACK", t.thread.type)
        assertEquals(1, t.usage)
    }
}
