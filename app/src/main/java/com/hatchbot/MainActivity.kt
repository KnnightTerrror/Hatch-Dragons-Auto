package com.hatchbot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: turn the service on, pick routines, show the floating bar. */
class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = BotConfig.load(this)
        val available = BotConfig.availableTemplates(this)
        val prefs = Prefs(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        column.addView(text("HatchBot", 26f))
        column.addView(text("Build $version", 12f))

        column.addView(heading("1. Turn on the bot service"))
        column.addView(text("Android only lets accessibility services look at the screen and tap. Find HatchBot in the list and switch it on."))
        serviceStatus = text("")
        column.addView(serviceStatus)
        column.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(text("Switch greyed out or says \"Restricted setting\"? Tap below, then ⋮ in the top corner → Allow restricted settings, and try again.", 13f))
        column.addView(button("Open HatchBot app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        })

        column.addView(heading("2. Pick what the bot does"))
        for (routine in config.routines) {
            val missing = routine.missingTemplates(available)
            column.addView(CheckBox(this).apply {
                text = if (missing.isEmpty()) {
                    routine.name
                } else {
                    "${routine.name}\n(needs pictures: ${missing.joinToString()})"
                }
                isChecked = prefs.isEnabled(routine.id)
                setOnCheckedChangeListener { _, checked -> prefs.setEnabled(routine.id, checked) }
            })
        }

        column.addView(heading("3. Play"))
        column.addView(text("Open Hatch Dragons and use the floating bar: ▶ start, ■ stop, 📷 save what the bot sees to Pictures/HatchBot, ✕ hide. Drag the bar by its text."))
        column.addView(button("Show floating bar") {
            val service = BotAccessibilityService.instance
            if (service == null) toast("Turn on the bot service first") else service.showPanel()
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        serviceStatus.text =
            if (BotAccessibilityService.instance != null) "✅ Service is on" else "❌ Service is off"
    }

    private fun heading(value: String) = text(value, 18f).apply { setPadding(0, dp(20), 0, dp(4)) }

    private fun text(value: String, sizeSp: Float = 15f) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
