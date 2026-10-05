package app.svan

import app.svan.svaramanas.ContextLayer
import app.svan.svaramanas.Iso226
import app.svan.svaramanas.NightMode
import app.svan.svaramanas.RouteKind
import app.svan.svaramanas.SvaresaBrain
import app.svan.svaramanas.SvaresaContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvaresaBrainTest {
    private fun ctx(volume: Double, route: RouteKind = RouteKind.BLUETOOTH, hour: Int = 14, night: NightMode = NightMode.AUTO,
                    volumeAware: Boolean = true, routeAware: Boolean = true) =
        SvaresaContext(volume, route, hour, 0, night, volumeAware, routeAware)

    @Test fun equalLoudnessContoursMatchTheStandard() {
        // By definition a contour passes through its own phon value at 1 kHz.
        assertEquals(40.0, Iso226.spl(1000.0, 40.0), 0.1)
        assertEquals(70.0, Iso226.spl(1000.0, 70.0), 0.1)
        // Published ISO 226:2003 40-phon contour: 99.85 dB at 20 Hzand 64.37 dB at 100 Hz; the ear is most sensitive near 4 kHz.
        assertEquals(99.85, Iso226.spl(20.0, 40.0), 0.15)
        assertEquals(64.37, Iso226.spl(100.0, 40.0), 0.15)
        assertTrue(Iso226.spl(4000.0, 40.0) in 33.0..38.0)
        // The ear is least sensitive at the extremes and most at ~3-4 kHz.
        assertTrue(Iso226.spl(3150.0, 40.0) < Iso226.spl(1000.0, 40.0))
        // Contours flatten with level: the low end is relatively less demanding when loud.
        assertTrue(Iso226.relative(63.0, 40.0) > Iso226.relative(63.0, 80.0))
    }

    @Test fun quietListeningLiftsBassMoreAsVolumeDrops() {
        val loud = SvaresaBrain.layer(ctx(0.9))
        val mid = SvaresaBrain.layer(ctx(0.5))
        val quiet = SvaresaBrain.layer(ctx(0.1))
        assertEquals(0.0, loud.bassLiftDb, 1e-9)       // above the reference level: never cut or boost
        assertTrue(mid.bassLiftDb > 0.5)
        assertTrue(quiet.bassLiftDb > mid.bassLiftDb)
        assertTrue(quiet.trebleLiftDb > 0.0)
        assertTrue(quiet.reasons.any { it.startsWith("Quiet listening") })
    }

    @Test fun liftsAreBoundedAndTheSpeakerIsProtected() {
        val quiet = SvaresaBrain.layer(ctx(0.0))
        assertTrue(quiet.bassLiftDb <= SvaresaBrain.MAX_BASS_DB + 1e-9)
        assertTrue(quiet.trebleLiftDb <= SvaresaBrain.MAX_TREBLE_DB + 1e-9)
        val speaker = SvaresaBrain.layer(ctx(0.0, RouteKind.SPEAKER))
        assertTrue(speaker.bassLiftDb <= SvaresaBrain.SPEAKER_MAX_BASS_DB + 1e-9)
        assertTrue(speaker.reasons.any { it.startsWith("Phone speaker") })
        // With output protection switched off the speaker is treated like any output.
        assertTrue(SvaresaBrain.layer(ctx(0.0, RouteKind.SPEAKER, routeAware = false)).bassLiftDb > SvaresaBrain.SPEAKER_MAX_BASS_DB)
    }

    @Test fun switchingVolumeAwarenessOffRemovesTheLift() {
        val l = SvaresaBrain.layer(ctx(0.05, volumeAware = false))
        assertEquals(0.0, l.bassLiftDb, 1e-9)
        assertEquals(0.0, l.trebleLiftDb, 1e-9)
        assertEquals(0.0, l.preampDb, 1e-9)
    }

    @Test fun nightFollowsTheClockWithSoftEdgesAndCanBeForced() {
        assertEquals(0.0, SvaresaBrain.nightAmount(NightMode.AUTO, 12, 0), 1e-9)
        assertEquals(1.0, SvaresaBrain.nightAmount(NightMode.AUTO, 23, 0), 1e-9)
        assertEquals(1.0, SvaresaBrain.nightAmount(NightMode.AUTO, 3, 0), 1e-9)
        assertEquals(0.5, SvaresaBrain.nightAmount(NightMode.AUTO, 22, 0), 1e-9)   // ramping in
        assertEquals(0.5, SvaresaBrain.nightAmount(NightMode.AUTO, 6, 0), 1e-9)    // ramping out
        assertEquals(1.0, SvaresaBrain.nightAmount(NightMode.ON, 12, 0), 1e-9)
        assertEquals(0.0, SvaresaBrain.nightAmount(NightMode.OFF, 23, 0), 1e-9)
    }

    @Test fun nightTrimsSubBassSoftensPresenceAndEvensLevels() {
        val day = SvaresaBrain.layer(ctx(0.9, hour = 14))
        val night = SvaresaBrain.layer(ctx(0.9, hour = 23))
        assertEquals(0.0, day.levelling, 1e-9)
        assertEquals(SvaresaBrain.NIGHT_LEVELLING, night.levelling, 1e-9)
        val bassShelf = night.bands[0].gainDb
        assertEquals(SvaresaBrain.NIGHT_SUB_DB, bassShelf, 1e-9)   // loud + night: only the trim remains
        assertEquals(SvaresaBrain.NIGHT_PRESENCE_DB, night.bands[3].gainDb, 1e-9)
        assertTrue(night.reasons.any { it.startsWith("Night comfort") })
    }

    @Test fun theBandSkeletonNeverChangesSoSlewingByIndexIsSafe() {
        val layers = listOf(ctx(0.0), ctx(1.0), ctx(0.5, RouteKind.SPEAKER, 23), ctx(0.3, volumeAware = false))
            .map(SvaresaBrain::layer)
        layers.forEach { assertEquals(ContextLayer.BAND_COUNT, it.bands.size) }
        val first = layers.first().bands.map { it.type to it.freqHz }
        layers.forEach { l -> assertEquals(first, l.bands.map { it.type to it.freqHz }) }
    }

    @Test fun routesAreClassifiedFromBothSources() {
        assertEquals(RouteKind.BLUETOOTH, RouteKind.fromServerText("{AUDIO_DEVICE_OUT_BLUETOOTH_A2DP} (BLUETOOTH_A2DP)"))
        assertEquals(RouteKind.USB, RouteKind.fromServerText("{AUDIO_DEVICE_OUT_USB_HEADSET} (USB_HEADSET)"))
        assertEquals(RouteKind.SPEAKER, RouteKind.fromServerText("{AUDIO_DEVICE_OUT_SPEAKER} (SPEAKER)"))
        assertEquals(RouteKind.WIRED, RouteKind.fromServerText("{AUDIO_DEVICE_OUT_WIRED_HEADPHONE}"))
        assertEquals(RouteKind.SPEAKER, RouteKind.fromDeviceType(2))
        assertEquals(RouteKind.BLUETOOTH, RouteKind.fromDeviceType(8))
        assertEquals(RouteKind.USB, RouteKind.fromDeviceType(22))
    }
}
