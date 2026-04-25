package com.ikeilo.skyr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

object VolumeShortcutManager {
    interface Dispatcher {
        fun onVolumeShortcut(action: VolumeShortcutAction)
    }

    @Volatile
    var dispatcher: Dispatcher? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingKeyCode: Int? = null
    private var pendingRunnable: Runnable? = null

    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        val settings = VolumeShortcutSettings(context)
        if (!settings.isVolumeSuppressionEnabled()) {
            clearPending()
            return false
        }
        if (dispatcher == null) {
            clearPending()
            return false
        }
        if (event.repeatCount > 0) {
            return true
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                handleActionDown(settings, event.keyCode)
                true
            }
            KeyEvent.ACTION_UP -> true
            else -> true
        }
    }

    private fun handleActionDown(settings: VolumeShortcutSettings, keyCode: Int) {
        val existingKey = pendingKeyCode
        if (existingKey == keyCode) {
            pendingRunnable?.let(mainHandler::removeCallbacks)
            pendingKeyCode = null
            pendingRunnable = null
            dispatch(settings.actionFor(triggerFor(keyCode, isDouble = true)))
            return
        }
        flushPending()
        pendingKeyCode = keyCode
        pendingRunnable = Runnable {
            val currentKey = pendingKeyCode ?: return@Runnable
            pendingKeyCode = null
            pendingRunnable = null
            dispatch(settings.actionFor(triggerFor(currentKey, isDouble = false)))
        }.also {
            mainHandler.postDelayed(it, DOUBLE_TAP_TIMEOUT_MS)
        }
    }

    private fun dispatch(action: VolumeShortcutAction) {
        if (action == VolumeShortcutAction.NONE) return
        mainHandler.post { dispatcher?.onVolumeShortcut(action) }
    }

    private fun flushPending() {
        val runnable = pendingRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        runnable.run()
    }

    private fun clearPending() {
        pendingRunnable?.let(mainHandler::removeCallbacks)
        pendingRunnable = null
        pendingKeyCode = null
    }

    private fun triggerFor(keyCode: Int, isDouble: Boolean): VolumeShortcutTrigger {
        return when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP && isDouble -> VolumeShortcutTrigger.DOUBLE_UP
            keyCode == KeyEvent.KEYCODE_VOLUME_UP -> VolumeShortcutTrigger.SINGLE_UP
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isDouble -> VolumeShortcutTrigger.DOUBLE_DOWN
            else -> VolumeShortcutTrigger.SINGLE_DOWN
        }
    }

    private const val DOUBLE_TAP_TIMEOUT_MS = 260L
}
