package app.svan.svaramanas

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import app.svan.EqController
import app.svan.SvanRepository
import app.svan.ui.SvaraMark
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Svaramanas bubble floating over other apps. Tap: open the dialog. Hold:
 * hear the music without Svaramanas until you let go. Drag: move it (the
 * position is remembered). Drag it onto the close target that rises at the
 * bottom of the screen to switch the bubble off. Needs "Display over other apps";
 * without it the app still offers the in-app bubble, the Quick Settings tile and
 * the notification.
 */
class SvaramanasBubbleService : Service() {

    private var view: BubbleView? = null
    private var target: CloseTarget? = null
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        SvanRepository.init(this)
        wm = getSystemService(WindowManager::class.java)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val prefs = getSharedPreferences("svaramanas", MODE_PRIVATE)
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubbleX", resources.displayMetrics.widthPixels - sizePx - 24)
            y = prefs.getInt("bubbleY", resources.displayMetrics.heightPixels / 3)
        }
        // The close target is added first so the bubble always draws above it. It stays
        // invisible and untouchable until a drag starts.
        target = CloseTarget(this).also { t ->
            t.visibility = View.GONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, (168 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            runCatching { wm.addView(t, lp) }.onFailure { target = null }
        }
        view = BubbleView(this).also {
            it.visibility = if (visibleAppScreens > 0) View.GONE else View.VISIBLE
            runCatching { wm.addView(it, params) }.onFailure { e ->
                EqController.log("svaramanas bubble: overlay refused: $e")
                stopSelf()
            }
        }
        EqController.log("svaramanas bubble: shown")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateAppVisibility()
        return START_STICKY
    }

    private fun updateAppVisibility() {
        // The in-app dock already provides Svaresa. Keep the external overlay out
        // of search fields, charts and dialogs while either Svan screen is open.
        view?.visibility = if (visibleAppScreens > 0) View.GONE else View.VISIBLE
        if (visibleAppScreens > 0) target?.visibility = View.GONE
    }

    override fun onDestroy() {
        view?.let { runCatching { wm.removeView(it) } }
        target?.let { runCatching { wm.removeView(it) } }
        view = null
        target = null
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView(context: Context) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val holdMs = 450L
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false
        private var closing = false
        // Screen position of the window origin relative to params (status bar, cutouts).
        private var originX = 0
        private var originY = 0
        private val hold = Runnable {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Svaramanas.setBypass(true)
            invalidate()
        }

        // Redraw once a second so the bubble follows Svaramanas's state (resting / listening).
        private val tick = object : Runnable {
            override fun run() { invalidate(); postDelayed(this, 1000) }
        }

        init { contentDescription = "Sound guide. Tap to open, hold to compare." }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            val request = Svaramanas.request.value
            val pulse = if (Svaramanas.listening.value) 0.6f else 0f
            val resting = !request.enabled || holding
            contentDescription = "${request.mode.plainName}. Tap to open, hold to compare."
            if (request.mode == SmartMode.SVARESA) SvaraMark.drawSvaresa(canvas, r, r, r * 0.98f, pulse, resting)
            else SvaraMark.draw(canvas, r, r, r * 0.98f, pulse, resting)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (closing) return true
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    val loc = IntArray(2).also { getLocationOnScreen(it) }
                    originX = loc[0] - params.x; originY = loc[1] - params.y
                    dragging = false; holding = false
                    postDelayed(hold, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (closing) return true
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !holding && hypot(dx, dy) > slop) {
                        dragging = true; removeCallbacks(hold); target?.reveal()
                    }
                    if (dragging) {
                        val t = target
                        val armed = t != null && t.contains(e.rawX, e.rawY)
                        t?.setArmed(armed)
                        if (armed && t != null) {
                            // Magnet: the bubble settles into the close ring.
                            params.x = (t.centerX() - width / 2f - originX).toInt()
                            params.y = (t.centerY() - height / 2f - originY).toInt()
                        } else {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                        }
                        runCatching { wm.updateViewLayout(this, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    val closeNow = dragging && target?.armed == true && e.actionMasked == MotionEvent.ACTION_UP
                    if (dragging) target?.conceal()
                    when {
                        closeNow -> close()
                        holding -> { Svaramanas.setBypass(false); holding = false; invalidate() }
                        dragging -> getSharedPreferences("svaramanas", MODE_PRIVATE).edit()
                            .putInt("bubbleX", startX + (e.rawX - downX).toInt())
                            .putInt("bubbleY", startY + (e.rawY - downY).toInt()).apply()
                        e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawX - downX) <= slop -> {
                            performClick()
                        }
                    }
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            SvaramanasActivity.open(context)
            return true
        }

        /** Shrinks into the ring, then switches the bubble off (the setting is saved). */
        private fun close() {
            closing = true
            EqController.log("svaramanas bubble: closed by drag")
            animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180).withEndAction {
                Svaramanas.setBubble(applicationContext, false)
            }.start()
        }
    }

    /**
     * The close target: a dusk gradient rising from the bottom edge with a brass
     * ring and a drawn cross. It warms to molten gold when the bubble is over it.
     */
    @SuppressLint("ViewConstructor")
    private inner class CloseTarget(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val ringR = 28 * d
        private val armRadius = 72 * d
        var armed = false
            private set
        private var glow = 0f
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 12 * d; letterSpacing = 0.08f }
        private val tick = object : Runnable {
            override fun run() {
                val goal = if (armed) 1f else 0f
                if (abs(glow - goal) > 0.01f) { glow += (goal - glow) * 0.35f; invalidate(); postOnAnimation(this) }
                else { glow = goal; invalidate() }
            }
        }

        init {
            contentDescription = "Close target. Release the bubble here to switch it off."
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun ringCenterY(): Float = height - 64 * d
        fun centerX(): Float = IntArray(2).also { getLocationOnScreen(it) }[0] + width / 2f
        fun centerY(): Float = IntArray(2).also { getLocationOnScreen(it) }[1] + ringCenterY()
        fun contains(rawX: Float, rawY: Float): Boolean = visibility == VISIBLE && hypot(rawX - centerX(), rawY - centerY()) < armRadius

        fun reveal() {
            armed = false; glow = 0f
            alpha = 0f; visibility = VISIBLE
            animate().alpha(1f).setDuration(160).start()
        }

        fun conceal() {
            animate().alpha(0f).setDuration(140).withEndAction { visibility = GONE; armed = false }.start()
        }

        fun setArmed(on: Boolean) {
            if (on == armed) return
            armed = on
            removeCallbacks(tick); postOnAnimation(tick)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // Dusk: transparent at the top, charred ground at the edge.
            shade.shader = LinearGradient(0f, 0f, 0f, h, 0x00000000, 0xCC0C0A08.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, shade)
            val cx = w / 2f; val cy = ringCenterY()
            val r = ringR * (1f + 0.28f * glow)
            // Inner warmth grows with the glow.
            fill.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(
                blend(0xFF1C1813.toInt(), 0xFFF3D58F.toInt(), 0.55f * glow), blend(0xFF15120E.toInt(), 0xFF9A6B2A.toInt(), glow)),
                null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, fill)
            ring.color = blend(0xFFA39A8B.toInt(), 0xFFF3D58F.toInt(), glow)
            canvas.drawCircle(cx, cy, r, ring)
            // A faint second ring, like a ripple, when armed.
            if (glow > 0.05f) {
                ring.alpha = (90 * glow).toInt()
                canvas.drawCircle(cx, cy, r + 9 * d * glow, ring)
                ring.alpha = 255
            }
            val k = min(r * 0.32f, 10 * d)
            cross.color = blend(0xFFEEE6D8.toInt(), 0xFF1A1206.toInt(), glow)
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
            label.color = blend(0xFFA79D8D.toInt(), 0xFFF3D58F.toInt(), glow)
            canvas.drawText(if (armed) "Release to close" else "Drag here to close", cx, cy - r - 14 * d, label)
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - u) + ((b shr shift) and 0xFF) * u).toInt() and 0xFF) shl shift
            return ch(24) or ch(16) or ch(8) or ch(0)
        }
    }

    companion object {
        private var visibleAppScreens = 0
        private var activeService: WeakReference<SvaramanasBubbleService>? = null

        fun appScreenStarted() {
            visibleAppScreens++
            activeService?.get()?.updateAppVisibility()
        }

        fun appScreenStopped() {
            visibleAppScreens = (visibleAppScreens - 1).coerceAtLeast(0)
            activeService?.get()?.updateAppVisibility()
        }

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) context.startService(Intent(context, SvaramanasBubbleService::class.java))
        }

        fun stop(context: Context) = context.stopService(Intent(context, SvaramanasBubbleService::class.java))
    }
}
