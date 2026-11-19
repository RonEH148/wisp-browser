package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Lumen: the remote's pointer above the top bar. The page has its own pointer (inside the web view);
 * when it reaches the top edge it continues here, so the bar's buttons, tabs and their ✕ can be
 * pointed at and clicked like with a mouse. Moving below the bar hands the pointer back to the page.
 */
class ToolbarCursorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Host {
        /** Lower edge of the area this pointer may move in (the bar's bottom), in this view's coordinates. */
        fun toolbarBottom(): Int
        /** The pointer left the bar downwards at [x], still moving: give it back to the page. */
        fun onLeaveToPage(x: Float, dirX: Int, dirY: Int, speedX: Float, speedY: Float)
        /** The pointer was put away (Back, or a text field got the keyboard). */
        fun onCursorClosed()
        /** Where to deliver the simulated clicks. */
        fun touchTarget(): ViewGroup
        /** OK held at ([x], [y]); true = handled (e.g. pasted into a text field), no click then. */
        fun onLongPress(x: Float, y: Float): Boolean = false
    }

    var host: Host? = null
    var active = false
        private set
    private var x = 0f
    private var y = 0f
    private var pressedDownTime = 0L
    private var longPressed = false
    private val longPressRunnable = Runnable {
        if (active && host?.onLongPress(x, y) == true) {
            longPressed = true
            touch(MotionEvent.ACTION_CANCEL)
        }
    }
    private val radius = context.resources.displayMetrics.widthPixels / 100f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dirX = 0
    private var dirY = 0
    private var speedX = 0f
    private var speedY = 0f
    private var lastStep = 0L
    /** Same speed limit as the page pointer: a share of the display width per step. */
    private val maxSpeedBaseline: Float = run {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getSize(size)
        size.x / 25f
    }

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    private var lastBarBottom = 0

    /**
     * Takes the pointer over at ([atX], [atY]), keeping the page pointer's direction and speed
     * so a held key carries it on into the bar without a stop.
     */
    fun show(atX: Float, atY: Float, dirX: Int = 0, dirY: Int = 0, speedX: Float = 0f, speedY: Float = 0f) {
        x = atX.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        y = atY.coerceAtLeast(0f)
        lastBarBottom = host?.toolbarBottom() ?: 0
        active = true
        this.dirX = dirX
        this.dirY = dirY
        this.speedX = speedX
        this.speedY = speedY
        removeCallbacks(mover)
        if (dirX != 0 || dirY != 0) {
            lastStep = System.currentTimeMillis()
            post(mover)
        }
        invalidate()
    }

    /** The bar is sliding in or out: the pointer rides along with it, like it is attached. */
    fun onBarMoved() {
        if (!active) return
        val bottom = host?.toolbarBottom() ?: return
        val d = bottom - lastBarBottom
        lastBarBottom = bottom
        if (d == 0) return
        y = (y + d).coerceIn(0f, (bottom - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    fun close(notify: Boolean = true) {
        if (!active) return
        active = false
        dirX = 0; dirY = 0; speedX = 0f; speedY = 0f
        removeCallbacks(mover)
        invalidate()
        if (notify) host?.onCursorClosed()
    }

    private val mover = object : Runnable {
        override fun run() {
            if (!active || (dirX == 0 && dirY == 0)) return
            //exactly the page pointer's movement (CursorDrawerDelegate), including the user's
            //speed and acceleration settings, so the pointer feels the same everywhere
            val now = System.currentTimeMillis()
            val dt = (now - lastStep).coerceIn(1L, 100L)
            lastStep = now
            val k = (dt / (1000f / 60f)).coerceIn(0.25f, 3f) //per-frame speeds, by real time
            val cfg = com.phlox.tvwebbrowser.AppContext.provideConfig()
            val cap = maxSpeedBaseline * (cfg.cursorMaxSpeedPercent / 100f)
            val accel = 0.05f * (cfg.cursorAccelerationPercent / 100f) * dt
            speedX = (speedX + dirX * accel).coerceIn(-cap, cap)
            speedY = (speedY + dirY * accel).coerceIn(-cap, cap)
            x = (x + speedX * k).coerceIn(0f, (width - 1).toFloat())
            y = (y + speedY * k).coerceAtLeast(0f)
            val bottom = host?.toolbarBottom() ?: height
            //only a downward move leaves the bar (while it slides in, its bottom starts at 0)
            if (dirY > 0 && y >= bottom) {
                val leaveX = x
                val dx = dirX; val dy = dirY; val sx = speedX; val sy = speedY
                close(notify = false)
                host?.onLeaveToPage(leaveX, dx, dy, sx, sy)
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    /** Remote keys while the pointer is shown. Returns true when the key was used. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!active) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val dx = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                val dy = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
                if (down) {
                    if (event.repeatCount == 0) {
                        val idle = dirX == 0 && dirY == 0
                        if (dx != 0) dirX = dx
                        if (dy != 0) dirY = dy
                        if (idle) {
                            lastStep = System.currentTimeMillis()
                            removeCallbacks(mover)
                            post(mover)
                        }
                    }
                } else {
                    //like the page pointer: releasing a key stops the movement at once
                    if (dx != 0) dirX = 0
                    if (dy != 0) dirY = 0
                    speedX = 0f; speedY = 0f
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && event.repeatCount == 0) {
                    pressedDownTime = SystemClock.uptimeMillis()
                    longPressed = false
                    touch(MotionEvent.ACTION_DOWN)
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, 700)
                } else if (!down) {
                    removeCallbacks(longPressRunnable)
                    if (!longPressed) touch(MotionEvent.ACTION_UP)
                    longPressed = false
                }
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (!down) close()
                return true
            }
        }
        return false
    }

    private fun touch(action: Int) {
        val target = host?.touchTarget() ?: return
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(if (action == MotionEvent.ACTION_DOWN) now else pressedDownTime, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(e)
        e.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (radius / 4f).coerceAtLeast(2f)
        paint.color = Color.rgb(0x20, 0xC9, 0x97)
        canvas.drawCircle(x, y, radius, paint)
    }
}
