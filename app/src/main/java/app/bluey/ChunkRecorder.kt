package app.bluey

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Records a session's audio (the same mic stream he listens to) as five-minute AAC chunks. Each finished
 * chunk is transcribed with speaker labels and merged into the session's meeting notes.
 * `append` runs on the mic thread; everything else is guarded by a lock.
 */
class ChunkRecorder {
    companion object {
        private const val CHUNK_SECONDS = 300
        private const val RATE = Mic.RATE
    }

    private val lock = Any()
    private var session: SessionStore.Session? = null
    private var index = 0

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxing = false
    private var file: File? = null
    private var chunkStart = 0L
    private var samples = 0L
    private val info = MediaCodec.BufferInfo()

    fun begin(session: SessionStore.Session) {
        synchronized(lock) {
            closeChunk()
            this.session = session
            index = 0
        }
    }

    fun finish() {
        synchronized(lock) {
            closeChunk()
            session = null
        }
    }

    fun append(pcm: ByteArray) {
        synchronized(lock) {
            if (session == null) return
            try {
                if (codec == null) openChunk()
                feed(pcm)
                samples += pcm.size / 2
                if (samples >= RATE.toLong() * CHUNK_SECONDS) closeChunk()
            } catch (e: Exception) {
                Log.w("Bluey", "recording failed: $e")
                abandonChunk()
            }
        }
    }

    private fun openChunk() {
        val session = session ?: return
        val dir = File(session.folder, "audio").apply { mkdirs() }
        index += 1
        val out = File(dir, String.format(Locale.US, "chunk-%03d.m4a", index))
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = -1
        muxing = false
        file = out
        chunkStart = System.currentTimeMillis()
        samples = 0
    }

    private fun feed(pcm: ByteArray) {
        val encoder = codec ?: return
        var offset = 0
        var fed = samples
        while (offset < pcm.size) {
            val inIndex = encoder.dequeueInputBuffer(10_000)
            if (inIndex < 0) {
                drain(false)
                continue
            }
            val buffer = encoder.getInputBuffer(inIndex) ?: continue
            buffer.clear()
            val n = minOf(buffer.remaining(), pcm.size - offset)
            buffer.put(pcm, offset, n)
            val ptsUs = fed * 1_000_000L / RATE
            encoder.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
            offset += n
            fed += n / 2
        }
        drain(false)
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val mux = muxer ?: return
        var idle = 0
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(encoder.outputFormat)
                    mux.start()
                    muxing = true
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 50) return
                }

                outIndex >= 0 -> {
                    val data = encoder.getOutputBuffer(outIndex)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (data != null && info.size > 0 && muxing) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        mux.writeSampleData(track, data, info)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun closeChunk() {
        val encoder = codec ?: return
        val out = file
        val started = chunkStart
        val chunkIndex = index
        val hadAudio = samples > RATE  // more than a second
        try {
            val inIndex = encoder.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                encoder.queueInputBuffer(inIndex, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (e: Exception) {
            Log.w("Bluey", "closing chunk: $e")
        }
        val wroteFile = muxing
        release()
        val session = session
        if (out != null && session != null && wroteFile && hadAudio) {
            val ended = System.currentTimeMillis()
            Bluey.main.post { transcribe(session, out, chunkIndex, started, ended) }
        } else {
            out?.delete()
        }
    }

    private fun abandonChunk() {
        release()
        file?.delete()
    }

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        if (muxing) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        codec = null
        muxer = null
        muxing = false
        track = -1
    }

    /** Main thread: send the chunk off for a proper transcript with speakers. */
    private fun transcribe(session: SessionStore.Session, audio: File, chunkIndex: Int, started: Long, ended: Long) {
        session.transcribing += 1
        Bluey.notes.save(session)
        Bluey.scope.launch {
            val segments = try {
                OpenAI.transcribe(audio)
            } catch (e: Exception) {
                Log.w("Bluey", "transcription of chunk $chunkIndex failed: $e")
                null
            }
            session.transcribing = maxOf(0, session.transcribing - 1)
            if (segments != null) Bluey.notes.transcribed(session, started, ended, segments)
            else Bluey.notes.save(session)
        }
    }
}
