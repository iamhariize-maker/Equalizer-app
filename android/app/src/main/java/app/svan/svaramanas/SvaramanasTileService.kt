package app.svan.svaramanas

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.svan.SvanRepository

/** Quick Settings tile: opens Svaramanas over whatever is playing. Works without overlay permission. */
class SvaramanasTileService : TileService() {
    override fun onStartListening() {
        SvanRepository.init(this)
        qsTile?.apply {
            state = if (Svaramanas.request.value.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (Svaramanas.request.value.enabled) Svaramanas.request.value.feel.title else "Resting"
            updateTile()
        }
    }

    // The Intent overload is only used below API 34, where it is the only one available.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = Intent(this, SvaramanasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
