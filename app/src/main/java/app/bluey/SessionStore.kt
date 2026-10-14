package app.bluey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Each listening session's meeting notes, kept as plain files: Bluey Notes/<date> <id>/ with notes.md,
 * session.json and the audio chunks. Lives on the main thread; files are written in the background.
 */
class SessionStore(context: Context) {
    class Entry(val kind: String, val time: Long, val text: String, val speaker: String? = null)

    class Session(val id: String, val started: Long, val folder: File) {
        var ended: Long? = null
        val entries = mutableListOf<Entry>()
        var transcribing = 0
        /** Names for speaker letters ("A" -> "Alberto"), set in the notes viewer. */
        val speakers = mutableMapOf<String, String>()
    }

    /** A saved session, for the list in the app. */
    class Saved(val folder: File, val title: String, val summary: String) {
        val notes: File get() = File(folder, "notes.md")
    }

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Bluey Notes")
    private val writer = Executors.newSingleThreadExecutor()
    private val pendingSaves = mutableSetOf<Session>()

    var current: Session? = null
        private set

    fun startSession(): Session {
        current?.let { endSession() }
        val started = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().take(8)
        val name = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(started)) + " " + id
        val folder = File(root, name)
        File(folder, "audio").mkdirs()
        writeReadmeIfNeeded()
        val session = Session(id, started, folder)
        current = session
        save(session)
        return session
    }

    fun endSession() {
        val session = current ?: return
        session.ended = System.currentTimeMillis()
        current = null
        if (session.entries.isEmpty() && session.transcribing == 0) {
            // Nothing was said: don't keep an empty folder (audio chunks still arriving keep it).
            Bluey.main.postDelayed({
                if (session.entries.isEmpty() && session.transcribing == 0) {
                    writer.execute { if ((File(session.folder, "audio").list()?.isEmpty() != false)) session.folder.deleteRecursively() }
                }
            }, 3000)
        } else {
            save(session)
        }
        Bluey.changed()
    }

    private fun add(kind: String, text: String) {
        val session = current ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        session.entries.add(Entry(kind, System.currentTimeMillis(), clean))
        save(session)
    }

    fun heard(text: String) = add("heard", text)
    fun asked(text: String) = add("asked", text)
    fun reply(text: String) = add("reply", text)
    fun report(text: String) = add("report", text)

    /** A recorded chunk came back with speakers: it replaces the rough live lines for that stretch. */
    fun transcribed(session: Session, chunkStart: Long, chunkEnd: Long, segments: List<Segment>) {
        session.entries.removeAll { it.kind == "heard" && it.time >= chunkStart - 1000 && it.time <= chunkEnd + 2500 }
        for (segment in segments) {
            session.entries.add(
                Entry("transcript", chunkStart + (segment.start * 1000).toLong(), segment.text, segment.speaker)
            )
        }
        session.entries.sortBy { it.time }
        save(session)
    }

    /** What's been said this session, handed to a fresh connection so he keeps context. */
    fun recentText(limit: Int = 6000): String? {
        val session = current ?: return null
        val text = session.entries.filter { it.kind != "report" }
            .joinToString("\n") { (if (it.kind == "reply") "Bluey: " else "") + it.text }
        return if (text.isEmpty()) null else text.takeLast(limit)
    }

    /** Writes the session's files a moment after the changes settle. */
    fun save(session: Session) {
        if (!pendingSaves.add(session)) return
        Bluey.main.postDelayed({
            pendingSaves.remove(session)
            val json = toJson(session)
            val markdown = markdown(session)
            writer.execute {
                runCatching {
                    session.folder.mkdirs()
                    File(session.folder, "session.json").writeText(json.toString(2))
                    File(session.folder, "notes.md").writeText(markdown)
                }
                Bluey.changed()
            }
        }, 1200)
    }

    fun saved(): List<Saved> {
        val folders = root.listFiles()?.filter { it.isDirectory && File(it, "notes.md").exists() } ?: return emptyList()
        return folders.sortedByDescending { it.name }.take(40).map { folder ->
            val json = runCatching { JSONObject(File(folder, "session.json").readText()) }.getOrNull()
            val started = json?.optLong("started") ?: folder.lastModified()
            val entries = json?.optJSONArray("entries")?.length() ?: 0
            val title = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(started))
            val ended = json?.optLong("ended", 0L) ?: 0L
            val minutes = if (ended > 0) ((ended - started) / 60000).toInt() else -1
            val summary = (if (minutes >= 0) "${minutes / 60}h ${minutes % 60}m · " else "Recording now · ") +
                "$entries lines"
            Saved(folder, title, summary)
        }
    }

    private fun toJson(session: Session): JSONObject {
        val entries = JSONArray()
        for (e in session.entries) {
            entries.put(
                JSONObject().put("kind", e.kind).put("time", e.time / 1000.0).put("text", e.text)
                    .apply { e.speaker?.let { put("speaker", it) } }
            )
        }
        return JSONObject()
            .put("id", session.id)
            .put("started", session.started)
            .put("ended", session.ended ?: 0L)
            .put("transcribing", session.transcribing)
            .put("entries", entries)
            .put("speakers", JSONObject(session.speakers as Map<*, *>))
    }

    /** A saved session, read back from its files (or the live one, if it's still going). */
    fun load(folder: File): Session? {
        current?.let { if (it.folder == folder) return it }
        val json = runCatching { JSONObject(File(folder, "session.json").readText()) }.getOrNull() ?: return null
        val session = Session(json.optString("id"), json.optLong("started"), folder)
        session.ended = json.optLong("ended", 0L).takeIf { it > 0 }
        session.transcribing = 0
        val entries = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            session.entries.add(
                Entry(
                    e.optString("kind"), (e.optDouble("time") * 1000).toLong(), e.optString("text"),
                    if (e.has("speaker")) e.optString("speaker") else null
                )
            )
        }
        json.optJSONObject("speakers")?.let { names ->
            names.keys().forEach { key -> names.optString(key).takeIf { it.isNotBlank() }?.let { session.speakers[key] = it } }
        }
        return session
    }

    /** The speaker letters that appear in a session's transcript, in order. */
    fun speakerLetters(session: Session): List<String> =
        session.entries.mapNotNull { it.speaker }.filter { it != "?" }.distinct().sorted()

    /** Names the speakers and rewrites the session's files. */
    fun nameSpeakers(session: Session, names: Map<String, String>) {
        session.speakers.clear()
        names.forEach { (letter, name) -> if (name.isNotBlank()) session.speakers[letter] = name.trim() }
        save(session)
    }

    /** The notes as plain text, for reading in the app. */
    fun text(session: Session): String = markdown(session)

    private fun markdown(session: Session): String {
        val day = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault())
        val clock = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        val out = mutableListOf("# Bluey session: ${day.format(Date(session.started))}", "")
        val ended = session.ended
        if (ended != null) {
            val minutes = ((ended - session.started) / 60000).toInt()
            out.add("Ended ${clock.format(Date(ended))} (${minutes / 60}h ${minutes % 60}m).")
        } else {
            out.add("Still recording.")
        }
        if (session.transcribing > 0) {
            out.add("${session.transcribing} recording chunk(s) still being transcribed; this file updates when they're done.")
        }
        val asked = session.entries.count { it.kind == "asked" }
        if (asked > 0) out.add("I asked Bluey $asked question(s); they're marked inline below.")
        out.addAll(listOf("", "## Transcript", ""))
        for (entry in session.entries) {
            val time = clock.format(Date(entry.time))
            when (entry.kind) {
                "transcript" -> {
                    val letter = entry.speaker ?: "?"
                    val who = session.speakers[letter] ?: "Speaker $letter"
                    out.add("**[$time] $who:** ${entry.text}")
                }
                "heard" -> out.add("[$time] (live) ${entry.text}")
                "asked" -> out.add("> **[$time] I asked Bluey:** ${entry.text}")
                "reply" -> out.add("> **Bluey:** ${entry.text}")
                "report" -> out.add("> **Research report:**\n" + entry.text.lines().joinToString("\n") { "> $it" })
                else -> out.add("[$time] ${entry.text}")
            }
            out.add("")
        }
        return out.joinToString("\n")
    }

    private fun writeReadmeIfNeeded() {
        writer.execute {
            val file = File(root, "README.md")
            if (file.exists()) return@execute
            root.mkdirs()
            file.writeText(
                """
                # Bluey Notes

                Meeting notes recorded by Bluey (the blueberry on the phone). One folder per listening session,
                named by start time.

                In each session folder:
                - `notes.md`: the full transcript with timestamps and speaker labels, plus questions to Bluey,
                  Bluey's replies and any research reports, in time order. Start here.
                - `session.json`: the same data, structured.
                - `audio/`: the raw recording, as five-minute AAC chunks.

                Speaker letters (A, B, …) are assigned per five-minute chunk, so the same letter can mean different
                people in different chunks. Lines marked "(live)" are a rough live transcript for audio that hasn't
                been fully transcribed yet.
                """.trimIndent()
            )
        }
    }
}
