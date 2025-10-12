package `is`.xyz.mpv

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.*

enum class PropertyChange {
    Init,
    Seek,
    Volume,
    Bright,
    Finalize,

    /* Tap gestures */
    SeekFixed,
    PlayPause,
    Custom,

    /* Hold gestures */
    HoldSpeedStart,
    HoldSpeedEnd,
}

internal interface TouchGesturesObserver {
    fun onPropertyChange(p: PropertyChange, diff: Float)
}

internal class TouchGestures(private val observer: TouchGesturesObserver) {

    private enum class State {
        Up,
        Down,
        ControlSeek,
        ControlVolume,
        ControlBright,
        HoldSpeed,
    }

    private var state = State.Up
    private var stateDirection = 0

    private var lastTapTime = 0L
    private var lastDownTime = 0L

    private var initialPos = PointF()
    private var lastPos = PointF()

    // sensitivity + movement accumulator
    private var totalPixelMovement = 0f
    private val PIXEL_SEEK_TRIGGER = 180f   // lower = more sensitive, higher = slower
    private val MS_PER_SEEK = 80L           // ms jump per seek step

    private var width = 0f
    private var height = 0f
    private var trigger = 0f

    private var holdStartTime = 0L
    private val HOLD_DURATION = 200L

    private var gestureHoriz = State.Down
    private var gestureVertLeft = State.Down
    private var gestureVertRight = State.Down
    private var tapGestureLeft: PropertyChange? = null
    private var tapGestureCenter: PropertyChange? = null
    private var tapGestureRight: PropertyChange? = null

    private inline fun checkFloat(vararg n: Float): Boolean {
        return !n.any { it.isInfinite() || it.isNaN() }
    }

    private inline fun assertFloat(vararg n: Float) {
        if (!checkFloat(*n))
            throw IllegalArgumentException()
    }

    private fun isInLeftoverArea(point: PointF): Boolean {
        val inCenterZone = point.x >= width * 0.25f && point.x <= width * 0.75f && point.y <= height * 0.70f
        return !inCenterZone
    }

    fun setMetrics(width: Float, height: Float) {
        assertFloat(width, height)
        this.width = width
        this.height = height
        trigger = min(width, height) / TRIGGER_RATE
    }

    companion object {
        private const val TAG = "mpv"
        private const val TRIGGER_RATE = 30
        private const val TAP_DURATION = 300L
        private const val CONTROL_VOLUME_MAX = 1.5f
        private const val CONTROL_BRIGHT_MAX = 1.5f
        private const val DEADZONE = 5
    }

    private fun processTap(p: PointF): Boolean {
        if (state == State.HoldSpeed) return false

        if (state == State.Up) {
            lastDownTime = SystemClock.uptimeMillis()
            if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() > trigger * 3)
                lastTapTime = 0
            return true
        }

        if (state != State.Down)
            return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDownTime >= TAP_DURATION) {
            lastTapTime = 0
            return false
        }

        if (p.x >= width * 0.25f && p.x <= width * 0.75f && p.y <= height * 0.70f) {
            sendPropertyChange(PropertyChange.PlayPause, 0f)
            lastTapTime = 0
            return true
        }

        if (now - lastTapTime < TAP_DURATION) {
            if (p.x <= width * 0.28f)
                tapGestureLeft?.let { sendPropertyChange(it, -1f); return true }
            else if (p.x >= width * 0.72f)
                tapGestureRight?.let { sendPropertyChange(it, 1f); return true }
            else
                tapGestureCenter?.let { sendPropertyChange(it, 0f); return true }
            lastTapTime = 0
        } else {
            lastTapTime = now
        }
        return false
    }

    // --- improved smooth seeking logic ---
    private fun processMovement(p: PointF): Boolean {
        if (state == State.HoldSpeed) return false

        val diffX = p.x - lastPos.x
        val diffY = p.y - lastPos.y
        lastPos.set(p)

        when (state) {
            State.Up -> return false

            State.Down -> {
                val dx = p.x - initialPos.x
                val dy = p.y - initialPos.y

                if (abs(dx) > trigger) {
                    state = gestureHoriz
                    stateDirection = 0
                    totalPixelMovement = 0f   // reset on new seek start
                    sendPropertyChange(PropertyChange.Init, 0f)
                } else if (abs(dy) > trigger) {
                    state = if (initialPos.x > width / 2) gestureVertRight else gestureVertLeft
                    stateDirection = 1
                    sendPropertyChange(PropertyChange.Init, 0f)
                }
            }

            State.ControlSeek -> {
                totalPixelMovement += diffX
                totalPixelMovement = totalPixelMovement.coerceIn(-2400f, 2400f)

                // smooth dynamic scaling — faster swipes a bit stronger
                val scaledTrigger = PIXEL_SEEK_TRIGGER * (1f - (abs(diffX) / width).coerceIn(0f, 0.8f) * 0.6f)

                if (abs(totalPixelMovement) >= scaledTrigger) {
                    val steps = (totalPixelMovement / scaledTrigger)
                        .coerceIn(-5f, 5f)
                    val seekMs = steps * MS_PER_SEEK
                    sendPropertyChange(PropertyChange.Seek, seekMs)
                    totalPixelMovement -= steps * scaledTrigger
                }
            }

            State.ControlVolume ->
                sendPropertyChange(PropertyChange.Volume, CONTROL_VOLUME_MAX * (-diffY / height))

            State.ControlBright ->
                sendPropertyChange(PropertyChange.Bright, CONTROL_BRIGHT_MAX * (-diffY / height))

            else -> {}
        }

        return state != State.Up && state != State.Down
    }
    // ---------------------------------------

    private fun sendPropertyChange(p: PropertyChange, diff: Float) {
        observer.onPropertyChange(p, diff)
    }

    fun syncSettings(prefs: SharedPreferences, resources: Resources) {
        val get: (String, Int) -> String = { key, defaultRes ->
            val v = prefs.getString(key, "")
            if (v.isNullOrEmpty()) resources.getString(defaultRes) else v
        }

        val map = mapOf(
            "bright" to State.ControlBright,
            "seek" to State.ControlSeek,
            "volume" to State.ControlVolume
        )

        val map2 = mapOf(
            "seek" to PropertyChange.SeekFixed,
            "playpause" to PropertyChange.PlayPause,
            "custom" to PropertyChange.Custom
        )

        gestureHoriz = map[get("gesture_horiz", R.string.pref_gesture_horiz_default)] ?: State.Down
        gestureVertLeft = map[get("gesture_vert_left", R.string.pref_gesture_vert_left_default)] ?: State.Down
        gestureVertRight = map[get("gesture_vert_right", R.string.pref_gesture_vert_right_default)] ?: State.Down
        tapGestureLeft = map2[get("gesture_tap_left", R.string.pref_gesture_tap_left_default)]
        tapGestureCenter = map2[get("gesture_tap_center", R.string.pref_gesture_tap_center_default)]
        tapGestureRight = map2[get("gesture_tap_right", R.string.pref_gesture_tap_right_default)]
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        if (width < 1 || height < 1) {
            Log.w(TAG, "TouchGestures: width or height not set!")
            return false
        }
        if (!checkFloat(e.x, e.y)) {
            Log.w(TAG, "TouchGestures: ignoring invalid point ${e.x} ${e.y}")
            return false
        }

        var gestureHandled = false
        val point = PointF(e.x, e.y)

        when (e.action) {
            MotionEvent.ACTION_UP -> {
                if (state == State.HoldSpeed) {
                    sendPropertyChange(PropertyChange.HoldSpeedEnd, 0f)
                    state = State.Up
                    holdStartTime = 0
                    totalPixelMovement = 0f
                    return true
                }

                gestureHandled = processMovement(point) or processTap(point)
                if (state != State.Down)
                    sendPropertyChange(PropertyChange.Finalize, 0f)
                state = State.Up
                holdStartTime = 0
                totalPixelMovement = 0f
            }

            MotionEvent.ACTION_DOWN -> {
                if (e.y < height * DEADZONE / 100 || e.y > height * (100 - DEADZONE) / 100)
                    return false
                initialPos.set(point)
                processTap(point)
                lastPos.set(point)
                state = State.Down

                if (isInLeftoverArea(point)) {
                    holdStartTime = SystemClock.uptimeMillis()
                }

                gestureHandled = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (state == State.HoldSpeed) return true

                gestureHandled = processMovement(point)

                if (state == State.Down && holdStartTime > 0) {
                    val holdTime = SystemClock.uptimeMillis() - holdStartTime
                    if (holdTime >= HOLD_DURATION && isInLeftoverArea(point)) {
                        state = State.HoldSpeed
                        sendPropertyChange(PropertyChange.HoldSpeedStart, 0f)
                        return true
                    }
                }
            }
        }

        return gestureHandled
    }
}
