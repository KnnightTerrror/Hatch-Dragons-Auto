package com.hatchbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bot's hands and eyes: Android lets an accessibility service take screenshots and send taps.
 * It also owns the floating control bar and the bot thread.
 */
class BotAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var panel: OverlayPanel? = null
    private var runner: BotRunner? = null
    private var lastShotAt = 0L

    val isRunning get() = runner != null

    override fun onServiceConnected() {
        instance = this
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        runner?.stopRunning()
        runner = null
        panel?.remove()
        panel = null
        instance = null
    }

    fun showPanel() {
        if (panel == null) panel = OverlayPanel(this).also { it.show() }
    }

    fun hidePanel() {
        stopBot()
        panel?.remove()
        panel = null
    }

    fun toggleBot() = if (isRunning) stopBot() else startBot()

    private fun startBot() {
        if (isRunning) return
        runner = BotRunner(this).also { it.start() }
        panel?.setRunning(true)
        panel?.setStatus("Starting…")
    }

    private fun stopBot() {
        runner?.stopRunning()
        runner = null
        panel?.setRunning(false)
        panel?.setStatus("Stopped")
    }

    /** Called from the bot thread when it ends on its own (error, or nothing to do). */
    fun onBotStopped(finished: BotRunner) = main.post {
        if (runner === finished) {
            runner = null
            panel?.setRunning(false)
        }
    }

    fun setStatus(text: String) {
        main.post { panel?.setStatus(text) }
    }

    /** Package name of the app the player is looking at. */
    fun frontPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun screenBounds(): Rect = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    /** Full-size screenshot, or null if Android refused. Blocks, so call it off the main thread. */
    @Synchronized
    fun capture(): Bitmap? {
        // Android allows roughly three screenshots per second.
        val wait = lastShotAt + MIN_SHOT_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) Thread.sleep(wait)

        val latch = CountDownLatch(1)
        var shot: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                shot = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                hardware?.recycle()
                buffer.close()
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(3, TimeUnit.SECONDS)
        lastShotAt = SystemClock.uptimeMillis()
        return shot
    }

    /** Taps a screen point. Skips points under the floating bar, which would catch the tap. */
    fun tap(x: Float, y: Float): Boolean {
        if (panel?.covers(x, y) == true) return false
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, 60))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(gesture, callback, null)) return false
        latch.await(stroke.duration + 2000, TimeUnit.MILLISECONDS)
        return completed
    }

    /** Saves what the bot sees to Pictures/HatchBot, without the floating bar in the way. */
    fun snap() {
        Thread {
            main.post { panel?.setVisible(false) }
            Thread.sleep(400)
            val shot = capture()
            main.post { panel?.setVisible(true) }
            val saved = shot?.let { bitmap -> saveToGallery(bitmap).also { bitmap.recycle() } }
            setStatus(if (saved != null) "Saved $saved" else "Screenshot failed")
        }.start()
    }

    private fun saveToGallery(bitmap: Bitmap): String? {
        val name = "hatchbot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HatchBot")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = contentResolver.openOutputStream(uri) ?: return null
        stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    companion object {
        private const val MIN_SHOT_INTERVAL_MS = 350L

        /** The running service, or null while it's turned off in Settings. */
        @Volatile
        var instance: BotAccessibilityService? = null
            private set
    }
}
