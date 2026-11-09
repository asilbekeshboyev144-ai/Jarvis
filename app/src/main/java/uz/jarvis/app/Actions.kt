package uz.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONObject

class Actions(private val svc: Context) {

    private val ctx: Context get() = JarvisAccessibility.instance ?: svc

    fun exec(a: JSONObject): String =
        try { run(a) } catch (e: Exception) { "xato: ${e.message}" }

    private fun run(a: JSONObject): String {
        val acc = JarvisAccessibility.instance
        return when (a.optString("type")) {
            "open_app" -> openApp(a.optString("name"))
            "call" -> {
                val n = number(a.optString("target")) ?: return "raqam topilmadi"
                launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n))))
                "qo'ng'iroq boshlandi"
            }
            "sms" -> {
                val n = number(a.optString("target")) ?: return "raqam topilmadi"
                sendSms(n, a.optString("text"))
                "sms yuborildi"
            }
            "click" -> if (acc?.clickText(a.optString("text")) == true) "bosildi" else "bosib bo'lmadi"
            "type" -> if (acc?.typeText(a.optString("text")) == true) "yozildi" else "yozib bo'lmadi"
            "scroll" -> if (acc?.scroll(a.optString("direction") != "up") == true) "skroll qilindi" else "skroll bo'lmadi"
            "back" -> { acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "orqaga" }
            "home" -> { acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); "bosh ekran" }
            "recents" -> { acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); "oxirgi ilovalar" }
            "wait" -> { Thread.sleep(a.optLong("ms", 1000).coerceIn(0, 5000)); "kutildi" }
            "torch" -> {
                val cm = svc.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], a.optBoolean("on", true))
                "chiroq"
            }
            "volume" -> volume(a)
            "alarm" -> {
                launch(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "budilnik qo'yildi"
            }
            "timer" -> {
                launch(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "taymer qo'yildi"
            }
            "url" -> { launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url")))); "ochildi" }
            "search" -> {
                launch(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))))
                "qidirildi"
            }
            else -> "noma'lum amal"
        }
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun openApp(name: String): String {
        val pm = svc.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.lowercase().trim()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "ilova topilmadi: $name"
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "ilovani ochib bo'lmadi"
        launch(intent)
        return "ochildi: ${match.loadLabel(pm)}"
    }

    private fun number(target: String): String? {
        if (Regex("^[+0-9 ()-]{3,}$").matches(target.trim())) return target.trim()
        val c = svc.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$target%"), null
        ) ?: return null
        c.use { return if (it.moveToFirst()) it.getString(0) else null }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(number: String, text: String) {
        val sm = if (Build.VERSION.SDK_INT >= 31) svc.getSystemService(SmsManager::class.java)
                 else SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
    }

    private fun volume(a: JSONObject): String {
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when {
            a.has("level") -> am.setStreamVolume(AudioManager.STREAM_MUSIC,
                a.optInt("level").coerceIn(0, 100) * max / 100, AudioManager.FLAG_SHOW_UI)
            a.optString("direction") == "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        return "ovoz o'zgartirildi"
    }
}
