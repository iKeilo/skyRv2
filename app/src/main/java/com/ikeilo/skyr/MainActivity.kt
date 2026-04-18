package com.ikeilo.skyr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var overlayController: OverlayController

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        root.addView(status)
        root.addView(button("选择乐谱") { pickSong() })
        root.addView(button("开启悬浮窗权限") { openOverlaySettings() })
        root.addView(button("开启无障碍服务") { openAccessibilitySettings() })
        root.addView(button("显示悬浮控制") { overlayController.showControls() })
        return root
    }

    private fun button(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }
    }

    private fun pickSong() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, REQUEST_PICK_SONG)
    }

    private fun loadSong(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("无法读取文件")
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "Untitled.txt"
            val song = MusicParser.parse(fileName, MusicParser.decode(bytes))
            PlaybackController.song = song
            overlayController.onSongSelected(song)
            Toast.makeText(this, "已读取乐谱: ${song.name}", Toast.LENGTH_SHORT).show()
            updateStatus()
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "读取乐谱失败", Toast.LENGTH_LONG).show()
        }
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
        val accessibility = if (SkyAccessibilityService.isRunning) "已启动" else "未启动"
        val positioned = if (PlaybackController.keyPoints.size == 15) "已定位" else "未定位"
        status.text = "乐谱: $song\n悬浮窗: $overlay\n无障碍: $accessibility\n琴键: $positioned"
    }

    companion object {
        private const val REQUEST_PICK_SONG = 1001
    }
}
