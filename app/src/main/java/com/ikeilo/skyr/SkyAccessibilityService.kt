package com.ikeilo.skyr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent

class SkyAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    fun tap(points: List<PointF>): Boolean {
        if (points.isEmpty()) return false
        val builder = GestureDescription.Builder()
        points.forEach { point ->
            val path = Path().apply {
                moveTo(point.x, point.y)
                lineTo(point.x + 0.1f, point.y + 0.1f)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
        }
        return dispatchGesture(builder.build(), null, null)
    }

    companion object {
        @Volatile
        var activeService: SkyAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = activeService != null
    }
}
