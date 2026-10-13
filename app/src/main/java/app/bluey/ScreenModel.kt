package app.bluey

import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max

/** One thing on screen he can point at or tap: a control or a piece of text. Rects are in screen pixels. */
class Target(val id: String, val text: String, val rect: Rect)

class Control(val id: String, val kind: String, val label: String, val rect: Rect) {
    val asTarget: Target get() = Target(id, label.ifEmpty { kind }, rect)
}

class ScreenSnapshot(
    val width: Int,
    val height: Int,
    val apps: List<String>,
    val controls: List<Control>,
    val lines: List<Target>,
    /** The screenshot as base64 JPEG, or null if it couldn't be taken. */
    val jpeg: String?,
) {
    fun target(id: String): Target? {
        val key = id.trim().uppercase()
        controls.firstOrNull { it.id == key }?.let { return it.asTarget }
        return lines.firstOrNull { it.id == key }
    }

    /** What's under (or right next to) a point: the smallest control or text there. */
    fun target(nearX: Float, nearY: Float): Target? {
        val all = controls.map { it.asTarget } + lines
        val pad = 12
        val hit = all.filter {
            nearX >= it.rect.left - pad && nearX <= it.rect.right + pad &&
                nearY >= it.rect.top - pad && nearY <= it.rect.bottom + pad
        }.minByOrNull { it.rect.width().toLong() * it.rect.height() }
        if (hit != null) return hit
        fun distance(r: Rect): Float = hypot(
            max(max(r.left - nearX, 0f), nearX - r.right),
            max(max(r.top - nearY, 0f), nearY - r.bottom),
        )
        return all.filter { distance(it.rect) < 120 }.minByOrNull { distance(it.rect) }
    }

    fun grid(x: Float, y: Float): String {
        val gx = (x / width * 1000).toInt().coerceIn(0, 1000)
        val gy = (y / height * 1000).toInt().coerceIn(0, 1000)
        return "@$gx,$gy"
    }

    private fun grid(r: Rect): String = grid(r.exactCenterX(), r.exactCenterY())

    /** The list he picks from. Coordinates are on a 0–1000 grid so he can relate them to the screenshot. */
    val targetList: String
        get() {
            val out = mutableListOf<String>()
            out.add(if (apps.isEmpty()) "On screen: (unknown app)" else "On screen: " + apps.joinToString(", "))
            if (controls.isNotEmpty()) {
                out.add("Controls (tap these by id):")
                controls.forEach { c ->
                    out.add("${c.id} ${c.kind} ${grid(c.rect)}" + if (c.label.isEmpty()) "" else " \"${c.label}\"")
                }
            }
            out.add("Text on screen (L ids, @x,y on a 0-1000 grid):")
            if (lines.isEmpty()) out.add("(no text found)")
            lines.forEach { out.add("${it.id} ${grid(it.rect)} \"${it.text}\"") }
            return out.joinToString("\n")
        }
}
