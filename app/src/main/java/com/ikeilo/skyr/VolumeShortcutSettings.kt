package com.ikeilo.skyr

import android.content.Context

class VolumeShortcutSettings(context: Context) {
    private val prefs = context.getSharedPreferences("volume_shortcuts", Context.MODE_PRIVATE)

    fun isVolumeSuppressionEnabled(): Boolean {
        return prefs.getBoolean(KEY_SUPPRESSION_ENABLED, false)
    }

    fun setVolumeSuppressionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUPPRESSION_ENABLED, enabled).apply()
    }

    fun actionFor(trigger: VolumeShortcutTrigger): VolumeShortcutAction {
        val fallback = defaultAction(trigger)
        return VolumeShortcutAction.fromStorage(prefs.getString(trigger.storageKey, fallback.storageValue))
    }

    fun setAction(trigger: VolumeShortcutTrigger, action: VolumeShortcutAction) {
        prefs.edit().putString(trigger.storageKey, action.storageValue).apply()
    }

    fun cycleAction(trigger: VolumeShortcutTrigger): VolumeShortcutAction {
        val actions = VolumeShortcutAction.entries
        val current = actionFor(trigger)
        val next = actions[(actions.indexOf(current) + 1) % actions.size]
        setAction(trigger, next)
        return next
    }

    private fun defaultAction(trigger: VolumeShortcutTrigger): VolumeShortcutAction {
        return when (trigger) {
            VolumeShortcutTrigger.SINGLE_UP -> VolumeShortcutAction.TOGGLE_UI
            VolumeShortcutTrigger.SINGLE_DOWN -> VolumeShortcutAction.NONE
            VolumeShortcutTrigger.DOUBLE_UP -> VolumeShortcutAction.PREVIOUS_SONG
            VolumeShortcutTrigger.DOUBLE_DOWN -> VolumeShortcutAction.NEXT_SONG
        }
    }

    private companion object {
        const val KEY_SUPPRESSION_ENABLED = "suppression_enabled"
    }
}
