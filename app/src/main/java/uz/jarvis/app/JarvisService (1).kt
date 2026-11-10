package uz.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "Jarvis"
        private const val CHANNEL = "jarvis"
        private const val TTS_LANG = "en"
        private const val WAKE_LANG = "en-US"   // "Jarvis" so'zini tinglash tili
        private const val CMD_LANG = "uz-UZ"    // buyruq tili

        private val WAKE_WORDS = listOf(
            "jarvis", "jarviz", "jarvish", "jarvas", "charvis", "gervis",
            "жарвис", "джарвис", "жарвиз", "джарвиз"
        )

        private val SYSTEM = """
Sen JARVIS — foydalanuvchining Android telefonidagi ovozli yordamchisan. Foydalanuvchi o'zbek tilida gapiradi (ba'zan rus yoki ingliz tilida).
Faqat bitta JSON obyekt qaytar, boshqa hech narsa yozma, kod belgilarisiz:
{"say":"qisqa javob lotin yozuvidagi o'zbek tilida, 1-2 gap","actions":[...],"done":true}
Mavjud amallar (type maydoni bilan):
open_app {name}, call {target: ism yoki raqam}, sms {target, text}, click {text}, type {text}, scroll {direction: up yoki down}, back, home, recents, wait {ms}, torch {on: true yoki false}, volume {direction: up yoki down} yoki {level: 0-100}, alarm {hour, minute, label}, timer {seconds}, url {url}, search {query}
Qoidalar:
- Oddiy buyruq uchun done=true. Ilova ichida ishlash kerak bo'lsa (masalan, Telegramda xabar yozish) qadam-baqadam ishla: harakat qil va done=false qo'y; keyingi xabarda yangi ekranni ko'rasan.
- click uchun text ekran ro'yxatidagi matnning aniq o'zi bo'lsin.
- To'lov, pul o'tkazish, xarid, hisobni yoki ma'lumotni o'chirish, parol kiritish kabi xavfli ishlarni BAJARMA, rad et.
- "say" matnini ingliz ovozi o'qiydi: apostrof ishlatma (o' o'rniga o, g' o'rniga g yoz), gaplarni sodda va qisqa qil.
- Tushunmasang yoki ekranda kerakli narsa bo'lmasa, say bilan aniqlashtir va done=true qil.
""".trim()
    }

    private abstract class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private lateinit var actions: Actions
    private lateinit var claude: ClaudeClient
    @Volatile private var busy = false
    @Volatile private var wakeActive = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        startInForeground()
        actions = Actions(this)
        claude = ClaudeClient(prefs.getString("claude", "") ?: "")
        tts = TextToSpeech(this, this)
        wakeActive = true
        note("Tinglayapman: Jarvis deng", false)
        main.post { listenWake() }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis tinglab turibdi")
            .setContentText("\"Jarvis\" deb chaqiring")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // Holatni bildirishnomada (va ba'zan toast sifatida) ko'rsatadi: ovoz bo'lmasa ham ko'rinadi.
    private fun note(msg: String, toast: Boolean = true) {
        Log.d(TAG, msg)
        main.post {
            try {
                val n = Notification.Builder(this, CHANNEL)
                    .setContentTitle("Jarvis")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()
                getSystemService(NotificationManager::class.java).notify(1, n)
                if (toast) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun recIntent(lang: String, partial: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)

    // ---- "Jarvis" so'zini doim tinglash ----
    private fun listenWake() {
        if (!wakeActive || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            wakeActive = false
            speak("Speech service not found")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : Listener() {
            override fun onPartialResults(partialResults: Bundle?) { checkWake(partialResults) }
            override fun onResults(results: Bundle?) {
                if (!checkWake(results)) restartWake(250L)
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartWake(250L)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        wakeActive = false
                        speak("Microphone permission is missing")
                    }
                    else -> { note("Tinglash xatosi: $error"); restartWake(1500L) }
                }
            }
        })
        r.startListening(recIntent(WAKE_LANG, true))
    }

    private fun restartWake(delayMs: Long) {
        main.postDelayed({ listenWake() }, delayMs)
    }

    private fun checkWake(b: Bundle?): Boolean {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        list.firstOrNull()?.let { note("Eshitdim: $it", false) }
        val hit = list.any { s ->
            val t = s.lowercase()
            WAKE_WORDS.any { t.contains(it) }
        }
        if (hit) onWake()
        return hit
    }

    private fun onWake() {
        if (busy) return
        busy = true
        main.post {
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
            note("Jarvis eshitildi!")
            speak("Eshitaman") { listenCommand() }
        }
    }

    private fun listenCommand() {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : Listener() {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) finishCycle() else { note("Buyruq: $t"); handle(t) }
            }
            override fun onError(error: Int) { note("Buyruq tinglash xatosi: $error"); finishCycle() }
        })
        r.startListening(recIntent(CMD_LANG, false))
    }

    private fun handle(command: String) {
        worker.execute {
            runAgent(command)
            main.post { finishCycle() }
        }
    }

    private fun finishCycle() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        busy = false
        restartWake(500L)
    }

    private fun screen() =
        JarvisAccessibility.instance?.screenSummary() ?: "(Accessibility yoqilmagan)"

    private fun runAgent(command: String) {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "user")
            .put("content", "Buyruq: $command\n\nHozirgi ekran:\n${screen()}"))
        try {
            for (step in 0 until 8) {
                val reply = claude.chat(SYSTEM, msgs)
                val j = parse(reply)
                val say = j.optString("say")
                if (say.isNotBlank()) speakBlocking(say)
                val acts = j.optJSONArray("actions") ?: JSONArray()
                val results = StringBuilder()
                for (k in 0 until acts.length()) {
                    results.append(actions.exec(acts.getJSONObject(k))).append('\n')
                    Thread.sleep(700)
                }
                if (j.optBoolean("done", true)) return
                Thread.sleep(1200)
                msgs.put(JSONObject().put("role", "assistant").put("content", reply))
                msgs.put(JSONObject().put("role", "user")
                    .put("content", "Natija:\n$results\nHozirgi ekran:\n${screen()}"))
            }
            speakBlocking("The task took too long, I stopped")
        } catch (e: Exception) {
            Log.e(TAG, "agent", e)
            note("Xato: ${e.message?.take(90)}")
            speakBlocking("An error occurred")
        }
    }

    private fun parse(s: String): JSONObject {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        return try {
            JSONObject(if (a >= 0 && b > a) s.substring(a, b + 1) else s)
        } catch (e: Exception) {
            JSONObject().put("say", s.take(200)).put("done", true)
        }
    }

    // ---- TTS ----
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { note("Ovoz xizmati ishga tushmadi (kod $status)"); return }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { fire(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { fire(utteranceId) }
        })
        fun ok(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ok(t.setLanguage(Locale(TTS_LANG)))) t.setLanguage(Locale.getDefault())
        ttsReady = true
        note("Ovoz tayyor", false)
    }

    private fun fire(id: String?) {
        val cb = doneCallbacks.remove(id ?: return) ?: return
        main.post { cb() }
    }

    private fun speak(text: String, done: () -> Unit = {}) {
        val t = tts
        if (t == null || !ttsReady) { done(); return }
        val id = System.nanoTime().toString()
        doneCallbacks[id] = done
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun speakBlocking(text: String) {
        val latch = CountDownLatch(1)
        main.post { speak(text) { latch.countDown() } }
        latch.await(20, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        wakeActive = false
        main.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
