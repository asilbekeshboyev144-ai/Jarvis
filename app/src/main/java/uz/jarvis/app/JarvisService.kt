package uz.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
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

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var porcupine: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private lateinit var actions: Actions
    private lateinit var claude: ClaudeClient
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        startInForeground()
        actions = Actions(this)
        claude = ClaudeClient(prefs.getString("claude", "") ?: "")
        tts = TextToSpeech(this, this)
        startWakeWord(prefs.getString("picovoice", "") ?: "")
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

    private fun startWakeWord(key: String) {
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(this, PorcupineManagerCallback { onWake() })
            porcupine?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine xatosi", e)
            speak("Uyg'onish so'zi ishlamadi. Picovoice kalitni tekshiring.")
        }
    }

    private fun onWake() {
        if (busy) return
        busy = true
        main.post {
            try { porcupine?.stop() } catch (_: Exception) {}
            speak("Eshitaman") { listen() }
        }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Ovozni tanish xizmati topilmadi") { finishCycle() }
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) finishCycle() else handle(t)
                }
                override fun onError(error: Int) { finishCycle() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
        recognizer?.startListening(i)
    }

    private fun handle(command: String) {
        worker.execute {
            runAgent(command)
            main.post { finishCycle() }
        }
    }

    private fun finishCycle() {
        recognizer?.destroy(); recognizer = null
        busy = false
        try { porcupine?.start() } catch (e: Exception) { Log.e(TAG, "restart", e) }
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
            speakBlocking("Vazifa juda uzoq davom etdi, to'xtatdim")
        } catch (e: Exception) {
            Log.e(TAG, "agent", e)
            speakBlocking("Xatolik yuz berdi")
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
        if (status != TextToSpeech.SUCCESS) return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { fire(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { fire(utteranceId) }
        })
        fun ok(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        // Javob o'zbekcha yoziladi, lekin INGLIZ ovozi o'qiydi.
        // Boshqa ovoz kerak bo'lsa, TTS_LANG ni o'zgartiring: "uz", "tr", "ru"...
        if (!ok(t.setLanguage(Locale(TTS_LANG)))) t.setLanguage(Locale.getDefault())
        ttsReady = true
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
        try { porcupine?.stop(); porcupine?.delete() } catch (_: Exception) {}
        recognizer?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
