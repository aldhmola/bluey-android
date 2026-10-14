package app.bluey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A short researched answer: a title, up to four paragraphs, and where it came from. */
data class ResearchReport(
    val question: String,
    val title: String,
    val paragraphs: List<String>,
    val sources: List<Pair<String, String>>,
) {
    val plainText: String get() = (listOf(title) + paragraphs).joinToString("\n\n")
}

data class Segment(val speaker: String, val start: Double, val end: Double, val text: String)

class OpenAIError(message: String) : Exception(message)

/** The plain HTTP calls: voice-session keys, transcription and web research. */
object OpenAI {
    private const val REALTIME_FULL = "gpt-realtime-2.1"
    private const val REALTIME_MINI = "gpt-realtime-2.1-mini"

    /** His voice brain: the full model, or mini when "Cheaper brain" is on (about a third of the price). */
    val realtimeModel: String get() = if (Bluey.settings.cheapBrain) REALTIME_MINI else REALTIME_FULL
    const val RESEARCH_MODEL = "gpt-5.5"
    private const val DIARIZE_MODEL = "gpt-4o-transcribe-diarize"
    private val FALLBACK_MODELS = listOf("gpt-transcribe", "gpt-4o-transcribe")

    private val JSON = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    /** For the live voice session: no read timeout, pings to keep it open. */
    val socketHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun key(): String = Bluey.settings.openAIKey
        ?: throw OpenAIError("I need an OpenAI API key. Add one in the Bluey app.")

    private fun postJson(path: String, body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/$path")
            .header("Authorization", "Bearer ${key()}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    /** A short-lived key for one voice session, with the whole session setup baked in. */
    suspend fun mintToken(session: JSONObject): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("expires_after", JSONObject().put("anchor", "created_at").put("seconds", 600))
            .put("session", session)
        val (code, text) = try {
            postJson("realtime/client_secrets", body)
        } catch (e: OpenAIError) {
            throw e
        } catch (e: Exception) {
            throw OpenAIError("I can't reach OpenAI. Check the internet connection.")
        }
        val value = if (code == 200) runCatching { JSONObject(text).getString("value") }.getOrNull() else null
        value ?: throw OpenAIError(
            if (code == 401) "OpenAI says the API key is wrong. Check it in the Bluey app."
            else "OpenAI wouldn't start a voice session: $code ${text.take(160)}"
        )
    }

    // MARK: Transcription

    suspend fun transcribe(audio: File): List<Segment> = withContext(Dispatchers.IO) {
        try {
            diarized(audio)
        } catch (e: Exception) {
            var last: Exception = e
            for (model in FALLBACK_MODELS) {
                try {
                    return@withContext plain(audio, model)
                } catch (e2: Exception) {
                    last = e2
                }
            }
            throw last
        }
    }

    private fun upload(audio: File, fields: Map<String, String>): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart("file", "chunk.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
        }.build()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${key()}")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (response.code != 200) throw OpenAIError("transcription ${response.code} ${text.take(200)}")
            return JSONObject(text)
        }
    }

    private fun diarized(audio: File): List<Segment> {
        val json = upload(
            audio,
            mapOf("model" to DIARIZE_MODEL, "response_format" to "diarized_json", "chunking_strategy" to "auto")
        )
        val raw = json.optJSONArray("segments") ?: JSONArray()
        // Join one speaker's consecutive pieces into a single turn.
        val turns = mutableListOf<Segment>()
        for (i in 0 until raw.length()) {
            val s = raw.getJSONObject(i)
            val segment = Segment(
                s.optString("speaker", "?"), s.optDouble("start", 0.0), s.optDouble("end", 0.0),
                s.optString("text", "").trim()
            )
            if (segment.text.isEmpty()) continue
            val last = turns.lastOrNull()
            if (last != null && last.speaker == segment.speaker && segment.start - last.end < 2.5) {
                turns[turns.size - 1] = last.copy(text = last.text + " " + segment.text, end = segment.end)
            } else {
                turns.add(segment)
            }
        }
        return turns
    }

    private fun plain(audio: File, model: String): List<Segment> {
        val json = upload(audio, mapOf("model" to model, "response_format" to "json"))
        val text = json.optString("text", "").trim()
        return if (text.isEmpty()) emptyList() else listOf(Segment("?", 0.0, 0.0, text))
    }

    // MARK: Research

    suspend fun research(question: String, context: String?): ResearchReport = withContext(Dispatchers.IO) {
        var input = question
        if (!context.isNullOrBlank()) input += "\n\nWhat's on the user's phone screen, for context: $context"
        val body = JSONObject()
            .put("model", RESEARCH_MODEL)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            .put(
                "instructions",
                "Research the question on the web and write a short, friendly report for a busy person. " +
                    "First line: a plain title of under eight words. Then one to four short paragraphs (four at most), " +
                    "leading with the direct answer, then the most useful details. Plain text only: no markdown, " +
                    "no headings, no bullet lists, and no links or citations in the text."
            )
            .put("input", input)
        val (code, text) = postJson("responses", body)
        if (code != 200) throw OpenAIError("The research didn't come back: $code ${text.take(160)}")
        val json = JSONObject(text)

        val answer = StringBuilder()
        val sources = mutableListOf<Pair<String, String>>()
        val output = json.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                answer.append(part.optString("text", ""))
                val notes = part.optJSONArray("annotations") ?: continue
                for (k in 0 until notes.length()) {
                    val note = notes.getJSONObject(k)
                    val url = note.optString("url", "").replace(Regex("[?&]utm_source=[^&]*$"), "")
                    if (url.isEmpty() || sources.any { it.second == url }) continue
                    val title = note.optString("title", "").ifBlank { android.net.Uri.parse(url).host ?: url }
                    sources.add(title to url)
                }
            }
        }
        var clean = answer.toString()
            .replace(Regex("""\s*\(\[[^\]]*]\([^)]*\)\)"""), "")
            .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace(Regex("""\s*\[https?://[^\]]*]"""), "")
            .replace("**", "")
        clean = clean.replace("#", "")
        val blocks = clean.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val title = blocks.firstOrNull() ?: throw OpenAIError("The research came back empty.")
        ResearchReport(question, title, blocks.drop(1).take(4), sources.take(5))
    }
}
