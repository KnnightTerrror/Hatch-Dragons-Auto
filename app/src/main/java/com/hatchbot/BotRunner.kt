package com.hatchbot

import android.accessibilityservice.AccessibilityService
import android.graphics.PointF
import org.opencv.core.Mat
import kotlin.math.hypot

/** Runs the enabled routines over and over until stopped. */
class BotRunner(private val service: BotAccessibilityService) : Thread("HatchBot") {
    @Volatile
    private var running = true

    fun stopRunning() {
        running = false
        interrupt()
    }

    override fun run() {
        try {
            val config = BotConfig.load(service)
            val available = BotConfig.availableTemplates(service)
            val prefs = Prefs(service)
            val routines = config.routines.filter { prefs.isEnabled(it.id) && it.isReady(available) }
            if (routines.isEmpty()) {
                service.setStatus("Nothing ready yet: needs pictures (see app)")
                return
            }
            val vision = Vision(service, config.templateNames() intersect available)

            var loop = 0
            while (running) {
                loop++
                for (routine in routines) {
                    if (!running) break
                    waitForGame(config.gamePackage)
                    service.setStatus("#$loop ${routine.name}…")
                    val summary = runRoutine(routine, vision, config.matchThreshold)
                    service.setStatus("#$loop ${routine.name}: $summary")
                }
                sleep(config.loopDelayMs)
            }
        } catch (_: InterruptedException) {
            // Stopped from the floating bar.
        } catch (e: Exception) {
            service.setStatus("Error: ${e.message}")
        } finally {
            service.onBotStopped(this)
        }
    }

    /** Pauses while some other app is in front, so the bot never taps outside the game. */
    private fun waitForGame(gamePackage: String) {
        if (gamePackage.isEmpty()) return
        while (running) {
            val front = service.frontPackage()
            if (front == gamePackage) return
            service.setStatus("Paused: open Hatch Dragons (in front: ${front ?: "unknown"})")
            sleep(2000)
        }
    }

    /** Returns a short summary for the status line. */
    private fun runRoutine(routine: Routine, vision: Vision, defaultThreshold: Double): String {
        var taps = 0
        fun done() = if (taps == 0) "nothing to do" else "$taps taps"

        for (step in routine.steps) {
            if (!running) break
            when (step) {
                is Step.Tap -> {
                    if (!vision.has(step.template)) continue
                    val hit = findOnScreen(vision, step.template, step.threshold ?: defaultThreshold)
                    if (hit == null) {
                        if (step.optional) continue else return done()
                    }
                    if (service.tap(hit.x + step.offsetX, hit.y + step.offsetY)) taps++
                    sleep(step.waitAfterMs)
                }

                is Step.TapAll -> {
                    val screen = grab(vision) ?: return "screenshot failed"
                    val hits = mutableListOf<PointF>()
                    for (name in step.templates.filter(vision::has)) {
                        for (p in vision.findAll(screen, name, step.threshold ?: defaultThreshold, step.maxTaps)) {
                            // Two templates can match the same thing; keep only one tap there.
                            if (hits.none { hypot(it.x - p.x, it.y - p.y) < 30f }) hits += p
                        }
                    }
                    screen.release()
                    for (p in hits.take(step.maxTaps)) {
                        if (!running) break
                        if (service.tap(p.x, p.y)) taps++
                        sleep(step.delayMs)
                    }
                }

                is Step.WaitFor -> {
                    val deadline = System.currentTimeMillis() + step.timeoutMs
                    var seen = false
                    while (running && !seen && System.currentTimeMillis() < deadline) {
                        seen = findOnScreen(vision, step.template, step.threshold ?: defaultThreshold) != null
                    }
                    if (!seen && !step.optional) return done()
                }

                is Step.TapPoint -> {
                    val screen = service.screenBounds()
                    if (service.tap(screen.width() * step.x, screen.height() * step.y)) taps++
                    sleep(step.waitAfterMs)
                }

                is Step.Swipe -> {
                    val screen = service.screenBounds()
                    service.swipe(
                        screen.width() * step.x1, screen.height() * step.y1,
                        screen.width() * step.x2, screen.height() * step.y2,
                        step.durationMs,
                    )
                    sleep(step.waitAfterMs)
                }

                is Step.Wait -> sleep(step.ms)

                is Step.Back -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    sleep(step.waitAfterMs)
                }
            }
        }
        return done()
    }

    private fun findOnScreen(vision: Vision, template: String, threshold: Double): PointF? {
        val screen = grab(vision) ?: return null
        return vision.find(screen, template, threshold).also { screen.release() }
    }

    private fun grab(vision: Vision): Mat? {
        val bitmap = service.capture() ?: return null
        return vision.prepare(bitmap).also { bitmap.recycle() }
    }
}
