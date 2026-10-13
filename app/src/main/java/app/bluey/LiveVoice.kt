package app.bluey

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * A live session with OpenAI's Realtime API. Once he's awake the mic is on the whole time, so everything
 * you say becomes context, but he stays quiet until you hold him and ask. He never talks out loud: replies
 * are text (speech bubbles) with a little chirp. Everything here runs on the main thread except the mic.
 */
class LiveVoice {
    enum class State { ASLEEP, WAKING, LISTENING, ASKING, THINKING, SPEAKING }

    var state = State.ASLEEP
        private set

    /** His reply so far (or the last one, until you ask again). */
    var caption: String = ""
        private set

    private val mic = Mic()
    private val chirper = Chirper()
    private val recorder = ChunkRecorder()

    @Volatile private var socket: WebSocket? = null
    private var wantAwake = false
    private var reconnecting = false

    private var transcript = ""
    private var responseActive = false
    private var sleepAfterReply = false
    private var holding = false
    private var askWhenReady = false
    private var chirpedThisResponse = false
    private val askedItems = mutableSetOf<String>()
    private var awaitingQuestion = false
    private var toolsRunning = 0

    val isAwake: Boolean get() = state != State.ASLEEP

    private fun setState(new: State) {
        if (new == state) return
        state = new
        Bluey.brain.voiceStateChanged(new)
        Bluey.changed()
    }

    private fun showCaption(text: String, done: Boolean) {
        caption = text
        Bluey.brain.caption(text, done)
        Bluey.changed()
    }

    // MARK: Wake and sleep

    fun toggle() {
        if (state == State.ASLEEP) wake() else sleep()
    }

    fun wake() {
        if (state != State.ASLEEP) return
        val app = Bluey.app
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showCaption("I need the microphone. Open the Bluey app and allow it.", true)
            return
        }
        if (Bluey.settings.openAIKey == null) {
            showCaption("I need an OpenAI API key. Add one in the Bluey app.", true)
            return
        }
        VoiceService.ensureRunning(app)
        wantAwake = true
        setState(State.WAKING)
        Bluey.scope.launch {
            val token = try {
                OpenAI.mintToken(Bluey.brain.sessionConfig())
            } catch (e: Exception) {
                showCaption(e.message ?: "I couldn't start a voice session.", true)
                null
            }
            if (state != State.WAKING) return@launch
            if (token == null) {
                wantAwake = false
                setState(State.ASLEEP)
                return@launch
            }
            connect(token)
        }
    }

    fun sleep() {
        val wasRunning = wantAwake || socket != null
        wantAwake = false
        reconnecting = false
        recorder.finish()
        if (wasRunning) Bluey.notes.endSession()
        askedItems.clear()
        awaitingQuestion = false
        socket?.close(1000, null)
        socket = null
        mic.stop()
        transcript = ""
        responseActive = false
        sleepAfterReply = false
        holding = false
        askWhenReady = false
        toolsRunning = 0
        setState(State.ASLEEP)
    }

    // MARK: Hold to ask

    /** You pressed and held him: what you say now is the question. */
    fun beginAsk() {
        holding = true
        showCaption("", false)
        if (state == State.ASLEEP) {
            wake()
            return
        }
        if (socket == null) return
        if (state == State.LISTENING || state == State.SPEAKING) setState(State.ASKING)
    }

    /** You let go: he answers (or does the thing) using everything he's heard as context. */
    fun endAsk() {
        if (!holding) return
        holding = false
        if (socket == null || state == State.WAKING) {
            askWhenReady = state != State.ASLEEP
            return
        }
        ask()
    }

    /** Asks him something typed, with no voice needed (from the app screen). */
    fun askTyped(text: String) {
        if (socket == null) return
        Bluey.notes.asked(text)
        setState(State.THINKING)
        send(
            JSONObject().put("type", "conversation.item.create").put(
                "item", JSONObject().put("type", "message").put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))
            )
        )
        send(JSONObject().put("type", "response.create"))
    }

    private fun ask() {
        askWhenReady = false
        setState(State.THINKING)
        awaitingQuestion = true
        // Close off what you just said (it may still be mid-sentence) and ask for a reply.
        send(JSONObject().put("type", "input_audio_buffer.commit"))
        send(JSONObject().put("type", "response.create"))
    }

    // MARK: Connection

    private fun connect(token: String) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${OpenAI.REALTIME_MODEL}")
            .header("Authorization", "Bearer $token")
            .build()
        val ws = OpenAI.socketHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                Bluey.main.post { if (webSocket === socket) handle(event) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("Bluey", "realtime closed: $t")
                Bluey.main.post { dropped(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Bluey.main.post { dropped(webSocket) }
            }
        })
        socket = ws

        if (reconnecting) {
            // Same session, new connection: the mic and recording never stopped. Remind him what was said.
            reconnecting = false
            val context = Bluey.notes.recentText()
            if (!context.isNullOrEmpty()) {
                send(
                    JSONObject().put("type", "conversation.item.create").put(
                        "item", JSONObject().put("type", "message").put("role", "user").put(
                            "content", JSONArray().put(
                                JSONObject().put("type", "input_text").put(
                                    "text",
                                    "(Context: what I said earlier in this session, from my notes. Don't reply to this.)\n$context"
                                )
                            )
                        )
                    )
                )
            }
            if (askWhenReady) ask() else setState(if (holding) State.ASKING else State.LISTENING)
            return
        }

        val session = Bluey.notes.startSession()
        if (Bluey.settings.meetingNotes) recorder.begin(session)
        try {
            mic.start { pcm -> onMic(pcm) }
        } catch (e: Exception) {
            showCaption("I couldn't start the microphone: ${e.message}", true)
            sleep()
            return
        }
        chirp(2)
        if (askWhenReady) ask() else setState(if (holding) State.ASKING else State.LISTENING)
    }

    private fun dropped(webSocket: WebSocket) {
        if (webSocket !== socket) return
        if (wantAwake) reconnect(0) else sleep()
    }

    /** The connection dropped mid-session: get a new key and carry on, keeping the mic and recording running. */
    private fun reconnect(attempt: Int) {
        socket = null
        responseActive = false
        toolsRunning = 0
        if (!wantAwake) return
        reconnecting = true
        Bluey.scope.launch {
            val token = runCatching { OpenAI.mintToken(Bluey.brain.sessionConfig()) }.getOrNull()
            if (!wantAwake || socket != null) return@launch
            if (token != null) {
                connect(token)
            } else {
                // Offline for a bit: keep trying (the recording carries on meanwhile).
                Bluey.main.postDelayed({ if (wantAwake && socket == null) reconnect(attempt + 1) },
                    min(30_000L, 2_000L + attempt * 3_000L))
            }
        }
    }

    private fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    /** Mic thread: every 100 ms of sound goes to OpenAI and, if notes are on, into the recording. */
    private fun onMic(pcm: ByteArray) {
        recorder.append(pcm)
        val ws = socket ?: return
        val audio = Base64.encodeToString(pcm, Base64.NO_WRAP)
        ws.send("""{"type":"input_audio_buffer.append","audio":"$audio"}""")
    }

    // MARK: Events

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "response.created" -> {
                responseActive = true
                transcript = ""
                chirpedThisResponse = false
            }

            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                val delta = event.optString("delta", "")
                if (delta.isEmpty()) return
                transcript += delta
                if (!chirpedThisResponse && transcript.isNotBlank()) {
                    chirpedThisResponse = true
                    setState(State.SPEAKING)
                    chirp(min(max(transcript.split(" ").size, 3), 5))
                }
                showCaption(transcript, false)
            }

            "input_audio_buffer.committed" -> {
                val item = event.optString("item_id", "")
                if (item.isNotEmpty()) {
                    val asked = holding || awaitingQuestion
                    if (asked) askedItems.add(item)
                    if (awaitingQuestion && !holding) awaitingQuestion = false
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val item = event.optString("item_id", "")
                val text = event.optString("transcript", "").trim()
                if (text.isNotEmpty()) {
                    if (askedItems.contains(item)) Bluey.notes.asked(text) else Bluey.notes.heard(text)
                }
            }

            "response.done" -> {
                responseActive = false
                if (transcript.isNotEmpty()) {
                    showCaption(transcript, true)
                    Bluey.notes.reply(transcript)
                }
                val output = event.optJSONObject("response")?.optJSONArray("output") ?: JSONArray()
                val calls = mutableListOf<JSONObject>()
                for (i in 0 until output.length()) {
                    val item = output.getJSONObject(i)
                    if (item.optString("type") == "function_call") calls.add(item)
                }
                if (calls.isNotEmpty()) {
                    if (state != State.SPEAKING) setState(State.THINKING)
                    runTools(calls)
                }
                finishIfQuiet()
            }

            "error" -> {
                val error = event.optJSONObject("error")
                if (error?.optString("code") == "input_audio_buffer_commit_empty") awaitingQuestion = false
                Log.w("Bluey", "realtime error: ${error?.optString("message")}")
            }
        }
    }

    private fun runTools(calls: List<JSONObject>) {
        toolsRunning += 1
        val ws = socket
        Bluey.scope.launch {
            val images = mutableListOf<String>()
            for (call in calls) {
                val name = call.optString("name")
                val callID = call.optString("call_id")
                val arguments = call.optString("arguments", "{}")
                if (name == "go_to_sleep") sleepAfterReply = true
                val result = try {
                    Bluey.brain.runTool(name, arguments)
                } catch (e: Exception) {
                    ToolResult("That didn't work: ${e.message}")
                }
                if (socket !== ws || ws == null) return@launch
                if (name == "web_research") {
                    val marker = result.text.indexOf(Brain.REPORT_MARKER)
                    if (marker >= 0) Bluey.notes.report(result.text.substring(marker + Brain.REPORT_MARKER.length))
                }
                send(
                    JSONObject().put("type", "conversation.item.create").put(
                        "item", JSONObject().put("type", "function_call_output").put("call_id", callID)
                            .put("output", result.text)
                    )
                )
                result.image?.let { images.add(it) }
            }
            toolsRunning = max(0, toolsRunning - 1)
            // Screenshots go in as a user image so he can actually see the screen.
            for (image in images) {
                send(
                    JSONObject().put("type", "conversation.item.create").put(
                        "item", JSONObject().put("type", "message").put("role", "user").put(
                            "content", JSONArray().put(
                                JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$image")
                            )
                        )
                    )
                )
            }
            send(JSONObject().put("type", "response.create"))
        }
    }

    /** Back to listening once he's replied and chirped (or asleep, if he said goodbye). */
    private fun finishIfQuiet() {
        if (chirper.pending > 0 || responseActive || toolsRunning > 0) return
        if (sleepAfterReply) {
            sleep()
            return
        }
        if (state == State.SPEAKING || state == State.THINKING) setState(if (holding) State.ASKING else State.LISTENING)
    }

    private fun chirp(syllables: Int) {
        chirper.chirp(syllables, Bluey.settings.volume) { finishIfQuiet() }
    }

    /** Plays a chirp on its own (the volume slider's test button). */
    fun testChirp() {
        chirper.chirp(4, Bluey.settings.volume) {}
    }
}
