package com.ikeilo.skyr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class SkyAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        activeService = this
        refreshKeyFilterState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return if (VolumeShortcutManager.onKeyEvent(this, event)) {
            true
        } else {
            super.onKeyEvent(event)
        }
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    fun refreshKeyFilterState() {
        val settings = VolumeShortcutSettings(this)
        val updated = serviceInfo ?: AccessibilityServiceInfo()
        updated.flags = if (settings.isVolumeSuppressionEnabled()) {
            updated.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            updated.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        serviceInfo = updated
    }

    fun tap(points: List<PointF>, onFinished: (() -> Unit)? = null): Boolean {
        if (points.isEmpty()) return false
        val builder = GestureDescription.Builder()
        points.forEach { point ->
            val path = Path().apply {
                moveTo(point.x, point.y)
                lineTo(point.x + 0.1f, point.y + 0.1f)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
        }
        val callback = if (onFinished == null) {
            null
        } else {
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished()
                }
            }
        }
        val dispatched = dispatchGesture(builder.build(), callback, null)
        if (!dispatched) {
            onFinished?.invoke()
        }
        return dispatched
    }

    companion object {
        @Volatile
        var activeService: SkyAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = activeService != null
    }
}
