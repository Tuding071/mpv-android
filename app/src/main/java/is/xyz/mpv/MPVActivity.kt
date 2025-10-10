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

internal class MPVActivity : AppCompatActivity(), TouchGesturesObserver {

    private val player = MPVLib()
    private lateinit var binding: PlayerBinding
    private lateinit var touchGestures: TouchGestures
    
    // NEW: State tracking for Pause-on-Seek
    private var wasPlayingBeforeSeek = false 
    
    // NEW: Speed Ramp variables
    private val speedRampHandler = Handler(Looper.getMainLooper())
    private var speedRampRunnable: Runnable? = null
    private var currentSpeed = 1.0f

    // Existing members...
    private var isPlaying = false
    private var isPip = false
    private var controlsHidden = false
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private var controlsTimeoutRunnable: Runnable? = null
    private var controlsFadeRunnable: Runnable? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val audioFocusHelper = AudioFocusHelper(this)
    private lateinit var mediaSession: MediaSessionCompat

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
            player.pause() // Use the internal pause function which uses MPVLib
        } else {
            wasPlayingBeforeSeek = false
        }
        // Force controls to show when seeking starts
        showControls()
    }

    // NEW: Shared logic to resume video when seeking ends
    private fun onSeekEnd() {
        if (wasPlayingBeforeSeek) {
            player.resume() // Use the internal resume function
        }
        wasPlayingBeforeSeek = false
        // Update controls immediately on stop
        toggleControls() 
    }
    
    // Existing functions (toggleControls, showControls, hideControls, etc.)
    // ... (omitted for brevity, assume they exist)
    
    private fun toggleControls() {
        if (controlsHidden)
            showControls()
        else
            hideControls()
    }

    private fun showControls() {
        controlsHidden = false
        // ... existing showControls logic
    }
    
    private fun hideControls() {
        controlsHidden = true
        // ... existing hideControls logic
    }
    
    private fun showGestureText(text: String) {
        // ... existing showGestureText logic
    }
    
    private fun fadeGestureText() {
        // ... existing fadeGestureText logic
    }
    
    // Existing lifecycle functions
    // ... (omitted for brevity, assume they exist)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ... (existing binding setup)
        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize touchGestures
        touchGestures = TouchGestures(this).apply {
            setScreenDimensions(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            loadPreferences(getDefaultSharedPreferences(applicationContext), resources)
        }
        
        // ... (existing window/system UI setup)

        // NEW: Apply custom seekbar listener for pause-on-seek logic
        binding.controls.seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser)
                    return

                val time = progress * 1000 / binding.controls.seekbar.max
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

    // Existing dispatchTouchEvent function
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // ... (existing code for controls timeout)
        
        val mightWantToToggleControls = ev.action == MotionEvent.ACTION_DOWN
        val handled = touchGestures.onTouchEvent(ev)

        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls) {
            // This is the fallback for single tap, which returns false from touchGestures
            if (!handled)
                toggleControls()
        }

        return handled || super.dispatchTouchEvent(ev)
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
                // If the gesture is a Seek (horiz or vert depending on preferences)
                // Note: The integer value of the gesture-move-* options is not available,
                // so we rely on the property string value "seek"
                val gestureProp = if (touchGestures.stateDirection == 0) "gesture-move-horiz" else {
                    if (touchGestures.initialPos.x < width / 2) "gesture-move-vert-left" else "gesture-move-vert-right"
                }

                val isSeekGesture = MPVLib.getPropertyString(gestureProp) == "seek"
                
                if (isSeekGesture) {
                    onSeekStart() // Start pause logic for touch gestures
                    showGestureText() // Existing UI logic for Init
                } else {
                    showGestureText() // Existing UI logic for Init (volume/bright)
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
            PropertyChange.PlayPause -> player.cyclePause() // Single tap play/pause
            
            // NEW: Handle speed shift command (diff is the target speed)
            PropertyChange.SpeedShift -> {
                rampSpeed(diff) // Diff is 2.0f or 1.0f
            }

            PropertyChange.Custom -> {
                // ... (existing code)
            }
        }
    }

    // Existing functions (onPause, onResume, onStop, etc.)
    // ... (omitted for brevity, assume they exist)
    
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
    
    // Existing helper classes (AudioFocusHelper, etc.)
    // ... (omitted for brevity, assume they exist)
}
