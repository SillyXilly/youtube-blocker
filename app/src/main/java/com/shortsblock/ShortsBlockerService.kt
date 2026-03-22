package com.shortsblock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ShortsBlockerService : AccessibilityService() {

    private val PREFS_NAME = "shorts_blocker_prefs"
    private val KEY_BLOCKING_ENABLED = "blocking_enabled"
    private val TAG = "ShortsBlocker"

    private val SHORTS_LABELS = setOf("shorts", "short", "reels", "reel", "#shorts")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isBlockingEnabled()) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != "com.google.android.youtube") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isShortsActive()) {
                    Log.d(TAG, "Shorts detected — navigating back")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    private fun isShortsActive(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            checkNodeForShorts(root)
        } finally {
            root.recycle()
        }
    }

    private fun checkNodeForShorts(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val labelMatches = SHORTS_LABELS.any { label -> text.contains(label) || desc.contains(label) }
        if (labelMatches && (node.isSelected || node.isChecked || node.isFocused)) return true
        if (viewId.contains("reel") || viewId.contains("short")) return true
        if (text.contains("#shorts") || desc.contains("#shorts")) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (checkNodeForShorts(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun isBlockingEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLOCKING_ENABLED, false)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        Log.d(TAG, "ShortsBlockerService connected")
    }
}
