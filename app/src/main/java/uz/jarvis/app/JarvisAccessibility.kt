package uz.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibility? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun screenSummary(): String {
        val root = rootInActiveWindow ?: return "(ekranni o'qib bo'lmadi)"
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= 70) return
            if (node.isVisibleToUser) {
                val t = node.text?.toString() ?: node.contentDescription?.toString()
                val tags = buildString {
                    if (node.isClickable) append("[bosiladi]")
                    if (node.isEditable) append("[kiritish]")
                }
                if (!t.isNullOrBlank()) {
                    sb.append("- ").append(t.take(60)).append(' ').append(tags).append('\n'); count++
                } else if (node.isEditable) {
                    sb.append("- (bo'sh maydon) ").append(tags).append('\n'); count++
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return "Ilova: ${root.packageName}\n$sb"
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: find(root) { it.isEditable } ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(down: Boolean): Boolean {
        val node = find(rootInActiveWindow) { it.isScrollable } ?: return false
        return node.performAction(
            if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun find(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val r = find(n.getChild(i), pred)
            if (r != null) return r
        }
        return null
    }
}
