package app.bluey

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rough running total of what Bluey costs, kept on this phone. Prices are OpenAI's list prices per
 * million tokens (October 2026); the real bill is on platform.openai.com, so this is an estimate.
 */
class Usage(context: Context) {
    private val prefs = context.getSharedPreferences("bluey-usage", Context.MODE_PRIVATE)

    private class Prices(
        val textIn: Double, val textCached: Double, val textOut: Double,
        val audioIn: Double, val audioCached: Double, val audioOut: Double,
        val imageIn: Double, val imageCached: Double,
    )

    private val full = Prices(4.0, 0.40, 24.0, 32.0, 0.40, 64.0, 5.0, 0.50)
    private val mini = Prices(0.60, 0.06, 2.40, 10.0, 0.30, 20.0, 0.80, 0.08)

    /** Adds the cost of one Realtime response from its `usage` block. */
    fun addRealtime(model: String, usage: JSONObject?) {
        usage ?: return
        val p = if (model.contains("mini")) mini else full
        val input = usage.optJSONObject("input_token_details")
        val output = usage.optJSONObject("output_token_details")
        val cached = input?.optJSONObject("cached_tokens_details")
        val cText = cached?.optInt("text_tokens") ?: 0
        val cAudio = cached?.optInt("audio_tokens") ?: 0
        val cImage = cached?.optInt("image_tokens") ?: 0
        val text = (input?.optInt("text_tokens") ?: usage.optInt("input_tokens")) - cText
        val audio = (input?.optInt("audio_tokens") ?: 0) - cAudio
        val image = (input?.optInt("image_tokens") ?: 0) - cImage
        val outText = output?.optInt("text_tokens") ?: usage.optInt("output_tokens")
        val outAudio = output?.optInt("audio_tokens") ?: 0
        val dollars = (
            text.coerceAtLeast(0) * p.textIn + cText * p.textCached +
                audio.coerceAtLeast(0) * p.audioIn + cAudio * p.audioCached +
                image.coerceAtLeast(0) * p.imageIn + cImage * p.imageCached +
                outText * p.textOut + outAudio * p.audioOut
            ) / 1_000_000
        add(dollars)
    }

    /** The live transcript of what you say (gpt-4o-mini-transcribe, about $0.003 a minute). */
    fun addLiveTranscription(usage: JSONObject?) {
        usage ?: return
        val seconds = usage.optDouble("seconds", Double.NaN)
        if (!seconds.isNaN()) {
            add(seconds / 60 * 0.003)
            return
        }
        // Token-billed: roughly 600 audio tokens a minute.
        val tokens = usage.optInt("total_tokens", usage.optInt("input_tokens", 0))
        add(tokens / 600.0 * 0.003)
    }

    /** A recorded meeting chunk sent for transcription with speakers ($0.006 a minute). */
    fun addTranscription(seconds: Double) = add(seconds / 60 * 0.006)

    /** A research card: tokens plus the web search call. */
    fun addResearch(usage: JSONObject?) {
        val input = usage?.optInt("input_tokens") ?: 8000
        val output = usage?.optInt("output_tokens") ?: 1500
        add(0.01 + input * 5.0 / 1_000_000 + output * 30.0 / 1_000_000)
    }

    @Synchronized
    private fun add(dollars: Double) {
        if (dollars <= 0 || dollars.isNaN()) return
        val key = "day-" + day.format(Date())
        prefs.edit().putFloat(key, prefs.getFloat(key, 0f) + dollars.toFloat()).apply()
        Bluey.changed()
    }

    fun today(): Double = prefs.getFloat("day-" + day.format(Date()), 0f).toDouble()

    fun thisMonth(): Double {
        val prefix = "day-" + month.format(Date())
        return prefs.all.filterKeys { it.startsWith(prefix) }.values.sumOf { (it as? Float)?.toDouble() ?: 0.0 }
    }

    fun reset() {
        prefs.edit().clear().apply()
        Bluey.changed()
    }

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val month = SimpleDateFormat("yyyy-MM", Locale.US)
}
