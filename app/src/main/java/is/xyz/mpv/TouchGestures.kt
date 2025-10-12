package is.xyz.mpv

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class TouchGestures(
    private val context: Context,
    private val mpv: MPVLib // or your MPV controller class
) : View.OnTouchListener {

    private var lastSeekX = 0f
    private var lastSeekTime = 0L

    // --- Sensitivity config ---
    private val PIXEL_SEEK_TRIGGER = 15f   // pixels per seek step (you can adjust)
    private val SEEK_JUMP_MS = 80L         // ms per seek step
    private val MIN_DELAY_MS = 40L         // optional safety to avoid spam triggers

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastSeekX = event.x
                lastSeekTime = System.currentTimeMillis()
            }

            MotionEvent.ACTION_MOVE -> {
                val currentX = event.x
                val deltaX = currentX - lastSeekX

                val timeNow = System.currentTimeMillis()
                val timeDiff = timeNow - lastSeekTime

                // Only trigger if distance exceeded and not too frequent
                if (abs(deltaX) >= PIXEL_SEEK_TRIGGER && timeDiff >= MIN_DELAY_MS) {
                    val direction = if (deltaX > 0) 1 else -1

                    // Seek the video (true = relative seek)
                    mpv.seek(direction * SEEK_JUMP_MS.toFloat(), true)

                    // Reset reference for next trigger
                    lastSeekX = currentX
                    lastSeekTime = timeNow
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastSeekX = 0f
            }
        }
        return true
    }
}
