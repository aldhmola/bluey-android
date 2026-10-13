package app.bluey

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BlueyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Bluey.init(this)
    }
}

/** The pieces of Bluey, shared by the app screen, the floating overlay and the mic service. */
object Bluey {
    lateinit var app: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var notes: SessionStore
        private set
    lateinit var voice: LiveVoice
        private set
    lateinit var brain: Brain
        private set

    val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (::settings.isInitialized) return
        app = context.applicationContext
        settings = Settings(app)
        notes = SessionStore(app)
        brain = Brain()
        voice = LiveVoice()
    }

    /** Something the screens show changed (state, caption, settings). */
    fun changed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { changed() }
            return
        }
        listeners.toList().forEach { it() }
    }

    fun listen(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unlisten(listener: () -> Unit) {
        listeners.remove(listener)
    }
}

/** Choices kept between launches. The OpenAI key is encrypted with a key held by Android's keystore. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bluey", Context.MODE_PRIVATE)

    /** Lets him tap, type, scroll and open things when you ask. */
    var phoneControl: Boolean
        get() = prefs.getBoolean("phoneControl", true)
        set(value) = save { putBoolean("phoneControl", value) }

    /** Records the session's audio in five-minute chunks for proper meeting notes. */
    var meetingNotes: Boolean
        get() = prefs.getBoolean("meetingNotes", true)
        set(value) = save { putBoolean("meetingNotes", value) }

    /** Chirp loudness, 0…1. */
    var volume: Float
        get() = prefs.getFloat("volume", 0.8f)
        set(value) = save { putFloat("volume", value) }

    /** Size of his floating cursor, 0.7…1.6. */
    var cursorScale: Float
        get() = prefs.getFloat("cursorScale", 1f)
        set(value) = save { putFloat("cursorScale", value) }

    /** Where he rests on screen, as fractions of the screen (his cursor tip). */
    var homeX: Float
        get() = prefs.getFloat("homeX", 0.86f)
        set(value) = save { putFloat("homeX", value) }
    var homeY: Float
        get() = prefs.getFloat("homeY", 0.58f)
        set(value) = save { putFloat("homeY", value) }

    var personality: String
        get() = prefs.getString("personality", null)?.trim()?.takeIf { it.isNotEmpty() } ?: Brain.DEFAULT_PERSONALITY
        set(value) = save { putString("personality", value.trim()) }

    val hasCustomPersonality: Boolean get() = !prefs.getString("personality", null).isNullOrBlank()

    private inline fun save(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        Bluey.changed()
    }

    // MARK: OpenAI key

    private var cachedKey: String? = null

    var openAIKey: String?
        get() {
            cachedKey?.let { return it }
            val stored = prefs.getString("openaiKey", null) ?: return null
            return try {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
                String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8).also { cachedKey = it }
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            val key = value?.trim().orEmpty()
            cachedKey = null
            if (key.isEmpty()) {
                save { remove("openaiKey") }
                return
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val sealed = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            save { putString("openaiKey", Base64.encodeToString(sealed, Base64.NO_WRAP)) }
            cachedKey = key
        }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "bluey-openai"
    }
}
