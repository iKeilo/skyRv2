package com.ikeilo.skyr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var overlayController: OverlayController
    private lateinit var favoriteButton: Button
    private lateinit var suppressionButton: Button
    private lateinit var uiModeButton: Button
    private val shortcutSpinners = linkedMapOf<VolumeShortcutTrigger, Spinner>()
    private val shortcutActions = VolumeShortcutAction.entries.filter { it != VolumeShortcutAction.START_OR_RESTART }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayController = OverlayController(this, ::updateStatus, ::pickSong)
        setContentView(createContent())
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    @Deprecated("Used for platform document picker callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_SONG && resultCode == RESULT_OK) {
            data?.data?.let(::loadSong)
        }
    }

    private fun createContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 40, 32, 32)
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        favoriteButton = button("收藏当前乐谱") {
            overlayController.toggleCurrentSongFavoriteFromApp()
            updateStatus()
        }
        suppressionButton = button("音量抑制") {
            overlayController.setVolumeSuppressionEnabled(!overlayController.isVolumeSuppressionEnabled())
            updateStatus()
            Toast.makeText(
                this,
                if (overlayController.isVolumeSuppressionEnabled()) "已开启音量控制抑制" else "已关闭音量控制抑制",
                Toast.LENGTH_SHORT
            ).show()
        }
        uiModeButton = button("UI模式") {
            val mode = overlayController.cycleUiHideMode()
            updateStatus()
            Toast.makeText(this, "当前UI模式: ${mode.label}", Toast.LENGTH_SHORT).show()
        }

        content.addView(status)
        content.addView(button("选择乐谱") { pickSong() })
        content.addView(favoriteButton)
        content.addView(button("开启悬浮窗权限") { openOverlaySettings() })
        content.addView(button("开启无障碍服务") { openAccessibilitySettings() })
        content.addView(button("显示悬浮控制") { overlayController.showControls() })
        content.addView(suppressionButton)
        content.addView(uiModeButton)
        content.addView(sectionTitle("音量快捷键"))
        content.addView(sectionHint("开启音量控制抑制后生效。选择下拉项后，点击保存按钮才会写入当前配置。"))

        VolumeShortcutTrigger.entries.forEach { trigger ->
            content.addView(settingLabel(trigger.label))
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    shortcutActions.map { it.label }
                ).also { adapter ->
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            }
            shortcutSpinners[trigger] = spinner
            content.addView(spinner, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        content.addView(button("保存快捷键配置") {
            shortcutSpinners.forEach { (trigger, spinner) ->
                val action = shortcutActions[spinner.selectedItemPosition.coerceIn(shortcutActions.indices)]
                overlayController.setShortcutAction(trigger, action)
            }
            updateStatus()
            Toast.makeText(this, "快捷键配置已保存", Toast.LENGTH_SHORT).show()
        })

        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun button(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            setPadding(0, 28, 0, 8)
        }
    }

    private fun sectionHint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 0, 0, 10)
        }
    }

    private fun settingLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 10, 0, 4)
        }
    }

    private fun pickSong() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_SONG)
    }

    private fun loadSong(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers do not support persistable permissions.
        }
        overlayController.importSong(uri)
        updateStatus()
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun updateStatus() {
        val song = PlaybackController.song?.name ?: "未选择"
        val overlay = if (Settings.canDrawOverlays(this)) "已授权" else "未授权"
        val accessibility = if (SkyAccessibilityService.isRunning) "已启用" else "未启用"
        val positioned = if (PlaybackController.keyPoints.size == 15) "已定位" else "未定位"
        val favorite = if (overlayController.isCurrentSongFavorite()) "已收藏" else "未收藏"
        val suppression = if (overlayController.isVolumeSuppressionEnabled()) "开启" else "关闭"
        val uiMode = overlayController.uiHideMode().label
        status.text = buildString {
            append("乐谱: ").append(song)
            append("\n悬浮窗: ").append(overlay)
            append("\n无障碍: ").append(accessibility)
            append("\n琴键定位: ").append(positioned)
            append("\n当前收藏: ").append(favorite)
            append("\n音量抑制: ").append(suppression)
            append("\nUI模式: ").append(uiMode)
        }
        favoriteButton.text = if (overlayController.isCurrentSongFavorite()) "取消收藏当前乐谱" else "收藏当前乐谱"
        suppressionButton.text = "音量抑制: $suppression"
        uiModeButton.text = "UI模式: $uiMode"
        shortcutSpinners.forEach { (trigger, spinner) ->
            val action = overlayController.shortcutAction(trigger)
            spinner.setSelection(shortcutActions.indexOf(action).coerceAtLeast(0), false)
        }
    }

    companion object {
        private const val REQUEST_PICK_SONG = 1001
    }
}
