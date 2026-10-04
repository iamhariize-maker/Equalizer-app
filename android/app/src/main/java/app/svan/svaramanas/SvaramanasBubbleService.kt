package app.svan.svaramanas

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.PixelFormat
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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Svaramanas bubble floating over other apps. Tap: open the dialog. Hold:
 * hear the music without Svaramanas until you let go. Drag: move it (the
 * position is remembered). Needs "Display over other apps"; without it the app
 * still offers the in-app bubble, the Quick Settings tile and the notification.
 */
class SvaramanasBubbleService : Service() {

    private var view: BubbleView? = null
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        view = BubbleView(this).also {
            runCatching { wm.addView(it, params) }.onFailure { e ->
                EqController.log("svaramanas bubble: overlay refused: $e")
                stopSelf()
            }
        }
        EqController.log("svaramanas bubble: shown")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
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

        init { contentDescription = "Svaramanas. Tap to open, hold to compare." }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            val enabled = Svaramanas.request.value.enabled
            SvaraMark.draw(canvas, r, r, r * 0.98f, if (Svaramanas.listening.value) 0.6f else 0f, resting = !enabled || holding)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragging = false; holding = false
                    postDelayed(hold, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !holding && hypot(dx, dy) > slop) { dragging = true; removeCallbacks(hold) }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(this, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    when {
                        holding -> { Svaramanas.setBypass(false); holding = false; invalidate() }
                        dragging -> getSharedPreferences("svaramanas", MODE_PRIVATE).edit()
                            .putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
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
    }

    companion object {
        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) context.startService(Intent(context, SvaramanasBubbleService::class.java))
        }

        fun stop(context: Context) = context.stopService(Intent(context, SvaramanasBubbleService::class.java))
    }
}
