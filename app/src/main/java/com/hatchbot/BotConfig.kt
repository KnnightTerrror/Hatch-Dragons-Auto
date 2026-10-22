package com.hatchbot

import android.content.Context
import org.json.JSONObject

/** One action in a routine. Screen points are in pixels; TapPoint and Swipe use 0..1 fractions of the screen. */
sealed class Step {
    data class Tap(
        val template: String,
        val threshold: Double?,
        val optional: Boolean,
        val offsetX: Float,
        val offsetY: Float,
        val waitAfterMs: Long,
    ) : Step()

    data class TapAll(val templates: List<String>, val threshold: Double?, val maxTaps: Int, val delayMs: Long) : Step()
    data class WaitFor(val template: String, val threshold: Double?, val timeoutMs: Long, val optional: Boolean) : Step()
    data class TapPoint(val x: Float, val y: Float, val waitAfterMs: Long) : Step()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long, val waitAfterMs: Long) : Step()
    data class Wait(val ms: Long) : Step()
    data class Back(val waitAfterMs: Long) : Step()
}

data class Routine(val id: String, val name: String, val steps: List<Step>) {
    /** Template pictures this routine can't run without. */
    fun missingTemplates(available: Set<String>): List<String> = steps.flatMap { step ->
        when (step) {
            is Step.Tap -> if (!step.optional && step.template !in available) listOf(step.template) else emptyList()
            is Step.WaitFor -> if (!step.optional && step.template !in available) listOf(step.template) else emptyList()
            is Step.TapAll -> if (step.templates.none { it in available }) step.templates else emptyList()
            else -> emptyList()
        }
    }.distinct()

    fun isReady(available: Set<String>) = missingTemplates(available).isEmpty()
}

data class BotConfig(
    val gamePackage: String,
    val loopDelayMs: Long,
    val matchThreshold: Double,
    val routines: List<Routine>,
) {
    fun templateNames(): Set<String> = routines.flatMap { it.steps }.flatMap { step ->
        when (step) {
            is Step.Tap -> listOf(step.template)
            is Step.WaitFor -> listOf(step.template)
            is Step.TapAll -> step.templates
            else -> emptyList()
        }
    }.toSet()

    companion object {
        private const val DIR = "bot"

        fun load(context: Context): BotConfig {
            val text = context.assets.open("$DIR/routines.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val routines = json.getJSONArray("routines")
            return BotConfig(
                gamePackage = json.optString("gamePackage"),
                loopDelayMs = json.optLong("loopDelayMs", 1500),
                matchThreshold = json.optDouble("matchThreshold", 0.85),
                routines = (0 until routines.length()).map { parseRoutine(routines.getJSONObject(it)) },
            )
        }

        /** Names (without .png) of the template pictures bundled in assets/bot/templates. */
        fun availableTemplates(context: Context): Set<String> =
            (context.assets.list("$DIR/templates") ?: emptyArray())
                .filter { it.endsWith(".png") }
                .map { it.removeSuffix(".png") }
                .toSet()

        private fun parseRoutine(json: JSONObject): Routine {
            val steps = json.getJSONArray("steps")
            return Routine(
                id = json.getString("id"),
                name = json.getString("name"),
                steps = (0 until steps.length()).map { parseStep(steps.getJSONObject(it)) },
            )
        }

        private fun parseStep(json: JSONObject): Step {
            val threshold = if (json.has("threshold")) json.getDouble("threshold") else null
            return when (val type = json.getString("type")) {
                "tap" -> Step.Tap(
                    template = json.getString("template"),
                    threshold = threshold,
                    optional = json.optBoolean("optional", false),
                    offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                    offsetY = json.optDouble("offsetY", 0.0).toFloat(),
                    waitAfterMs = json.optLong("waitAfterMs", 800),
                )
                "tapAll" -> {
                    val names = json.getJSONArray("templates")
                    Step.TapAll(
                        templates = (0 until names.length()).map { names.getString(it) },
                        threshold = threshold,
                        maxTaps = json.optInt("maxTaps", 10),
                        delayMs = json.optLong("delayMs", 400),
                    )
                }
                "waitFor" -> Step.WaitFor(
                    template = json.getString("template"),
                    threshold = threshold,
                    timeoutMs = json.optLong("timeoutMs", 5000),
                    optional = json.optBoolean("optional", false),
                )
                "tapPoint" -> Step.TapPoint(
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat(),
                    waitAfterMs = json.optLong("waitAfterMs", 800),
                )
                "swipe" -> Step.Swipe(
                    x1 = json.getDouble("x1").toFloat(),
                    y1 = json.getDouble("y1").toFloat(),
                    x2 = json.getDouble("x2").toFloat(),
                    y2 = json.getDouble("y2").toFloat(),
                    durationMs = json.optLong("durationMs", 400),
                    waitAfterMs = json.optLong("waitAfterMs", 800),
                )
                "wait" -> Step.Wait(json.getLong("ms"))
                "back" -> Step.Back(json.optLong("waitAfterMs", 800))
                else -> throw IllegalArgumentException("Unknown step type: $type")
            }
        }
    }
}

/** Which routines the player turned on in the app. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("hatchbot", Context.MODE_PRIVATE)

    fun isEnabled(routineId: String) = prefs.getBoolean("routine_$routineId", true)

    fun setEnabled(routineId: String, enabled: Boolean) {
        prefs.edit().putBoolean("routine_$routineId", enabled).apply()
    }
}
