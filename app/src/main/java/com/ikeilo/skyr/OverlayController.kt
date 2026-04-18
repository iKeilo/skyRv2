package com.ikeilo.skyr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n")
class OverlayController(
    private val context: Context,
    private val onSongChanged: () -> Unit,
    private val onPickExternalSong: () -> Unit
) : PlaybackController.Listener {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val positionStore = PositionStore(context)
    private var controls: View? = null
    private var positionView: View? = null
    private var songPickerView: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var positionParams: WindowManager.LayoutParams? = null
    private var songPickerParams: WindowManager.LayoutParams? = null
    private var pauseButton: Button? = null
    private var positionButton: Button? = null
    private var songLabel: TextView? = null
    private val bundledSongs: List<String> by lazy { loadBundledSongs() }
    private val speeds = listOf(0.4, 0.6, 0.8, 1.0, 1.5, 2.0)
    private var speedIndex = 3

    init {
        PlaybackController.listener = this
        positionStore.load()?.let { PlaybackController.keyPoints = it.points }
    }

    fun showControls() {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (controls != null) return

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(110, 10, 14, 15), dp(6).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        songLabel = TextView(context).apply {
            text = "乐谱: ${PlaybackController.song?.name ?: "未选择"}"
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 1
            setPadding(dp(3), 0, dp(3), dp(3))
        }
        val pick = button("选曲") { showSongPicker() }
        val play = button("开始") { PlaybackController.start() }
        pauseButton = button("暂停") { PlaybackController.pauseOrResume() }.apply {
            visibility = View.GONE
        }
        val end = button("结束") { PlaybackController.stopCurrent() }
        val speed = button("1x") {
            speedIndex = (speedIndex + 1) % speeds.size
            PlaybackController.speed = speeds[speedIndex]
            (it as Button).text = "${PlaybackController.speed}x"
        }
        positionButton = button("定位") {
            if (positionView == null) showPositionOverlay() else finishPosition()
        }
        val exit = button("退出") {
            PlaybackController.stopCurrent()
            removePositionOverlay()
            removeSongPicker()
            removeControls()
        }
        root.addView(songLabel)
        listOf(pick, play, pauseButton, end, speed, positionButton, exit).forEach { root.addView(it) }
        makeDraggable(root) { controlsParams }

        val params = baseParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 20
            y = 80
        }
        controlsParams = params
        windowManager.addView(root, params)
        controls = root
    }

    private fun showSongPicker() {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (songPickerView != null) return

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.86f).roundToInt()
        val height = (metrics.heightPixels * 0.66f).roundToInt()
        val x = ((metrics.widthPixels - width) / 2f).roundToInt()
        val y = (metrics.heightPixels * 0.12f).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(226, 18, 27, 28), dp(10).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(8).toFloat()
        }
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "选择乐谱"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        val importButton = button("导入") {
            removeSongPicker()
            onPickExternalSong()
        }
        val closeButton = button("×") { removeSongPicker() }.apply {
            minWidth = dp(44)
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        titleBar.addView(importButton)
        titleBar.addView(closeButton)

        val search = EditText(context).apply {
            hint = "搜索乐谱"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(170, 255, 255, 255))
            background = rounded(Color.argb(88, 255, 255, 255), dp(8).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
        }
        val countLabel = TextView(context).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 12f
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(list)
        }

        fun render(keyword: String) {
            val normalized = keyword.trim().lowercase(Locale.getDefault())
            val songs = if (normalized.isEmpty()) {
                bundledSongs
            } else {
                bundledSongs.filter {
                    it.removeSuffix(".txt").lowercase(Locale.getDefault()).contains(normalized)
                }
            }
            countLabel.text = "共 ${songs.size} 首"
            list.removeAllViews()
            if (songs.isEmpty()) {
                list.addView(songRow("没有找到匹配乐谱", enabled = false))
                return
            }
            songs.forEachIndexed { index, assetName ->
                list.addView(songRow(assetName.removeSuffix(".txt"), enabled = true, index = index) {
                    selectBundledSong(assetName)
                })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        root.addView(titleBar)
        root.addView(search, LinearLayout.LayoutParams(-1, dp(42)).apply {
            setMargins(0, dp(4), 0, 0)
        })
        root.addView(countLabel)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        makeDraggable(titleBar) { songPickerParams }
        render("")

        val params = baseParams(focusable = true).apply {
            this.width = width
            this.height = height
            this.x = x
            this.y = y
        }
        songPickerParams = params
        windowManager.addView(root, params)
        songPickerView = root
    }

    private fun showPositionOverlay() {
        if (positionView != null) return
        val metrics = context.resources.displayMetrics
        val landscapeWidth = maxOf(metrics.widthPixels, metrics.heightPixels)
        val landscapeHeight = minOf(metrics.widthPixels, metrics.heightPixels)
        val width = (landscapeWidth * 0.92f).roundToInt()
        val height = (landscapeHeight * 0.92f).roundToInt()
        val x = ((landscapeWidth - width) / 2f).roundToInt()
        val y = ((landscapeHeight - height) / 2f).roundToInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(70, 255, 204, 0))
        }
        val grid = GridLayout(context).apply {
            rowCount = 3
            columnCount = 5
        }
        repeat(15) {
            val cell = TextView(context).apply {
                setBackgroundColor(Color.argb(40, 255, 255, 255))
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL)
                setMargins(4, 4, 4, 4)
            })
        }
        val label = TextView(context).apply {
            text = "覆盖全部琴键区域"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
        }
        val handle = TextView(context).apply {
            text = "resize"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1).apply { setMargins(10, 10, 10, 10) })
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        root.addView(handle, FrameLayout.LayoutParams(dp(84), dp(44), Gravity.BOTTOM or Gravity.END))
        makePositionAdjustable(root)

        val params = baseParams().apply {
            this.width = width
            this.height = height
            this.x = x
            this.y = y
        }
        positionParams = params
        windowManager.addView(root, params)
        positionView = root
        positionButton?.text = "定位好了"
    }

    fun onSongSelected(song: Song) {
        songLabel?.text = "乐谱: ${song.name}"
    }

    private fun selectBundledSong(assetName: String) {
        try {
            val bytes = context.assets.open("music/$assetName").use { it.readBytes() }
            val song = MusicParser.parse(assetName, MusicParser.decode(bytes))
            PlaybackController.song = song
            onSongSelected(song)
            onSongChanged()
            removeSongPicker()
            Toast.makeText(context, "已读取乐谱: ${song.name}", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(context, error.message ?: "读取乐谱失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishPosition() {
        val params = positionParams ?: return
        val cellW = params.width / 5f
        val cellH = params.height / 3f
        val points = mutableListOf<PointF>()
        for (row in 0 until 3) {
            for (col in 0 until 5) {
                points += PointF(params.x + cellW * (col + 0.5f), params.y + cellH * (row + 0.5f))
            }
        }
        val config = PositionConfig(points)
        PlaybackController.keyPoints = points
        positionStore.save(config)
        Toast.makeText(context, "定位已保存", Toast.LENGTH_SHORT).show()
        removePositionOverlay()
    }

    private fun removePositionOverlay() {
        positionView?.let { windowManager.removeView(it) }
        positionView = null
        positionParams = null
        positionButton?.text = "定位"
    }

    private fun removeSongPicker() {
        songPickerView?.let { windowManager.removeView(it) }
        songPickerView = null
        songPickerParams = null
    }

    private fun removeControls() {
        controls?.let { windowManager.removeView(it) }
        controls = null
        controlsParams = null
    }

    override fun onStateChanged(state: String) {
        Toast.makeText(context, state, Toast.LENGTH_SHORT).show()
    }

    override fun onPlaybackStarted() {
        pauseButton?.visibility = View.VISIBLE
        pauseButton?.text = "暂停"
    }

    override fun onPlaybackPaused() {
        pauseButton?.visibility = View.VISIBLE
        pauseButton?.text = "继续"
    }

    override fun onPlaybackResumed() {
        pauseButton?.visibility = View.VISIBLE
        pauseButton?.text = "暂停"
    }

    override fun onPlaybackFinished() {
        pauseButton?.visibility = View.GONE
        pauseButton?.text = "暂停"
    }

    private fun button(text: String, action: (View) -> Unit): Button {
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            includeFontPadding = false
            background = rounded(Color.argb(118, 0, 150, 136), dp(6).toFloat())
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            setOnClickListener(action)
        }
    }

    private fun songRow(
        text: String,
        enabled: Boolean,
        index: Int = 0,
        action: (() -> Unit)? = null
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (enabled) Color.WHITE else Color.argb(170, 255, 255, 255))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(
                if (index % 2 == 0) Color.argb(54, 255, 255, 255) else Color.argb(34, 255, 255, 255),
                dp(7).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply {
                setMargins(0, dp(3), 0, dp(3))
            }
            if (enabled && action != null) {
                setOnClickListener { action() }
            }
        }
    }

    private fun baseParams(focusable: Boolean = false): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun loadBundledSongs(): List<String> {
        return context.assets.list("music")
            ?.filter { it.endsWith(".txt", ignoreCase = true) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            .orEmpty()
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun makeDraggable(view: View, paramsProvider: () -> WindowManager.LayoutParams?) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            val params = paramsProvider() ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun makePositionAdjustable(view: View) {
        var startX = 0
        var startY = 0
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        var resizing = false
        view.setOnTouchListener { _, event ->
            val params = positionParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startW = params.width
                    startH = params.height
                    touchX = event.rawX
                    touchY = event.rawY
                    resizing = event.x > view.width - dp(96) && event.y > view.height - dp(64)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (resizing) {
                        params.width = (startW + dx).coerceAtLeast(dp(220))
                        params.height = (startH + dy).coerceAtLeast(dp(140))
                    } else {
                        params.x = startX + dx
                        params.y = startY + dy
                    }
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
