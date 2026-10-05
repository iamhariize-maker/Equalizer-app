package app.svan.svaramanas

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.svan.DetectionMonitor
import java.util.Calendar

/** Reads what the phone itself knows (volume, output, clock) for [SvaresaBrain]. No audio, no permissions. */
object SvaresaSensors {
    fun volume(context: Context): Double {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
    }

    /** The output the music is actually on: the audio server's view of a playing session, else the connected outputs. */
    fun route(context: Context): RouteKind {
        DetectionMonitor.status.value.sessions
            .filter { it.session.state == "started" || it.serverActive == true }
            .firstNotNullOfOrNull { RouteKind.fromServerText(it.devices) }
            ?.let { return it }
        val kinds = runCatching {
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { RouteKind.fromDeviceType(it.type) }
        }.getOrDefault(emptyList())
        return listOf(RouteKind.BLUETOOTH, RouteKind.USB, RouteKind.WIRED).firstOrNull { it in kinds }
            ?: if (RouteKind.SPEAKER in kinds) RouteKind.SPEAKER else RouteKind.OTHER
    }

    /** Name of the connected headphones/earbuds (for headphone recognition), or null for the speaker. */
    fun headphoneName(context: Context): String? = runCatching {
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { RouteKind.fromDeviceType(it.type) in setOf(RouteKind.BLUETOOTH, RouteKind.USB, RouteKind.WIRED) }
            .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) 0 else 1 }
            .firstNotNullOfOrNull { it.productName?.toString()?.takeIf { n -> n.isNotBlank() } }
    }.getOrNull()

    fun read(context: Context, request: SmartRequest, now: Calendar = Calendar.getInstance()) = SvaresaContext(
        volume = volume(context),
        route = route(context),
        hourOfDay = now.get(Calendar.HOUR_OF_DAY),
        minuteOfHour = now.get(Calendar.MINUTE),
        night = request.night,
        volumeAware = request.volumeAware,
        routeAware = request.routeAware,
    )
}
