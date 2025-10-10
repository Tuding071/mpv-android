package `is`.xyz.mpv

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
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
    // NEW: For speed shifting (2.0 to ramp up, 1.0 to ramp down)
    SpeedShift, 
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
        // NEW: State for long press speed shift
        ControlSpeedShift, 
    }

    private var state = State.Up
    // relevant movement direction for the current state (0=H, 1=V)
    private var stateDirection = 0

    // timestamp of the last tap (ACTION_UP)
    private var lastTapTime = 0L
    // when the current gesture began
    private var lastDownTime = 0L

    // where user initially placed their finger (ACTION_DOWN)
    private var initialPos = PointF()
    // last non-throttled processed position
    private var lastPos = PointF()
    
    // NEW: For velocity-based seeking
    private var lastSeekUpdateTime = 0L // Time of the last SEEK command sent
    private var lastSeekUpdatePos = PointF() // Position of the last SEEK command sent

    private var width = 0f
    private var height = 0f
    // minimum movement which triggers a Control state
    private var trigger = 0f

    // which property change should be invoked where
    private var gestureHoriz = State.Down
    private var gestureVertLeft = State.Down
    private var gestureVertRight = State.Down

    // map gesture-name to PropertyChange
    private val map = mapOf(
        "none" to State.Down,
        "seek" to State.ControlSeek,
        "volume" to State.ControlVolume,
        "bright" to State.ControlBright
    )

    // map tap-gesture-name to PropertyChange
    private val map2 = mapOf(
        "none" to null,
        "seek_fixed" to PropertyChange.SeekFixed,
        "play_pause" to PropertyChange.PlayPause,
        "custom" to PropertyChange.Custom,
    )

    private var tapGestureLeft: PropertyChange? = null
    private var tapGestureCenter: PropertyChange? = null
    private var tapGestureRight: PropertyChange? = null

    private val DEADZONE = 10 // top/bottom deadzone percentage

    private val TAP_DURATION = 350L
    // NEW: Long press duration (before ramp begins)
    private const val LONG_PRESS_DURATION = 300L 
    
    // NEW: Seek sensitivity reduction (80% reduction = 20% sensitivity)
    private const val SEEK_SENSITIVITY_FACTOR = 0.20f 

    // minimum movement for Control state to begin
    private val THRESHOLD_MOVE = 2

    // minimum movement for a value update to take place
    // Modifying this to 1 for precise slow seeking, and controlling fast seek with timing/velocity
    private const val THRESHOLD_UPDATE_MIN = 1 
    private const val THRESHOLD_UPDATE_MAX = 5 // Max required pixels for fast update
    private const val SEEK_UPDATE_TIMEOUT = 100L // Max time between updates

    // total height that changes volume/brightness from 0 to 100
    private var totalHeight = 0f
    
    // Handler and Runnable for Long Press detection
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        // Long press detected (300ms elapsed)
        if (state == State.Down) {
            state = State.ControlSpeedShift
            // Send command to start ramp up to 2.0x
            sendPropertyChange(PropertyChange.SpeedShift, 2.0f)
        }
    }

    private fun sendPropertyChange(p: PropertyChange, diff: Float) {
        observer.onPropertyChange(p, diff)
    }

    fun setScreenDimensions(w: Int, h: Int) {
        width = w.toFloat()
        height = h.toFloat()
        trigger = min(w, h) / 15f
        totalHeight = h.toFloat() * 0.7f
    }

    fun loadPreferences(sh: SharedPreferences, r: Resources) {
        fun get(key: String, res: Int) = sh.getString(key, r.getString(res))!!

        gestureHoriz = map[get("gesture_move_horiz", R.string.pref_gesture_move_horiz_default)] ?: State.Down
        gestureVertLeft = map[get("gesture_move_vert_left", R.string.pref_gesture_move_vert_left_default)] ?: State.Down
        gestureVertRight = map[get("gesture_move_vert_right", R.string.pref_gesture_move_vert_right_default)] ?: State.Down

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
                handler.removeCallbacks(longPressRunnable) // Cancel any pending long press
                
                // Handle speed shift release
                if (state == State.ControlSpeedShift) {
                    sendPropertyChange(PropertyChange.SpeedShift, 1.0f) // Command to start ramp down to 1.0x
                    state = State.Up
                    return true // Consume the UP event
                }
                
                gestureHandled = processMovement(point) or processTap(point)
                if (state != State.Down)
                    sendPropertyChange(PropertyChange.Finalize, 0f)
                state = State.Up
                
                // Reset seek tracking variables
                lastSeekUpdateTime = 0L
                lastSeekUpdatePos.set(0f, 0f)
            }
            MotionEvent.ACTION_DOWN -> {
                // deadzone on top/bottom
                if (e.y < height * DEADZONE / 100 || e.y > height * (100 - DEADZONE) / 100)
                    return false
                    
                initialPos.set(point)
                processTap(point)
                lastPos.set(point)
                state = State.Down
                
                // Start long press timer
                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION)

                // Initialize seek tracking variables
                lastSeekUpdateTime = SystemClock.uptimeMillis()
                lastSeekUpdatePos.set(point)

                // always return true on ACTION_DOWN to continue receiving events
                gestureHandled = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = PointF(p.x - initialPos.x, p.y - initialPos.y).length()
                
                // If movement exceeds threshold, cancel long press
                if (state == State.Down && dist > trigger) {
                    handler.removeCallbacks(longPressRunnable)
                }

                gestureHandled = processMovement(point)
                lastPos.set(point)
            }
        }
        return gestureHandled
    }

    private fun checkFloat(x: Float, y: Float): Boolean = x.isFinite() && y.isFinite()

    private fun processMovement(p: PointF): Boolean {
        // If in speed shift state, block all other movement gestures
        if (state == State.ControlSpeedShift) {
            return true
        }
        
        val dx = p.x - initialPos.x
        val dy = p.y - initialPos.y
        val dist = PointF(dx, dy).length()

        // if the state is down, we check if we should change to a control state
        if (state == State.Down) {
            if (dist < trigger)
                return false

            // check if movement is mostly horizontal (stateDirection = 0) or vertical (stateDirection = 1)
            stateDirection = if (abs(dx) > abs(dy)) 0 else 1

            if (stateDirection == 0) {
                state = gestureHoriz
            } else {
                state = if (p.x < width / 2) gestureVertLeft else gestureVertRight
            }

            if (state == State.Down)
                return false

            // a control state has begun, notify
            sendPropertyChange(PropertyChange.Init, 0f)
            // Initialize last position for continuous updates
            lastPos.set(p)
            lastSeekUpdatePos.set(p)
            lastSeekUpdateTime = SystemClock.uptimeMillis()
            return true
        } else if (state == State.Up) {
            return false
        }

        // we are in a control state, check for updates

        val d = if (stateDirection == 0) p.x - lastPos.x else p.y - lastPos.y
        
        if (state == State.ControlSeek) {
            // Smart Throttling Logic for Smooth Seeking
            val now = SystemClock.uptimeMillis()
            val timeSinceLastUpdate = now - lastSeekUpdateTime
            
            // d_total is the distance since the last *sent seek command*
            val d_total = if (stateDirection == 0) p.x - lastSeekUpdatePos.x else p.y - lastSeekUpdatePos.y
            val d_total_abs = abs(d_total)

            // Rule 1: Always update on slow movement for precision (THRESHOLD_UPDATE_MIN)
            // Rule 2: Always update if a timeout has been reached (SEEK_UPDATE_TIMEOUT)
            // Rule 3: For fast movement, require a larger distance to update (THRESHOLD_UPDATE_MAX) to skip frames
            
            val requiredDistance = if (timeSinceLastUpdate > SEEK_UPDATE_TIMEOUT) {
                THRESHOLD_UPDATE_MIN // Force update if too slow/stalled
            } else {
                // Dynamic check: Use THRESHOLD_UPDATE_MIN for slow/precise, and THRESHOLD_UPDATE_MAX for fast
                val speedFactor = min(1f, max(0f, (now - lastDownTime).toFloat() / 500f))
                (THRESHOLD_UPDATE_MIN + (THRESHOLD_UPDATE_MAX - THRESHOLD_UPDATE_MIN) * speedFactor).toInt()
            }
            
            if (d_total_abs < requiredDistance && timeSinceLastUpdate < SEEK_UPDATE_TIMEOUT) {
                return true // Still handling gesture, but no update needed yet
            }

            // Apply 80% sensitivity reduction (multiply by 0.20)
            val diff = -d_total / width * 100f * SEEK_SENSITIVITY_FACTOR 

            if (diff != 0f) {
                sendPropertyChange(PropertyChange.Seek, diff)
                // Reset tracking variables after sending the seek command
                lastSeekUpdateTime = now
                lastSeekUpdatePos.set(p)
            }
            
            lastPos.set(p) // Update lastPos for volume/bright logic fallback (though not applicable here)
            return true

        } else {
            // Logic for Volume/Bright (No throttling required)
            val d = if (stateDirection == 0) p.x - lastPos.x else p.y - lastPos.y
            if (abs(d) < THRESHOLD_UPDATE_MIN) // Use min threshold for volume/bright as well
                return true 
                
            val diff = when (state) {
                State.ControlVolume, State.ControlBright -> d / totalHeight // always vertical
                else -> 0f
            }

            if (diff == 0f)
                return true

            val property = when (state) {
                State.ControlVolume -> PropertyChange.Volume
                State.ControlBright -> PropertyChange.Bright
                else -> return true
            }

            sendPropertyChange(property, diff)
            lastPos.set(p)
            return true
        }
    }

    private fun processTap(p: PointF): Boolean {
        if (state == State.Up) {
            lastDownTime = SystemClock.uptimeMillis()
            // 3 is another arbitrary value here that seems good enough
            if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() > trigger * 3)
                lastTapTime = 0 // last tap was too far away, invalidate
            return true
        }
        // discard if any movement gesture took place
        if (state != State.Down)
            return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDownTime >= TAP_DURATION) {
            lastTapTime = 0 // finger was held too long, reset
            return false
        }
        if (now - lastTapTime < TAP_DURATION) {
            // This is the double tap path.
            // [ Left 28% ] [    Center    ] [ Right 28% ]
            if (p.x <= width * 0.28f)
                tapGestureLeft?.let { sendPropertyChange(it, -1f); return true }
            else if (p.x >= width * 0.72f)
                tapGestureRight?.let { sendPropertyChange(it, 1f); return true }
            else
                tapGestureCenter?.let { sendPropertyChange(it, 0f); return true }
            lastTapTime = 0
        } else {
            // Single tap path for Pause/Play and UI Toggle
            sendPropertyChange(PropertyChange.PlayPause, 0f)
            lastTapTime = now
        }
        return false
    }

    companion object {
        private const val TAG = "mpv"
    }
}
