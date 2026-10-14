package app.bluey

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import java.io.File

/** The app screen: the big Bluey, setup steps, settings and meeting notes. */
class MainActivity : Activity() {
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var face: FaceView
    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var askRow: LinearLayout
    private lateinit var askField: EditText
    private lateinit var setup: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var notesBox: LinearLayout
    private lateinit var cost: TextView
    private lateinit var meetingButton: Button
    private lateinit var autoSleepButton: Button
    private lateinit var languageButton: Button

    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bluey.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        face = FaceView(this)
        root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(32))
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        reply = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Palette.PANEL, Palette.alpha(Palette.BERRY2, 0.6f))
            visibility = View.GONE
        }
        column.addView(reply, matchWrap(bottom = 10))

        status = TextView(this).apply {
            setTextColor(Palette.INK_SOFT)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(status, matchWrap(bottom = 4))

        cost = TextView(this).apply {
            setTextColor(0xFF8E88A3.toInt())
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setOnLongClickListener {
                dialog().setTitle("Reset the cost meter?")
                    .setMessage("This only clears the estimate on this phone. Your real bill is on platform.openai.com.")
                    .setPositiveButton("Reset") { _, _ -> Bluey.usage.reset() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
        column.addView(cost, matchWrap(bottom = 10))

        meetingButton = button("Meeting mode: off") {
            Bluey.voice.setMeetingMode(!Bluey.voice.meetingMode)
        }
        column.addView(meetingButton, matchWrap(bottom = 8))

        askField = EditText(this).apply {
            hint = "Type a question instead…"
            setHintTextColor(0xFF6E6880.toInt())
            setTextColor(Color.WHITE)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    sendTyped()
                    true
                } else false
            }
        }
        askRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(askField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Ask") { sendTyped() })
        }
        column.addView(askRow, matchWrap(bottom = 14))

        setup = section(column, "Setup")
        column.addView(howTo(), matchWrap(bottom = 16))
        settingsBox = section(column, "Settings")
        notesBox = section(column, "Meeting notes")

        setContentView(root)
        buildSettings()
        render()
    }

    override fun onResume() {
        super.onResume()
        Bluey.listen(refresh)
        BlueyAccessibility.instance?.overlay?.appInFront = true
        if (micGranted()) VoiceService.ensureRunning(this)
        render()
    }

    override fun onPause() {
        Bluey.unlisten(refresh)
        BlueyAccessibility.instance?.overlay?.appInFront = false
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (micGranted()) VoiceService.ensureRunning(this)
        render()
    }

    private fun sendTyped() {
        val text = askField.text.toString().trim()
        if (text.isEmpty()) return
        if (!Bluey.voice.isAwake) {
            Toast.makeText(this, "Wake him first (double-tap him).", Toast.LENGTH_SHORT).show()
            return
        }
        askField.setText("")
        Bluey.voice.askTyped(text)
    }

    // MARK: Rendering

    private fun render() {
        val voice = Bluey.voice
        status.text = when (voice.state) {
            LiveVoice.State.ASLEEP -> "Asleep · double-tap him to wake him"
            LiveVoice.State.WAKING -> "Waking up…"
            LiveVoice.State.LISTENING -> "Listening · press and hold him to ask"
            LiveVoice.State.ASKING -> "Go on, he's listening… let go when you're done"
            LiveVoice.State.THINKING -> "Thinking…"
            LiveVoice.State.SPEAKING -> "Replying"
        }
        val caption = voice.caption.trim()
        reply.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
        reply.text = caption
        askRow.visibility = if (voice.isAwake) View.VISIBLE else View.GONE
        meetingButton.visibility = if (voice.isAwake) View.VISIBLE else View.GONE
        meetingButton.text = if (voice.meetingMode) "Meeting mode: on (stays awake until you stop it)" else "Meeting mode: off"
        cost.text = String.format(
            java.util.Locale.US, "Estimated cost · today $%.2f · this month $%.2f", Bluey.usage.today(), Bluey.usage.thisMonth()
        )
        autoSleepButton.text = "Auto-sleep: " + autoSleepLabel(Bluey.settings.autoSleepMinutes)
        languageButton.text = "Reply language: " + languageLabel(Bluey.settings.language)
        // The setup steps and notes list change rarely; rebuild them a moment after things settle.
        status.removeCallbacks(renderSlow)
        status.postDelayed(renderSlow, 500)
    }

    private val renderSlow = Runnable {
        renderSetup()
        renderNotes()
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(): Boolean {
        if (BlueyAccessibility.instance != null) return true
        val manager = getSystemService(AccessibilityManager::class.java)!!
        val mine = ComponentName(this, BlueyAccessibility::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.let { s -> ComponentName(s.packageName, s.name) } == mine }
    }

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)

    private fun renderSetup() {
        setup.removeAllViews()
        val hasKey = Bluey.settings.openAIKey != null
        setup.addView(
            step(
                "1. OpenAI API key", if (hasKey) "Saved on this phone, encrypted." else "He thinks with OpenAI. Paste a key from platform.openai.com.",
                hasKey, if (hasKey) "Change" else "Add key"
            ) { askForKey() }
        )
        setup.addView(
            step("2. Microphone", if (micGranted()) "Allowed." else "So he can hear you.", micGranted(), "Allow") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        )
        val floating = accessibilityOn()
        setup.addView(
            step(
                "3. Floating Bluey",
                if (floating) "On. He floats over your apps and can see the screen when you ask."
                else "Settings → Accessibility → Installed apps → Bluey → On. If it's greyed out, tap \"App info\", " +
                    "then ⋮ (top right) → Allow restricted settings, and try again.",
                floating, "Open settings"
            ) { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
        if (!floating) {
            setup.addView(button("App info") {
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchWrap(bottom = 8))
        }
        val battery = batteryUnrestricted()
        setup.addView(
            step(
                "4. Keep him running", if (battery) "Unrestricted, so Samsung won't put him to sleep."
                else "Lets him keep listening in the background and survive long sessions.",
                battery, "Allow"
            ) {
                runCatching {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                }
            }
        )
    }

    private fun howTo(): View = TextView(this).apply {
        setTextColor(0xFFCFC9DE.toInt())
        textSize = 14f
        setLineSpacing(0f, 1.2f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(0xFF141220.toInt(), 0)
        text = "How to use him\n" +
            "• Double-tap Bluey (here or the little one floating over your apps) to wake him. He listens to everything as context but stays quiet.\n" +
            "• Press and hold him while you ask. Let go and he answers in a speech bubble.\n" +
            "• Drag the floating Bluey onto something and ask \"what's this?\"\n" +
            "• Ask him to do things: \"open WhatsApp\", \"search YouTube for…\", \"scroll down\".\n" +
            "• Tap him once while he's busy to stop him. Double-tap again to send him to sleep.\n" +
            "• While he's awake, the session is recorded and turned into meeting notes below."
    }

    private fun buildSettings() {
        val s = Bluey.settings
        settingsBox.addView(toggle("Let him use the phone", "Tap, type, scroll and open apps when you ask. He confirms before anything hard to undo.", s.phoneControl) {
            s.phoneControl = it
        })
        settingsBox.addView(toggle("Cheaper brain (mini)", "About a third of the price. Fine for questions and notes; a bit weaker at multi-step phone tasks.", s.cheapBrain) {
            s.cheapBrain = it
        })
        settingsBox.addView(toggle("Record meeting notes", "Saves the session's audio in five-minute chunks and transcribes it with speaker labels.", s.meetingNotes) {
            s.meetingNotes = it
        })
        autoSleepButton = button("Auto-sleep") { pickAutoSleep() }
        settingsBox.addView(autoSleepButton, matchWrap(bottom = 4))
        settingsBox.addView(note("Sends him to sleep after this long without a question, so a forgotten Bluey doesn't keep billing. Meeting mode skips it."))
        languageButton = button("Reply language") { pickLanguage() }
        settingsBox.addView(languageButton, matchWrap(bottom = 8))
        settingsBox.addView(slider("Chirp volume", (s.volume * 100).toInt()) {
            s.volume = it / 100f
        })
        settingsBox.addView(button("Test chirp") { Bluey.voice.testChirp() }, matchWrap(bottom = 8))
        settingsBox.addView(slider("Floating Bluey size", ((s.cursorScale - 0.7f) / 0.9f * 100).toInt()) {
            s.cursorScale = 0.7f + it / 100f * 0.9f
            BlueyAccessibility.instance?.overlay?.screenChanged()
        })
        settingsBox.addView(button("Personality…") { editPersonality() }, matchWrap(bottom = 4))
        settingsBox.addView(note("Changes to the brain, language, personality and phone control apply the next time you wake him."))
    }

    private fun renderNotes() {
        notesBox.removeAllViews()
        val saved = Bluey.notes.saved()
        if (saved.isEmpty()) {
            notesBox.addView(note("No sessions yet. Wake him and talk; notes appear here."))
            return
        }
        for (session in saved.take(15)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(0xFF141220.toInt(), 0)
            }
            row.addView(TextView(this).apply {
                text = session.title
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this).apply {
                text = session.summary
                setTextColor(Palette.INK_SOFT)
                textSize = 13f
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(button("Read") { openNotes(session.folder) })
            buttons.addView(button("Copy") { copyNotes(session.notes) })
            buttons.addView(button("Share") { shareNotes(session.notes) })
            row.addView(buttons)
            notesBox.addView(row, matchWrap(bottom = 8))
        }
        notesBox.addView(note("Files are in Android/data/app.bluey.fold/files/Bluey Notes."))
    }

    private fun copyNotes(file: File) {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("Bluey notes", text))
        Toast.makeText(this, "Notes copied. Paste them into any AI to summarize.", Toast.LENGTH_SHORT).show()
    }

    private fun shareNotes(file: File) {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bluey meeting notes")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share notes"))
    }

    // MARK: Dialogs

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private val autoSleepChoices = listOf(5, 10, 20, 30, 0)
    private fun autoSleepLabel(minutes: Int) = if (minutes <= 0) "never" else "after $minutes min"

    private fun pickAutoSleep() {
        val labels = autoSleepChoices.map { autoSleepLabel(it).replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        dialog().setTitle("Auto-sleep")
            .setSingleChoiceItems(labels, autoSleepChoices.indexOf(Bluey.settings.autoSleepMinutes)) { d, i ->
                Bluey.settings.autoSleepMinutes = autoSleepChoices[i]
                d.dismiss()
            }.show()
    }

    private val languageChoices = listOf("auto", "en", "es")
    private fun languageLabel(code: String) = when (code) {
        "en" -> "English"
        "es" -> "Español"
        else -> "Match mine (English or Spanish)"
    }

    private fun pickLanguage() {
        val labels = languageChoices.map { languageLabel(it) }.toTypedArray()
        dialog().setTitle("Reply language")
            .setSingleChoiceItems(labels, languageChoices.indexOf(Bluey.settings.language).coerceAtLeast(0)) { d, i ->
                Bluey.settings.language = languageChoices[i]
                d.dismiss()
            }.show()
    }

    /** Reads a session's notes in the app, with a button to name the speakers. */
    private fun openNotes(folder: File) {
        val session = Bluey.notes.load(folder) ?: return
        val body = TextView(this).apply {
            text = Bluey.notes.text(session)
            setTextColor(0xFFE6E2F2.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val builder = dialog().setTitle("Notes").setView(scroll)
            .setPositiveButton("Close", null)
            .setNegativeButton("Share") { _, _ -> shareText(Bluey.notes.text(session)) }
        if (Bluey.notes.speakerLetters(session).isNotEmpty()) {
            builder.setNeutralButton("Name speakers") { _, _ -> nameSpeakers(session) }
        }
        builder.show()
    }

    private fun nameSpeakers(session: SessionStore.Session) {
        val letters = Bluey.notes.speakerLetters(session)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val fields = letters.associateWith { letter ->
            EditText(this).apply {
                hint = "Speaker $letter"
                setText(session.speakers[letter] ?: "")
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }.also { box.addView(it) }
        }
        dialog().setTitle("Who's who?")
            .setMessage("Letters are assigned per five-minute stretch, so check a line or two before naming.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Bluey.notes.nameSpeakers(session, fields.mapValues { it.value.text.toString() })
                Bluey.main.postDelayed({ openNotes(session.folder) }, 1500)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bluey meeting notes")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share notes"))
    }

    private fun askForKey() {
        val field = EditText(this).apply {
            hint = "sk-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog()
            .setTitle("OpenAI API key")
            .setMessage("Create one at platform.openai.com → API keys. It stays on this phone, encrypted.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isNotEmpty()) Bluey.settings.openAIKey = key
            }
            .setNegativeButton("Cancel", null)
            .apply { if (Bluey.settings.openAIKey != null) setNeutralButton("Remove") { _, _ -> Bluey.settings.openAIKey = null } }
            .show()
    }

    private fun editPersonality() {
        val field = EditText(this).apply {
            setText(Bluey.settings.personality)
            minLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog()
            .setTitle("His personality")
            .setMessage("Who he is and how he talks. How he uses his tools is always added.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> Bluey.settings.personality = field.text.toString() }
            .setNeutralButton("Reset") { _, _ -> Bluey.settings.personality = "" }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // MARK: Little view helpers

    private fun rounded(color: Int, border: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 16 * density
        if (border != 0) setStroke((1.5f * density).toInt(), border)
    }

    private fun matchWrap(bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottom)
        }

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(Palette.BERRY1)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding(dp(4), dp(10), 0, dp(6))
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, matchWrap(bottom = 8))
        return box
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF8E88A3.toInt())
        textSize = 13f
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }

    private fun step(title: String, detail: String, done: Boolean, action: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(0xFF141220.toInt(), if (done) 0 else Palette.alpha(Palette.BERRY2, 0.5f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = (if (done) "✓ " else "") + title
            setTextColor(if (done) 0xFF9BE3B0.toInt() else Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(this).apply {
            text = detail
            setTextColor(Palette.INK_SOFT)
            textSize = 13f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!done || action == "Change") row.addView(button(action, onClick))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, matchWrap(bottom = 8))
        }
    }

    private fun toggle(title: String, detail: String, on: Boolean, changed: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(0xFF141220.toInt(), 0)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        texts.addView(TextView(this).apply {
            text = detail
            setTextColor(Palette.INK_SOFT)
            textSize = 13f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, value -> changed(value) }
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, matchWrap(bottom = 8))
        }
    }

    private fun slider(title: String, value: Int, changed: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFF141220.toInt(), 0)
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        box.addView(SeekBar(this).apply {
            max = 100
            progress = value.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) changed(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, matchWrap(bottom = 8))
        }
    }
}
