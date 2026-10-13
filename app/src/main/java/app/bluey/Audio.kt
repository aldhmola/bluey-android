package app.bluey

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The phone's microphone at 24 kHz mono PCM16 (what the Realtime API wants), read on its own thread. */
class Mic {
    companion object {
        const val RATE = 24000
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Calls `onAudio` with ~100 ms of little-endian PCM16 at a time, on the mic thread. */
    @SuppressLint("MissingPermission")
    fun start(onAudio: (ByteArray) -> Unit) {
        if (running) return
        val minSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = max(minSize * 2, RATE / 5 * 2)
        var made: AudioRecord? = null
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                made = candidate
                break
            }
            candidate.release()
        }
        val rec = made ?: throw IllegalStateException("the microphone wouldn't start")
        rec.startRecording()
        record = rec
        running = true
        thread = Thread({
            val chunk = ByteArray(RATE / 10 * 2)
            while (running) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read > 0) onAudio(chunk.copyOf(read))
                else if (read < 0) Thread.sleep(20)
            }
        }, "bluey-mic").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

/** His little cartoon chirp: a few soft, bell-like notes from a happy pentatonic scale. */
class Chirper {
    private val rate = Mic.RATE
    private val worker = Executors.newSingleThreadExecutor()
    private var track: AudioTrack? = null

    /** Chirps still playing. */
    @Volatile var pending = 0
        private set

    private fun track(): AudioTrack {
        track?.let { return it }
        val made = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(rate * 4)
            .build()
        made.play()
        track = made
        return made
    }

    /** Plays `syllables` notes; `done` runs on the main thread when they've finished. */
    fun chirp(syllables: Int, volume: Float, done: () -> Unit) {
        val samples = synth(syllables)
        pending++
        worker.execute {
            try {
                if (volume > 0.01f) {
                    val t = track()
                    t.setVolume(volume)
                    t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    Thread.sleep((samples.size * 1000L / rate) / 2)
                }
            } catch (e: Exception) {
                // No sound is fine; he still replies.
            }
            Bluey.main.post {
                pending = max(0, pending - 1)
                done()
            }
        }
    }

    private fun synth(syllables: Int): FloatArray {
        // C major pentatonic, two octaves up high (C6…E7), dropped an octave so it's rounder.
        val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)
        var index = Random.nextInt(1, 4)
        val out = ArrayList<Float>()
        for (i in 0 until syllables) {
            if (i > 0) index = min(max(index + listOf(-1, 1, 1, 2).random(), 0), scale.size - 1)
            val note = scale[index] * 0.5
            val last = i == syllables - 1
            val duration = if (last) 0.13 else Random.nextDouble(0.07, 0.09)
            val count = (duration * rate).toInt()
            var phase = 0.0
            for (n in 0 until count) {
                val time = n.toDouble() / rate
                val t = n.toDouble() / count
                val scoop = 1 - 0.18 * exp(-time / 0.012)
                val wobble = if (last) 1 + 0.012 * sin(time * 2 * PI * 18) else 1.0
                phase += 2 * PI * note * scoop * wobble / rate
                val attack = min(1.0, time / 0.006)
                val envelope = attack * exp(-t * 3.2) * (1 - t.pow(6))
                val wave = sin(phase) + 0.12 * sin(2 * phase)
                out.add((wave * envelope * 0.26).toFloat())
            }
            repeat((rate * 0.028).toInt()) { out.add(0f) }
        }
        return out.toFloatArray()
    }
}
