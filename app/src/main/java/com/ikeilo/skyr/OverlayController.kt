package com.ikeilo.skyr

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import kotlin.math.roundToLong

@SuppressLint("SetTextI18n")
class OverlayController(
    private val context: Context,
    private val onSongChanged: () -> Unit,
    private val onPickExternalSong: () -> Unit
) : PlaybackController.Listener {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val positionStore = PositionStore(context)
    private val uiPrefs = context.getSharedPreferences("overlay_ui", Context.MODE_PRIVATE)
    private var controls: View? = null
    private var positionView: View? = null
    private var practiceLegendView: View? = null
    private var authorView: View? = null
    private var songPickerView: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var positionParams: WindowManager.LayoutParams? = null
    private var practiceLegendParams: WindowManager.LayoutParams? = null
    private var authorParams: WindowManager.LayoutParams? = null
    private var songPickerParams: WindowManager.LayoutParams? = null
    private var pauseButton: Button? = null
    private var endButton: Button? = null
    private var practiceButton: Button? = null
    private var positionButton: Button? = null
    private var songLabel: TextView? = null
    private var positionLabel: TextView? = null
    private var lastScoreLabel: TextView? = null
    private var comboLabel: TextView? = null
    private var positionOverlayLocked = false
    private var practiceMode = false
    private var practiceCueVersion = 0L
    private var currentCue: ExpectedCue? = null
    private var practiceStats = PracticeStats()
    private var lastScoreText = ""
    private val pendingTouchKeys = linkedSetOf<Int>()
    private val pendingTouchPoints = mutableMapOf<Int, PointF>()
    private val practiceCells = mutableListOf<TextView>()
    private val practiceFadeAnimators = mutableMapOf<Int, ValueAnimator>()
    private val bundledSongs: List<String> by lazy { loadBundledSongs() }
    private val speeds = listOf(0.4, 0.6, 0.8, 1.0, 1.5, 2.0)
    private var speedIndex = 3
    private val uiScaleValues = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f)
    private val uiSizeLabels = listOf("小", "中", "大", "特大", "超大")
    private var uiSizeIndex = uiPrefs.getInt("uiSizeIndex", 1).coerceIn(uiScaleValues.indices)
    private val uiScale: Float
        get() = uiScaleValues[uiSizeIndex]

    init {
        PlaybackController.listener = this
        PlaybackController.practiceMode = practiceMode
        positionStore.load()?.let { PlaybackController.keyPoints = it.points }
    }

    fun showControls(x: Int = 20, y: Int = 80) {
        PlaybackController.listener = this
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (controls != null) {
            syncPlaybackControls()
            return
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(110, 10, 14, 15), scaledDp(6).toFloat())
            setPadding(scaledDp(4), scaledDp(4), scaledDp(4), scaledDp(4))
        }
        songLabel = TextView(context).apply {
            text = "乐谱: ${PlaybackController.song?.name ?: "未选择"}"
            setTextColor(Color.WHITE)
            textSize = scaledText(11f)
            maxLines = 1
            setPadding(scaledDp(3), 0, scaledDp(3), scaledDp(3))
        }
        val pick = button("选曲") { showSongPicker() }
        val play = button("开始") { PlaybackController.start() }
        practiceButton = button("跟练关") { togglePracticeMode() }
        pauseButton = button("暂停") { PlaybackController.pauseOrResume() }.apply {
            visibility = View.GONE
        }
        val end = button("结束") { PlaybackController.stopCurrent() }.apply {
            visibility = View.GONE
        }
        endButton = end
        val speed = button("1x") {
            speedIndex = (speedIndex + 1) % speeds.size
            PlaybackController.speed = speeds[speedIndex]
            (it as Button).text = "${PlaybackController.speed}x"
        }
        val uiSize = button("UI ${uiSizeLabels[uiSizeIndex]}") { cycleUiSize() }
        positionButton = button("定位") {
            if (practiceMode) {
                showPracticeOverlay()
            } else if (positionView == null) {
                showPositionOverlay()
            } else {
                finishPosition()
            }
        }
        val exit = button("退出") {
            PlaybackController.stopCurrent()
            removePositionOverlay()
            removeSongPicker()
            removeControls()
        }
        root.addView(songLabel)
        listOf(pick, play, practiceButton, pauseButton, end, speed, uiSize, positionButton, exit).forEach { root.addView(it) }
        makeDraggable(root) { controlsParams }

        val params = baseParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            this.x = x
            this.y = y
        }
        controlsParams = params
        windowManager.addView(root, params)
        controls = root
        showAuthorWatermark()
        syncPlaybackControls()
        syncModeControls()
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
            background = rounded(Color.argb(226, 18, 27, 28), scaledDp(10).toFloat())
            setPadding(scaledDp(10), scaledDp(10), scaledDp(10), scaledDp(10))
            elevation = scaledDp(8).toFloat()
        }
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "选择乐谱"
            textSize = scaledText(16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        val importButton = button("导入") {
            removeSongPicker()
            onPickExternalSong()
        }
        val closeButton = button("×") { removeSongPicker() }.apply {
            minWidth = scaledDp(44)
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, scaledDp(42), 1f))
        titleBar.addView(importButton)
        titleBar.addView(closeButton)

        val search = EditText(context).apply {
            hint = "搜索乐谱"
            setSingleLine(true)
            textSize = scaledText(15f)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(170, 255, 255, 255))
            background = rounded(Color.argb(88, 255, 255, 255), scaledDp(8).toFloat())
            setPadding(scaledDp(12), 0, scaledDp(12), 0)
        }
        val countLabel = TextView(context).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = scaledText(12f)
            setPadding(scaledDp(4), scaledDp(6), scaledDp(4), scaledDp(4))
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
        root.addView(search, LinearLayout.LayoutParams(-1, scaledDp(42)).apply {
            setMargins(0, scaledDp(4), 0, 0)
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

    private fun showPositionOverlay(locked: Boolean = false) {
        if (positionView != null) return
        val metrics = context.resources.displayMetrics
        val bounds = if (locked) practiceOverlayBounds(metrics.widthPixels, metrics.heightPixels) else defaultPositionBounds(
            metrics.widthPixels,
            metrics.heightPixels
        )
        positionOverlayLocked = locked
        practiceCells.clear()

        val root = FrameLayout(context).apply {
            setBackgroundColor(if (locked) Color.argb(56, 0, 0, 0) else Color.argb(70, 255, 204, 0))
        }
        val grid = GridLayout(context).apply {
            rowCount = 3
            columnCount = 5
        }
        repeat(15) {
            val cell = TextView(context).apply {
                setBackgroundColor(defaultPracticeCellColor())
            }
            practiceCells += cell
            grid.addView(cell, GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL)
                setMargins(4, 4, 4, 4)
            })
        }
        val label = TextView(context).apply {
            text = if (locked) "跟练模式" else "覆盖全部琴键区域"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = scaledText(18f)
        }
        positionLabel = label
        val scoreLabel = TextView(context).apply {
            text = lastScoreText
            setTextColor(Color.argb(230, 255, 255, 255))
            textSize = scaledText(12f)
            includeFontPadding = false
            background = rounded(Color.argb(82, 0, 0, 0), scaledDp(5).toFloat())
            setPadding(scaledDp(5), scaledDp(3), scaledDp(5), scaledDp(3))
            visibility = if (locked && lastScoreText.isNotBlank()) View.VISIBLE else View.GONE
        }
        lastScoreLabel = scoreLabel
        val currentComboLabel = TextView(context).apply {
            setTextColor(Color.argb(235, 255, 230, 128))
            textSize = scaledText(13f)
            includeFontPadding = false
            background = rounded(Color.argb(82, 0, 0, 0), scaledDp(5).toFloat())
            setPadding(scaledDp(5), scaledDp(3), scaledDp(5), scaledDp(3))
            visibility = View.GONE
        }
        comboLabel = currentComboLabel
        val handle = TextView(context).apply {
            text = "resize"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = scaledText(12f)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1).apply { setMargins(10, 10, 10, 10) })
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        if (locked) {
            root.addView(
                scoreLabel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins(scaledDp(10), scaledDp(10), 0, 0)
                }
            )
            root.addView(
                currentComboLabel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    setMargins(0, scaledDp(10), scaledDp(10), 0)
                }
            )
        }
        if (!locked) {
            root.addView(handle, FrameLayout.LayoutParams(scaledDp(84), scaledDp(44), Gravity.BOTTOM or Gravity.END))
            makePositionAdjustable(root)
        }

        val capturePracticeTouches = locked && SkyAccessibilityService.isRunning
        if (capturePracticeTouches) {
            root.setOnTouchListener { view, event -> handlePracticeTouch(view, event, bounds) }
        }

        val params = baseParams(touchable = !locked || capturePracticeTouches).apply {
            this.width = bounds.width
            this.height = bounds.height
            this.x = bounds.x
            this.y = bounds.y
        }
        positionParams = params
        windowManager.addView(root, params)
        positionView = root
        if (locked) {
            showPracticeLegend(bounds)
        }
        syncModeControls()
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
        removePracticeLegend()
        positionView = null
        positionParams = null
        positionOverlayLocked = false
        positionLabel = null
        lastScoreLabel = null
        comboLabel = null
        clearPracticeHighlights()
        practiceCells.clear()
        syncModeControls()
    }

    private fun removeSongPicker() {
        songPickerView?.let { windowManager.removeView(it) }
        songPickerView = null
        songPickerParams = null
    }

    private fun removeControls() {
        controls?.let { windowManager.removeView(it) }
        removeAuthorWatermark()
        controls = null
        controlsParams = null
    }

    private fun removePracticeLegend() {
        practiceLegendView?.let { windowManager.removeView(it) }
        practiceLegendView = null
        practiceLegendParams = null
    }

    private fun removeAuthorWatermark() {
        authorView?.let { windowManager.removeView(it) }
        authorView = null
        authorParams = null
    }

    private fun cycleUiSize() {
        val currentX = controlsParams?.x ?: 20
        val currentY = controlsParams?.y ?: 80
        val wasPickerOpen = songPickerView != null
        val wasPracticeOverlayOpen = practiceMode && positionView != null
        uiSizeIndex = (uiSizeIndex + 1) % uiScaleValues.size
        uiPrefs.edit().putInt("uiSizeIndex", uiSizeIndex).apply()

        if (wasPracticeOverlayOpen) {
            removePositionOverlay()
        }
        removeSongPicker()
        removeControls()
        showControls(currentX, currentY)
        if (wasPracticeOverlayOpen) {
            showPracticeOverlay()
        }
        if (wasPickerOpen) {
            showSongPicker()
        }
        Toast.makeText(context, "UI大小: ${uiSizeLabels[uiSizeIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun togglePracticeMode() {
        if (PlaybackController.isPlaying || PlaybackController.isPaused) {
            PlaybackController.stopCurrent()
        }
        practiceMode = !practiceMode
        PlaybackController.practiceMode = practiceMode
        clearPracticeHighlights()
        removePositionOverlay()
        if (practiceMode) {
            showPracticeOverlay()
            Toast.makeText(context, "已开启跟练模式", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已切换为演奏模式", Toast.LENGTH_SHORT).show()
        }
        syncModeControls()
        syncPlaybackControls()
    }

    private fun showPracticeOverlay() {
        if (positionView != null && positionOverlayLocked) return
        removePositionOverlay()
        showPositionOverlay(locked = true)
    }

    private fun syncPlaybackControls() {
        val pause = pauseButton ?: return
        val end = endButton ?: return
        when {
            PlaybackController.isPlaying -> {
                pause.visibility = View.VISIBLE
                pause.text = "暂停"
                end.visibility = View.VISIBLE
            }
            PlaybackController.isPaused -> {
                pause.visibility = View.VISIBLE
                pause.text = "继续"
                end.visibility = View.VISIBLE
            }
            else -> {
                pause.visibility = View.GONE
                pause.text = "暂停"
                end.visibility = View.GONE
            }
        }
    }

    private fun syncModeControls() {
        practiceButton?.text = if (practiceMode) "跟练开" else "跟练关"
        positionButton?.text = when {
            practiceMode -> "跟练区域"
            positionView != null -> "定位好了"
            else -> "定位"
        }
    }

    override fun onStateChanged(state: String) {
        Toast.makeText(context, state, Toast.LENGTH_SHORT).show()
    }

    override fun onPlaybackStarted() {
        if (practiceMode) {
            practiceStats = PracticeStats(startedAtMs = SystemClock.uptimeMillis())
            currentCue = null
            pendingTouchKeys.clear()
            pendingTouchPoints.clear()
            updateComboLabel()
        }
        syncPlaybackControls()
    }

    override fun onPlaybackPaused() {
        syncPlaybackControls()
    }

    override fun onPlaybackResumed() {
        syncPlaybackControls()
    }

    override fun onPlaybackFinished(completed: Boolean) {
        syncPlaybackControls()
        clearPracticeHighlights()
        if (practiceMode) {
            markCurrentCueMissed()
            showPracticeScore()
            positionLabel?.apply {
                text = "跟练模式"
                textSize = scaledText(18f)
            }
        }
        if (completed) {
            Toast.makeText(context, "演奏完成", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPracticeCountdown(seconds: Int) {
        if (!practiceMode) return
        if (positionView == null || !positionOverlayLocked) {
            showPracticeOverlay()
        }
        positionLabel?.apply {
            text = if (seconds > 0) seconds.toString() else ""
            textSize = if (seconds > 0) scaledText(54f) else scaledText(18f)
        }
    }

    override fun onPracticeCue(keys: List<Int>, kind: PlaybackController.PracticeCueKind, durationMs: Long) {
        if (!practiceMode) return
        if (positionView == null || !positionOverlayLocked) {
            showPracticeOverlay()
        }
        positionLabel?.text = ""
        markCurrentCueMissed()
        currentCue = ExpectedCue(
            keys = keys.toSet(),
            kind = kind,
            shownAtMs = SystemClock.uptimeMillis(),
            remainingHits = if (kind == PlaybackController.PracticeCueKind.DOUBLE ||
                kind == PlaybackController.PracticeCueKind.SIMULTANEOUS_DOUBLE
            ) 2 else 1
        )
        practiceStats = practiceStats.copy(total = practiceStats.total + 1)
        practiceCueVersion += 1
        val version = practiceCueVersion
        clearPracticeHighlights(incrementVersion = false)
        val color = when (kind) {
            PlaybackController.PracticeCueKind.SINGLE -> Color.argb(190, 42, 214, 116)
            PlaybackController.PracticeCueKind.SIMULTANEOUS -> Color.argb(198, 33, 150, 243)
            PlaybackController.PracticeCueKind.DOUBLE -> Color.argb(216, 255, 255, 255)
            PlaybackController.PracticeCueKind.SIMULTANEOUS_DOUBLE -> Color.argb(210, 156, 39, 176)
        }
        val visibleMs = practiceCueVisibleMs(durationMs)
        vibratePracticeCue(kind)
        keys.forEach { key -> showPracticeCell(key, color, visibleMs, version) }
    }

    private fun handlePracticeTouch(view: View, event: MotionEvent, bounds: OverlayBounds): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val rawPoint = rawPoint(view, event, pointerIndex)
                val key = keyAt(rawPoint.x, rawPoint.y, bounds)
                if (key != null) {
                    pendingTouchKeys += key
                    pendingTouchPoints[key] = rawPoint
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val rawPoint = rawPoint(view, event, pointerIndex)
                val key = keyAt(rawPoint.x, rawPoint.y, bounds)
                if (key != null) {
                    pendingTouchKeys += key
                    pendingTouchPoints[key] = rawPoint
                }
                finalizePracticeTouchGroup()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_CANCEL -> {
                pendingTouchKeys.clear()
                pendingTouchPoints.clear()
                return true
            }
        }
        return true
    }

    private fun rawPoint(view: View, event: MotionEvent, pointerIndex: Int): PointF {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return PointF(location[0] + event.getX(pointerIndex), location[1] + event.getY(pointerIndex))
    }

    private fun finalizePracticeTouchGroup() {
        if (pendingTouchKeys.isEmpty()) return
        val keys = pendingTouchKeys.toSet()
        val points = pendingTouchPoints.values.toList()
        pendingTouchKeys.clear()
        pendingTouchPoints.clear()
        positionView?.postDelayed({
            SkyAccessibilityService.activeService?.tap(points)
            scorePracticeTouch(keys)
        }, TOUCH_FORWARD_DELAY_MS)
    }

    private fun scorePracticeTouch(keys: Set<Int>) {
        val cue = currentCue
        if (cue == null) {
            registerPracticeMistake()
            return
        }
        val elapsedMs = SystemClock.uptimeMillis() - cue.shownAtMs
        if (keys == cue.keys) {
            if (cue.remainingHits > 1) {
                currentCue = cue.copy(remainingHits = cue.remainingHits - 1)
                return
            }
            val newStreak = practiceStats.streak + 1
            practiceStats = practiceStats.copy(
                matched = practiceStats.matched + 1,
                streak = newStreak,
                maxStreak = maxOf(practiceStats.maxStreak, newStreak),
                responseTotalMs = practiceStats.responseTotalMs + elapsedMs.coerceAtLeast(0L)
            )
            updateComboLabel()
            currentCue = null
        } else {
            registerPracticeMistake()
        }
    }

    private fun markCurrentCueMissed() {
        if (currentCue != null) {
            registerPracticeMistake()
            currentCue = null
        }
    }

    private fun registerPracticeMistake() {
        practiceStats = practiceStats.copy(
            mistakes = practiceStats.mistakes + 1,
            streak = 0
        )
        updateComboLabel()
    }

    private fun updateComboLabel() {
        val streak = practiceStats.streak
        comboLabel?.apply {
            if (streak >= 3) {
                text = "COMBO $streak"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun showPracticeScore() {
        val total = practiceStats.total.coerceAtLeast(1)
        val accuracy = practiceStats.matched.toDouble() / total
        val avgResponse = if (practiceStats.matched > 0) {
            practiceStats.responseTotalMs.toDouble() / practiceStats.matched
        } else {
            2500.0
        }
        val timingScore = (1.0 - (avgResponse / 1800.0)).coerceIn(0.0, 1.0)
        val mistakePenalty = (practiceStats.mistakes * 0.025).coerceAtMost(0.35)
        val score = (accuracy * 0.72 + timingScore * 0.28 - mistakePenalty).coerceIn(0.0, 1.0)
        val grade = when {
            score >= 0.95 -> "S"
            score >= 0.85 -> "A"
            score >= 0.70 -> "B"
            else -> "C"
        }
        val accuracyPercent = accuracy * 100.0
        lastScoreText = "上次: $grade ${"%.2f".format(Locale.US, accuracyPercent)}%"
        lastScoreLabel?.apply {
            text = lastScoreText
            visibility = View.VISIBLE
        }
        positionLabel?.apply {
            text = grade
            textSize = scaledText(72f)
            alpha = 1f
            animate().alpha(0f).setStartDelay(2000L).setDuration(900L).withEndAction {
                alpha = 1f
                text = "跟练模式"
                textSize = scaledText(18f)
            }.start()
        }
    }

    private fun keyAt(rawX: Float, rawY: Float, bounds: OverlayBounds): Int? {
        val localX = rawX - bounds.x
        val localY = rawY - bounds.y
        if (localX < 0f || localY < 0f || localX > bounds.width || localY > bounds.height) return null
        val col = (localX / (bounds.width / 5f)).toInt().coerceIn(0, 4)
        val row = (localY / (bounds.height / 3f)).toInt().coerceIn(0, 2)
        return row * 5 + col
    }

    private fun button(text: String, action: (View) -> Unit): Button {
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = scaledText(12f)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            includeFontPadding = false
            background = rounded(Color.argb(118, 0, 150, 136), scaledDp(6).toFloat())
            setPadding(scaledDp(4), scaledDp(2), scaledDp(4), scaledDp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(scaledDp(1), scaledDp(1), scaledDp(1), scaledDp(1))
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
            textSize = scaledText(15f)
            setTextColor(if (enabled) Color.WHITE else Color.argb(170, 255, 255, 255))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(scaledDp(12), 0, scaledDp(12), 0)
            background = rounded(
                if (index % 2 == 0) Color.argb(54, 255, 255, 255) else Color.argb(34, 255, 255, 255),
                scaledDp(7).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(-1, scaledDp(42)).apply {
                setMargins(0, scaledDp(3), 0, scaledDp(3))
            }
            if (enabled && action != null) {
                setOnClickListener { action() }
            }
        }
    }

    private fun baseParams(focusable: Boolean = false, touchable: Boolean = true): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
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

    private fun practiceLegend(): LinearLayout {
        val items = listOf(
            "绿色: 单击" to Color.rgb(42, 214, 116),
            "蓝色: 同时按" to Color.rgb(33, 150, 243),
            "白色: 双击" to Color.WHITE,
            "紫色: 同按双击" to Color.rgb(186, 85, 211)
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(104, 0, 0, 0), scaledDp(6).toFloat())
            setPadding(scaledDp(6), scaledDp(5), scaledDp(6), scaledDp(5))
            items.forEach { (label, color) ->
                addView(TextView(context).apply {
                    text = label
                    setTextColor(color)
                    textSize = scaledText(11f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, scaledDp(2), 0, scaledDp(2))
                })
            }
        }
    }

    private fun showPracticeLegend(bounds: OverlayBounds) {
        removePracticeLegend()
        val metrics = context.resources.displayMetrics
        val overlayWidth = maxOf(metrics.widthPixels, metrics.heightPixels)
        val width = scaledDp(108)
        val height = scaledDp(78)
        val margin = scaledDp(8)
        val view = practiceLegend()
        val params = baseParams(touchable = false).apply {
            this.width = width
            this.height = WindowManager.LayoutParams.WRAP_CONTENT
            this.x = (bounds.x + bounds.width + margin)
                .coerceAtMost(overlayWidth - width - margin)
                .coerceAtLeast(margin)
            this.y = (bounds.y + (bounds.height - height) / 2)
                .coerceAtLeast(margin)
        }
        practiceLegendParams = params
        practiceLegendView = view
        windowManager.addView(view, params)
    }

    private fun showAuthorWatermark() {
        if (authorView != null) return
        val metrics = context.resources.displayMetrics
        val width = scaledDp(92)
        val height = scaledDp(24)
        val margin = scaledDp(10)
        val view = TextView(context).apply {
            text = "By:iKeilo"
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = scaledText(11f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = rounded(Color.argb(88, 0, 0, 0), scaledDp(6).toFloat())
        }
        val params = baseParams(touchable = false).apply {
            this.width = width
            this.height = height
            this.x = (metrics.widthPixels - width - margin).coerceAtLeast(margin)
            this.y = (metrics.heightPixels - height - margin).coerceAtLeast(margin)
        }
        authorParams = params
        authorView = view
        windowManager.addView(view, params)
    }

    private fun defaultPracticeCellColor(): Int {
        return Color.argb(if (practiceMode) 58 else 40, 255, 255, 255)
    }

    private fun clearPracticeHighlights(incrementVersion: Boolean = true) {
        if (incrementVersion) {
            practiceCueVersion += 1
        }
        practiceFadeAnimators.values.forEach { it.cancel() }
        practiceFadeAnimators.clear()
        practiceCells.forEach { it.setBackgroundColor(defaultPracticeCellColor()) }
    }

    private fun showPracticeCell(key: Int, color: Int, visibleMs: Long, version: Long) {
        val cell = practiceCells.getOrNull(key) ?: return
        practiceFadeAnimators.remove(key)?.cancel()
        cell.setBackgroundColor(color)

        val fadeMs = (visibleMs * 0.35).roundToLong().coerceIn(180L, 900L)
        val holdMs = (visibleMs - fadeMs).coerceAtLeast(0L)
        val targetColor = defaultPracticeCellColor()
        cell.postDelayed({
            if (practiceCueVersion != version) return@postDelayed
            val animator = ValueAnimator.ofObject(ArgbEvaluator(), color, targetColor).apply {
                duration = fadeMs
                addUpdateListener { animation ->
                    cell.setBackgroundColor(animation.animatedValue as Int)
                }
            }
            practiceFadeAnimators[key] = animator
            animator.start()
        }, holdMs)
    }

    private fun practiceCueVisibleMs(durationMs: Long): Long {
        val baseMs = durationMs.takeIf { it > 0L } ?: 500L
        val speedScale = (1.0 / PlaybackController.speed.coerceAtLeast(0.1)).coerceIn(0.25, 4.0)
        return (baseMs * speedScale).roundToLong().coerceIn(500L, 4000L)
    }

    private fun vibratePracticeCue(kind: PlaybackController.PracticeCueKind) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return

        val effect = when (kind) {
            PlaybackController.PracticeCueKind.SINGLE ->
                VibrationEffect.createOneShot(24L, 96)
            PlaybackController.PracticeCueKind.SIMULTANEOUS ->
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 36L, 42L, 56L),
                    intArrayOf(0, 150, 0, 215),
                    -1
                )
            PlaybackController.PracticeCueKind.DOUBLE ->
                VibrationEffect.createOneShot(90L, 190)
            PlaybackController.PracticeCueKind.SIMULTANEOUS_DOUBLE ->
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 45L, 35L, 45L, 35L, 70L),
                    intArrayOf(0, 165, 0, 165, 0, 230),
                    -1
                )
        }
        vibrator.vibrate(effect)
    }

    private fun defaultPositionBounds(screenWidth: Int, screenHeight: Int): OverlayBounds {
        val landscapeWidth = maxOf(screenWidth, screenHeight)
        val landscapeHeight = minOf(screenWidth, screenHeight)
        val width = (landscapeWidth * 0.92f).roundToInt()
        val height = (landscapeHeight * 0.92f).roundToInt()
        return OverlayBounds(
            width = width,
            height = height,
            x = ((landscapeWidth - width) / 2f).roundToInt(),
            y = ((landscapeHeight - height) / 2f).roundToInt()
        )
    }

    private fun practiceOverlayBounds(screenWidth: Int, screenHeight: Int): OverlayBounds {
        val points = PlaybackController.keyPoints
        if (points.size != 15) return defaultPositionBounds(screenWidth, screenHeight)

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val cellW = ((maxX - minX) / 4f).takeIf { it > 0f }
        val cellH = ((maxY - minY) / 2f).takeIf { it > 0f }
        if (cellW == null || cellH == null) return defaultPositionBounds(screenWidth, screenHeight)

        return OverlayBounds(
            width = (cellW * 5f).roundToInt(),
            height = (cellH * 3f).roundToInt(),
            x = (minX - cellW / 2f).roundToInt(),
            y = (minY - cellH / 2f).roundToInt()
        )
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
                    resizing = event.x > view.width - scaledDp(96) && event.y > view.height - scaledDp(64)
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

    private fun scaledDp(value: Int): Int {
        return (value * uiScale * context.resources.displayMetrics.density).roundToInt()
            .coerceAtLeast(if (value > 0) 1 else 0)
    }

    private fun scaledText(value: Float): Float {
        return value * uiScale
    }

    private data class OverlayBounds(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int
    )

    private data class ExpectedCue(
        val keys: Set<Int>,
        val kind: PlaybackController.PracticeCueKind,
        val shownAtMs: Long,
        val remainingHits: Int
    )

    private data class PracticeStats(
        val total: Int = 0,
        val matched: Int = 0,
        val mistakes: Int = 0,
        val streak: Int = 0,
        val maxStreak: Int = 0,
        val responseTotalMs: Long = 0L,
        val startedAtMs: Long = 0L
    )

    private companion object {
        const val TOUCH_FORWARD_DELAY_MS = 35L
    }
}
