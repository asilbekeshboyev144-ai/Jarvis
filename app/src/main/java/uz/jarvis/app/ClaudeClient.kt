package uz.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClaudeClient(private val apiKey: String) {

    companion object {
        // Tezroq va arzonroq model. Xohlasangiz "claude-sonnet-5-5" ga almashtiring.
        const val MODEL = "claude-haiku-5-5"
    }

    fun chat(system: String, messages: JSONArray): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", messages)
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("API $code: ${text.take(200)}")
        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.getString("text"))
        }
        return sb.toString()
    }
}
