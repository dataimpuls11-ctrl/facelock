package com.example.facelock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches window changes. Two jobs:
 *  1. Launch the face lock instantly when a protected app comes to the foreground.
 *  2. Tamper guard: when Settings / package installer screens mention FaceLock
 *     (App info, Device admin, Accessibility, Uninstall dialog), require face verification first.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val settingsPkgs = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        if (pkg in settingsPkgs) {
            guardSettings()
            if (stateChange) LockPolicy.onForeground(this, pkg)
            return
        }
        if (stateChange) LockPolicy.onForeground(this, pkg)
    }

    private fun guardSettings() {
        if (!SecureStore.hasFace(this) || LockState.selfUnlocked()) return
        val root = rootInActiveWindow ?: return
        val label = getString(R.string.app_name)
        if (mentions(root, label, intArrayOf(0), 0)) LockState.showLock(this, packageName)
    }

    private fun mentions(node: AccessibilityNodeInfo?, needle: String, count: IntArray, depth: Int): Boolean {
        if (node == null || depth > 25 || count[0]++ > 500) return false
        if (node.text?.contains(needle, ignoreCase = true) == true ||
            node.contentDescription?.contains(needle, ignoreCase = true) == true) return true
        for (i in 0 until node.childCount) {
            if (mentions(node.getChild(i), needle, count, depth + 1)) return true
        }
        return false
    }

    override fun onInterrupt() {}
}
