package uz.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { f() }
        }
        fun txt(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        val claudeKey = EditText(this).apply {
            hint = "Anthropic API kalit (sk-ant-...)"
            setText(prefs.getString("claude", "")); isSingleLine = true
        }
        val pvKey = EditText(this).apply {
            hint = "Picovoice AccessKey"
            setText(prefs.getString("picovoice", "")); isSingleLine = true
        }
        status = TextView(this)

        root.addView(txt("JARVIS"))
        root.addView(claudeKey)
        root.addView(pvKey)
        root.addView(txt("1-qadam: ruxsatlar"))
        root.addView(btn("Mikrofon, qo'ng'iroq, SMS, kontaktlar") {
            requestPermissions(arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS,
                "android.permission.POST_NOTIFICATIONS"), 1)
        })
        root.addView(btn("Ilova sozlamalari (cheklangan sozlamaga ruxsat)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        })
        root.addView(txt("2-qadam: maxsus imkoniyatlar"))
        root.addView(btn("Accessibility'ni yoqish (Jarvis)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("Boshqa ilovalar ustida ko'rsatish") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        })
        root.addView(btn("Batareya cheklovini o'chirish") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(txt("3-qadam: ishga tushirish"))
        root.addView(btn("Ishga tushirish") {
            prefs.edit()
                .putString("claude", claudeKey.text.toString().trim())
                .putString("picovoice", pvKey.text.toString().trim())
                .apply()
            startForegroundService(Intent(this, JarvisService::class.java))
        })
        root.addView(btn("To'xtatish") {
            stopService(Intent(this, JarvisService::class.java))
        })
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = "Mikrofon: ${ok(mic)}\n" +
            "Accessibility: ${ok(JarvisAccessibility.instance != null)}\n" +
            "Overlay: ${ok(Settings.canDrawOverlays(this))}"
    }

    private fun ok(b: Boolean) = if (b) "yoqilgan ✅" else "o'chiq ❌"
}
