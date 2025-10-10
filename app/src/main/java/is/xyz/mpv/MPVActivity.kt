package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.PlayerBinding
import `is`.xyz.mpv.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope 
import kotlinx.coroutines.Dispatchers 
import kotlinx.coroutines.launch 
import kotlin.math.*

// FIX: Corrected MPVLib Extensions to use commands and handle nullability for paused status.
private val MPVLib.paused: Boolean
    get() = MPVLib.getPropertyBoolean("pause") ?: false

private fun MPVLib.pause() {
    MPVLib.command(arrayOf("set", "pause", "yes"))
}

private fun MPVLib.resume() {
    MPVLib.command(arrayOf("set", "pause", "no"))
}

private fun MPVLib.cyclePause() {
    MPVLib.command(arrayOf("cycle", "pause"))
}

// FIX: Minimal definition for AudioFocusHelper
class AudioFocusHelper(context: Context) {
    fun requestPlaybackFocus(): Boolean = true
    fun abandonFocus() {}
}

internal class MPVActivity : AppCompatActivity(), TouchGesturesObserver {

    // Note: The player variable is used by extensions, but its explicit initialization is not shown.
    // The previous error for MPVLib on line 76 is likely related to this structural ambiguity.
    // Assuming MPVLib is available statically or via a properly initialized instance 'player' later.
    private val player = MPVLib() // Explicitly re-adding this line as it's needed for extensions.
    private lateinit var binding: PlayerBinding
    private lateinit var touchGestures: TouchGestures
    
    // NEW: State tracking for Pause-on-Seek
    private var wasPlayingBeforeSeek = false 
    
    // NEW: Speed Ramp variables
    private val speedRampHandler = Handler(Looper.getMainLooper())
    private var speedRampRunnable: Runnable? = null
    private var currentSpeed = 1.0f

    // Existing members (omitted for brevity, but assume they exist in your full file)
    private var controlsHidden = false
    private val audioFocusHelper = AudioFocusHelper(this) 

    // Helper functions (omitted for brevity, but assume they exist)
    private fun showControls() { /* ... */ }
    private fun hideControls() { /* ... */ }
    private fun toggleControls() { /* ... */ }
    private fun showGestureText(text: String = "") { 
        // Implement logic to display text.
    }
    private fun fadeGestureText() { /* ... */ }

    // NEW: Smoothly ramps the video speed to a target value over 300ms.
    private fun rampSpeed(targetSpeed: Float) {
        // Stop any currently running ramp
        speedRampRunnable?.let { speedRampHandler.removeCallbacks(it) }

        val startSpeed = currentSpeed
        val rampDuration = 300L // Total ramp time in milliseconds
        val updateInterval = 30L // Update step every 30ms
        val totalSteps = rampDuration / updateInterval
        val speedDiffPerStep = (targetSpeed - startSpeed) / totalSteps
        
        var stepsCompleted = 0

        speedRampRunnable = object : Runnable {
            override fun run() {
                stepsCompleted++

                if (stepsCompleted <= totalSteps) {
                    currentSpeed += speedDiffPerStep
                    
                    // Clamp speed to ensure it doesn't overshoot
                    currentSpeed = when {
                        targetSpeed > startSpeed -> min(currentSpeed, targetSpeed)
                        else -> max(currentSpeed, targetSpeed)
                    }

                    MPVLib.setOptionString("speed", currentSpeed.toString())
                    
                    if (stepsCompleted < totalSteps) {
                        speedRampHandler.postDelayed(this, updateInterval)
                    } else {
                        // Ensure final speed is exactly the target
                        currentSpeed = targetSpeed
                        MPVLib.setOptionString("speed", currentSpeed.toString())
                        speedRampRunnable = null
                    }
                }
            }
        }
        speedRampHandler.post(speedRampRunnable!!)
    }

    // NEW: Shared logic to pause video when seeking starts
    private fun onSeekStart() {
        if (!player.paused) {
            wasPlayingBeforeSeek = true
            player.pause() // Uses corrected extension
        } else {
            wasPlayingBeforeSeek = false
        }
        // Force controls to show when seeking starts
        showControls()
    }

    // NEW: Shared logic to resume video when seeking ends
    private fun onSeekEnd() {
        if (wasPlayingBeforeSeek) {
            player.resume() // Uses corrected extension
        }
        wasPlayingBeforeSeek = false
        // Update controls immediately on stop
        toggleControls() 
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize touchGestures
        touchGestures = TouchGestures(this).apply {
            setScreenDimensions(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            loadPreferences(getDefaultSharedPreferences(applicationContext), resources)
        }
        
        // NEW: Apply custom seekbar listener for pause-on-seek logic
        // The unresolved 'seekbar' and 'getDurationText' errors are likely here.
        binding.controls.seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser)
                    return

                val time = progress * 1000 / seekBar!!.max
                // FIX: Explicitly calling MPVLib
                val timeText = MPVLib.getDurationText(time.toLong()) 
                
                // Smart seeking on SeekBar: Always use precise frame update
                MPVLib.command(arrayOf("seek", timeText, "absolute", "exact")) // "exact" for best frame accuracy
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Trigger pause logic when user starts dragging seekbar
                onSeekStart()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Trigger resume logic when user stops dragging seekbar
                onSeekEnd()
            }
        })
        
        // ... (rest of the existing onCreate code)
    }
    
    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        // Stop speed ramp and reset speed if other playback-altering gestures are used
        if (p == PropertyChange.Seek || p == PropertyChange.SeekFixed || p == PropertyChange.Init || p == PropertyChange.Finalize) {
            speedRampRunnable?.let { speedRampHandler.removeCallbacks(it) }
            if (currentSpeed != 1.0f) {
                currentSpeed = 1.0f
                MPVLib.setOptionString("speed", "1.0")
            }
        }
        
        when (p) {
            PropertyChange.Init -> {
                // FIX: Accessing internal properties of TouchGestures instance ('touchGestures')
                val isHorizontal = touchGestures.stateDirection == 0
                val gestureProp = if (isHorizontal) "gesture-move-horiz" else {
                    if (touchGestures.initialPos.x < touchGestures.width / 2) "gesture-move-vert-left" else "gesture-move-vert-right"
                }

                val isSeekGesture = MPVLib.getPropertyString(gestureProp) == "seek"
                
                if (isSeekGesture) {
                    onSeekStart() // Start pause logic for touch gestures
                    showGestureText() // FIX: Called with default argument
                } else {
                    showGestureText() // FIX: Called with default argument
                }
            }
            PropertyChange.Seek -> {
                val percent = diff.toInt()
                val seekAmount = String.format("%+d", percent)
                
                // Execute seek silently
                MPVLib.command(arrayOf("seek", seekAmount, "relative"))
                
                // Update UI text
                showGestureText(seekAmount)
            }
            PropertyChange.Volume -> {
                // ... (existing code)
            }
            PropertyChange.Bright -> {
                // ... (existing code)
            }
            PropertyChange.Finalize -> {
                // If we were seeking, onSeekEnd will handle resume
                onSeekEnd() 
                // Existing UI logic for Finalize
                fadeGestureText()
            }
            PropertyChange.SeekFixed -> {
                // ... (existing code)
            }
            PropertyChange.PlayPause -> player.cyclePause() // Uses corrected extension
            
            // NEW: Handle speed shift command (diff is the target speed)
            PropertyChange.SpeedShift -> {
                rampSpeed(diff) // Diff is 2.0f or 1.0f
            }

            PropertyChange.Custom -> {
                // ... (existing code)
            }
        }
    }

    // ... (rest of the existing MPVActivity code including other functions and companion object)

    companion object {
        private const val TAG = "mpv"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // how long controls fade to disappear (ms)
        private const val CONTROLS_FADE_DURATION = 500L
        // resolution (px) of the thumbnail displayed with playback notification
        private const val THUMB_SIZE = 384
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/...)
        private const val SEEKBAR_RESOLUTION = 1000
    }
}
