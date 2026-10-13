package app.bluey

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Bluey's eyes and hands on the phone. With this accessibility service on, he floats over every app
 * (the [Overlay]), reads what's on screen when you ask him something, and, only when you ask and
 * "Let him use the phone" is on, taps, types, scrolls and opens apps.
 */
class BlueyAccessibility : AccessibilityService() {
    companion object {
        var instance: BlueyAccessibility? = null
            private set
    }

    lateinit var overlay: Overlay
        private set

    /** Where the user last dropped him (his cursor tip), what they last tapped and what they selected. */
    var droppedAt: Pair<PointF, Long>? = null
        private set
    var lastTouched: Pair<Target, Long>? = null
        private set
    var selectedText: Pair<String, Long>? = null
        private set

    private var lastShot = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Bluey.init(applicationContext)
        overlay = Overlay(this)
        overlay.show()
        instance = this
        overlay.voiceState = Bluey.voice.state
        Bluey.changed()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (instance === this) {
            instance = null
            overlay.remove()
            Bluey.changed()
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding or unfolding changes the screen: keep him inside it.
        if (instance === this) overlay.screenChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() == packageName) return
        val now = SystemClock.uptimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val source = event.source ?: return
                val rect = Rect().also { source.getBoundsInScreen(it) }
                val text = listOfNotNull(
                    source.text?.toString(),
                    source.contentDescription?.toString(),
                    event.text.joinToString(" ").ifBlank { null },
                ).firstOrNull { it.isNotBlank() } ?: describe(source)
                if (!rect.isEmpty) lastTouched = Target("", text.trim(), rect) to now
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val full = event.text.joinToString("")
                val from = minOf(event.fromIndex, event.toIndex)
                val to = maxOf(event.fromIndex, event.toIndex)
                selectedText = if (from >= 0 && to > from && to <= full.length) full.substring(from, to) to now else null
            }
        }
    }

    /** The overlay tells us when the user drags him onto something. */
    fun dropped(tip: PointF) {
        droppedAt = PointF(tip.x, tip.y) to SystemClock.uptimeMillis()
    }

    fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size.x to size.y
    }

    // MARK: Looking

    suspend fun snapshot(): ScreenSnapshot {
        val (w, h) = screenSize()
        val read = withContext(Dispatchers.Default) { runCatching { readNodes(w, h) }.getOrNull() }
        val jpeg = screenshot()
        return ScreenSnapshot(w, h, read?.apps ?: emptyList(), read?.controls ?: emptyList(), read?.lines ?: emptyList(), jpeg)
    }

    private class NodeRead(val apps: List<String>, val controls: List<Control>, val lines: List<Target>)

    private class Found(val kind: String, val text: String, val rect: Rect)

    private fun readNodes(w: Int, h: Int): NodeRead {
        val screen = Rect(0, 0, w, h)
        val apps = mutableListOf<String>()
        val controls = mutableListOf<Found>()
        val lines = mutableListOf<Found>()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80 || visited > 4000) return
            visited++
            if (!node.isVisibleToUser) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val onScreen = !rect.isEmpty && Rect.intersects(rect, screen)
            if (onScreen) {
                rect.intersect(screen)
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable
                if (actionable && controls.size < 160) {
                    val own = when {
                        node.isPassword -> "(password field)"
                        text.isNotEmpty() && !(node.isEditable && node.isShowingHintText) -> text
                        desc.isNotEmpty() -> desc
                        node.hintText?.isNotBlank() == true -> "hint: ${node.hintText}"
                        else -> gatherText(node, 60)
                    }
                    var kind = kindOf(node)
                    if (node.isCheckable) kind += if (node.isChecked) " (on)" else " (off)"
                    if (node.isEditable && node.isFocused) kind += " (focused)"
                    controls.add(Found(kind, clip(own, 80), Rect(rect)))
                }
                val shown = if (text.isNotEmpty()) text else desc
                if (shown.isNotEmpty() && !actionable && !node.isPassword && lines.size < 320) {
                    lines.add(Found("text", clip(shown, 300), Rect(rect)))
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        val pm = packageManager
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            ) continue
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == packageName) continue
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && pkg.isNotEmpty()) {
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                if (label !in apps) apps.add(label)
            }
            walk(root, 0)
        }

        // Reading order: top to bottom in bands, then left to right.
        val band = max(1, h / 60)
        val order = compareBy<Found>({ it.rect.top / band }, { it.rect.left })
        val sortedControls = controls.sortedWith(order)
        val sortedLines = lines.sortedWith(order)
        return NodeRead(
            apps,
            sortedControls.mapIndexed { i, f -> Control("C${i + 1}", f.kind, f.text, f.rect) },
            sortedLines.mapIndexed { i, f -> Target("L${i + 1}", f.text, f.rect) },
        )
    }

    private fun clip(text: String, max: Int): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length > max) one.take(max - 1) + "…" else one
    }

    private fun gatherText(node: AccessibilityNodeInfo, limit: Int): String {
        val parts = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6 || parts.joinToString(" ").length > limit) return
            val t = n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
            if (t.isNotEmpty()) parts.add(t)
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, depth + 1) }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, 1) }
        return parts.joinToString(" ")
    }

    private fun describe(node: AccessibilityNodeInfo): String = gatherText(node, 60).ifEmpty { kindOf(node) }

    private fun kindOf(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        return when {
            node.isEditable || cls.contains("EditText") -> "text field"
            cls.contains("Switch") || cls.contains("Toggle") -> "switch"
            cls.contains("CheckBox") -> "checkbox"
            cls.contains("RadioButton") -> "radio button"
            cls.contains("SeekBar") || cls.contains("Slider") -> "slider"
            cls.contains("Tab") -> "tab"
            cls.contains("Image") -> "icon button"
            cls.contains("Button") -> "button"
            else -> "item"
        }
    }

    /** A JPEG of the screen (without Bluey on it), as base64. Android allows about one per second. */
    private suspend fun screenshot(): String? {
        val wait = 1100 - (SystemClock.uptimeMillis() - lastShot)
        if (wait > 0) delay(wait)
        overlay.hideForCapture(true)
        delay(80)
        val bitmap = try {
            capture()
        } finally {
            overlay.hideForCapture(false)
            lastShot = SystemClock.uptimeMillis()
        }
        bitmap ?: return null
        return withContext(Dispatchers.Default) {
            val scale = minOf(1f, 1100f / max(bitmap.width, bitmap.height))
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 72, out)
            if (small !== bitmap) small.recycle()
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private suspend fun capture(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    buffer.close()
                    if (cont.isActive) cont.resume(soft)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    // MARK: Hands

    private suspend fun gesture(description: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val sent = dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!sent && cont.isActive) cont.resume(false)
    }

    suspend fun tap(x: Float, y: Float, double: Boolean, long: Boolean): Boolean = overlay.passThrough {
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
        if (double) {
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            builder.addStroke(GestureDescription.StrokeDescription(path, 140, 40))
        } else {
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, if (long) 750 else 50))
        }
        gesture(builder.build())
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, holdFirst: Boolean): Boolean =
        overlay.passThrough {
            if (holdFirst) {
                val hold = Path().apply { moveTo(x1, y1); lineTo(x1 + 1, y1 + 1) }
                val first = GestureDescription.StrokeDescription(hold, 0, 650, true)
                if (!gesture(GestureDescription.Builder().addStroke(first).build())) return@passThrough false
                val move = Path().apply { moveTo(x1 + 1, y1 + 1); lineTo(x2, y2) }
                val second = first.continueStroke(move, 0, duration, false)
                gesture(GestureDescription.Builder().addStroke(second).build())
            } else {
                val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                gesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build())
            }
        }

    class Field(val node: AccessibilityNodeInfo, val rect: Rect, val password: Boolean)

    fun focusedField(): Field? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (node.packageName?.toString() == packageName) return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return Field(node, rect, node.isPassword)
    }

    /** Types at the cursor (or the end) of the field. Returns a problem, or null when it worked. */
    fun typeInto(field: Field, text: String): String? {
        val node = field.node
        node.refresh()
        if (node.isPassword) return "That's a password field. Ask the user to type it themselves."
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val valid = start in 0..existing.length && end in start..existing.length
        val updated = if (valid) existing.substring(0, start) + text + existing.substring(end) else existing + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "That field won't take typing from me. Ask the user to type it."
        }
        val caret = (if (valid) start else existing.length) + text.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        })
        return null
    }

    fun pressEnter(): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun pressButton(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun openApp(name: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val want = norm(name)
        if (want.isEmpty()) return "Which app?"
        val match = apps.firstOrNull { norm(it.first) == want }
            ?: apps.firstOrNull { norm(it.first).startsWith(want) }
            ?: apps.firstOrNull { norm(it.first).contains(want) }
            ?: apps.firstOrNull { norm(it.first).length >= 4 && want.contains(norm(it.first)) }
            ?: return "There's no app called \"$name\" on this phone."
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return "${match.first} can't be opened directly."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            "Opened ${match.first}."
        } catch (e: Exception) {
            "Couldn't open ${match.first}: ${e.message}"
        }
    }

    fun openUrl(raw: String): String {
        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) raw else "https://$raw"
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) return "I only open web links."
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened $url."
        } catch (e: ActivityNotFoundException) {
            "Nothing on this phone can open that link."
        }
    }
}
