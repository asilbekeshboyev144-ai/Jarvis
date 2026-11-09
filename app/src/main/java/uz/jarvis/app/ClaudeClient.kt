package uz.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClaudeClient(private val apiKey: String) {

    companion object {
        const val CLAUDE_MODEL = "claude-haiku-5-5"
        const val GEMINI_MODEL = "gemini-flash-latest"
    }

    fun chat(system: String, messages: JSONArray): String =
        if (apiKey.startsWith("sk-ant")) claude(system, messages) else gemini(system, messages)

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("API $code: ${text.take(200)}")
        return text
    }

    private fun claude(system: String, messages: JSONArray): String {
        val body = JSONObject().put("model", CLAUDE_MODEL).put("max_tokens", 1024)
            .put("system", system).put("messages", messages)
        val text = post("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), body)
        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.getString("text"))
        }
        return sb.toString()
    }

    private fun gemini(system: String, messages: JSONArray): String {
        val contents = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = if (m.getString("role") == "assistant") "model" else "user"
            contents.put(JSONObject().put("role", role).put("parts",
                JSONArray().put(JSONObject().put("text", m.getString("content")))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val text = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            mapOf("x-goog-api-key" to apiKey), body)
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }
}
