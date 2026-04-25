package com.ikeilo.skyr

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
) : PlaybackController.Listener, VolumeShortcutManager.Dispatcher {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val positionStore = PositionStore(context)
    private val uiPrefs = context.getSharedPreferences("overlay_ui", Context.MODE_PRIVATE)
    private val songLibrary = SongLibrary(context)
    private val shortcutSettings = VolumeShortcutSettings(context)

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
    private var favoriteButton: Button? = null
    private var volumeSuppressionButton: Button? = null
    private var songLabel: TextView? = null
    private var positionLabel: TextView? = null

    private var positionOverlayLocked = false
    private var practiceMode = false
    private var practiceCueVersion = 0L
    private var controlsAnchorX = 20
    private var controlsAnchorY = 80
    private var currentSongMode = preferredSongMode()

    private val practiceCells = mutableListOf<TextView>()
    private val practiceFadeAnimators = mutableMapOf<Int, ValueAnimator>()
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
        VolumeShortcutManager.dispatcher = this
        positionStore.load()?.let { PlaybackController.keyPoints = it.points }
    }

    fun showControls(x: Int = 20, y: Int = 80) {
        PlaybackController.listener = this
        VolumeShortcutManager.dispatcher = this
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (controls != null) {
            syncPlaybackControls()
            refreshCurrentSongUi()
            syncVolumeSuppressionButton()
            return
        }
        controlsAnchorX = x
        controlsAnchorY = y

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(110, 10, 14, 15), scaledDp(6).toFloat())
            setPadding(scaledDp(4), scaledDp(4), scaledDp(4), scaledDp(4))
        }
        songLabel = TextView(context).apply {
            text = currentSongLabel()
            setTextColor(Color.WHITE)
            textSize = scaledText(11f)
            maxLines = 1
            setPadding(scaledDp(3), 0, scaledDp(3), scaledDp(3))
        }
        val pick = button("选曲") { showSongPicker() }
        val play = button("开始") { PlaybackController.start() }
        favoriteButton = button("收藏") { toggleFavoriteCurrentSong() }
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
        volumeSuppressionButton = button("音量抑制") { toggleVolumeSuppression() }
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
        listOf(
            pick,
            play,
            favoriteButton,
            practiceButton,
            pauseButton,
            end,
            speed,
            uiSize,
            volumeSuppressionButton,
            positionButton,
            exit
        ).forEach { button -> button?.let(root::addView) }
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
        refreshCurrentSongUi()
        syncVolumeSuppressionButton()
        syncPlaybackControls()
        syncModeControls()
    }

    fun importSong(uri: Uri) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("无法读取文件")
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "Imported.txt"
            val song = MusicParser.parse(fileName, MusicParser.decode(bytes))
            val songRef = songLibrary.registerImportedSong(uri, song.name)
            setSelectedSong(songRef, song, closePicker = true, toastMessage = "已导入乐谱: ${song.name}")
        } catch (error: Exception) {
            Toast.makeText(context, error.message ?: "读取乐谱失败", Toast.LENGTH_LONG).show()
        }
    }

    fun toggleControlsVisibility() {
        if (controls != null) {
            controlsParams?.let {
                controlsAnchorX = it.x
                controlsAnchorY = it.y
            }
            removeSongPicker()
            removeControls()
            Toast.makeText(context, "悬浮窗已隐藏", Toast.LENGTH_SHORT).show()
        } else {
            showControls(controlsAnchorX, controlsAnchorY)
            Toast.makeText(context, "悬浮窗已显示", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleCurrentSongFavoriteFromApp() {
        toggleFavoriteCurrentSong()
    }

    fun isCurrentSongFavorite(): Boolean {
        return songLibrary.isFavorite(PlaybackController.songRef)
    }

    fun isVolumeSuppressionEnabled(): Boolean {
        return shortcutSettings.isVolumeSuppressionEnabled()
    }

    fun setVolumeSuppressionEnabled(enabled: Boolean) {
        shortcutSettings.setVolumeSuppressionEnabled(enabled)
        SkyAccessibilityService.activeService?.refreshKeyFilterState()
        syncVolumeSuppressionButton()
    }

    fun shortcutAction(trigger: VolumeShortcutTrigger): VolumeShortcutAction {
        return shortcutSettings.actionFor(trigger)
    }

    fun cycleShortcutAction(trigger: VolumeShortcutTrigger): VolumeShortcutAction {
        val next = shortcutSettings.cycleAction(trigger)
        SkyAccessibilityService.activeService?.refreshKeyFilterState()
        return next
    }

    override fun onVolumeShortcut(action: VolumeShortcutAction) {
        when (action) {
            VolumeShortcutAction.NONE -> Unit
            VolumeShortcutAction.TOGGLE_UI -> toggleControlsVisibility()
            VolumeShortcutAction.PREVIOUS_SONG -> navigateSong(-1)
            VolumeShortcutAction.NEXT_SONG -> navigateSong(1)
            VolumeShortcutAction.START_OR_RESTART -> PlaybackController.start()
            VolumeShortcutAction.PAUSE_OR_RESUME -> {
                if (PlaybackController.isPlaying || PlaybackController.isPaused) {
                    PlaybackController.pauseOrResume()
                } else {
                    PlaybackController.start()
                }
            }
            VolumeShortcutAction.STOP -> {
                if (PlaybackController.isPlaying || PlaybackController.isPaused) {
                    PlaybackController.stopCurrent()
                }
            }
            VolumeShortcutAction.TOGGLE_SONG_PICKER -> toggleSongPicker()
        }
    }

    private fun toggleSongPicker() {
        if (songPickerView != null) {
            removeSongPicker()
            return
        }
        if (controls == null) {
            showControls(controlsAnchorX, controlsAnchorY)
        }
        showSongPicker()
    }

    private fun showSongPicker() {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (songPickerView != null) return

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.86f).roundToInt()
        val height = (metrics.heightPixels * 0.68f).roundToInt()
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
        val closeButton = button("关闭") { removeSongPicker() }.apply {
            minWidth = scaledDp(44)
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, scaledDp(42), 1f))
        titleBar.addView(importButton)
        titleBar.addView(closeButton)

        val modeBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val playlistModeButton = button("歌单") {
            currentSongMode = SongListMode.PLAYLIST
            saveSongMode(currentSongMode)
        }
        val allModeButton = button("全部") {
            currentSongMode = SongListMode.ALL
            saveSongMode(currentSongMode)
        }
        modeBar.addView(playlistModeButton)
        modeBar.addView(allModeButton)

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

        fun refreshModeButtons(activeMode: SongListMode) {
            styleModeButton(playlistModeButton, activeMode == SongListMode.PLAYLIST)
            styleModeButton(allModeButton, activeMode == SongListMode.ALL)
        }

        fun render(keyword: String) {
            val normalized = keyword.trim().lowercase(Locale.getDefault())
            val activeMode = resolveSongModeForDisplay()
            val songs = songsForMode(activeMode).filter { ref ->
                normalized.isBlank() || ref.name.lowercase(Locale.getDefault()).contains(normalized)
            }
            refreshModeButtons(activeMode)
            val modeLabel = if (activeMode == SongListMode.PLAYLIST) "歌单" else "全部"
            countLabel.text = "$modeLabel ${songs.size} 首"
            list.removeAllViews()
            if (songs.isEmpty()) {
                val emptyText = if (activeMode == SongListMode.PLAYLIST) {
                    "歌单还没有收藏曲目"
                } else {
                    "没有找到匹配乐谱"
                }
                list.addView(emptySongRow(emptyText))
                return
            }
            songs.forEachIndexed { index, ref ->
                list.addView(songRow(ref, index,
                    onToggleFavorite = {
                        songLibrary.toggleFavorite(ref)
                        refreshCurrentSongUi()
                        render(search.text?.toString().orEmpty())
                    },
                    action = {
                        selectSong(ref)
                    }
                ))
            }
        }

        playlistModeButton.setOnClickListener {
            currentSongMode = SongListMode.PLAYLIST
            saveSongMode(currentSongMode)
            render(search.text?.toString().orEmpty())
        }
        allModeButton.setOnClickListener {
            currentSongMode = SongListMode.ALL
            saveSongMode(currentSongMode)
            render(search.text?.toString().orEmpty())
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        root.addView(titleBar)
        root.addView(modeBar)
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
        val bounds = if (locked) {
            practiceOverlayBounds(metrics.widthPixels, metrics.heightPixels)
        } else {
            defaultPositionBounds(metrics.widthPixels, metrics.heightPixels)
        }
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
        val handle = TextView(context).apply {
            text = "resize"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = scaledText(12f)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1).apply { setMargins(10, 10, 10, 10) })
        root.addView(label, FrameLayout.LayoutParams(-1, -1))
        if (!locked) {
            root.addView(handle, FrameLayout.LayoutParams(scaledDp(84), scaledDp(44), Gravity.BOTTOM or Gravity.END))
            makePositionAdjustable(root)
        }

        val params = baseParams(touchable = !locked).apply {
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
        PlaybackController.song = song
        refreshCurrentSongUi()
        onSongChanged()
    }

    private fun selectSong(ref: SongRef, closePicker: Boolean = true) {
        try {
            val song = songLibrary.loadSong(ref)
            setSelectedSong(ref, song, closePicker = closePicker, toastMessage = "已读取乐谱: ${song.name}")
        } catch (error: Exception) {
            Toast.makeText(context, error.message ?: "读取乐谱失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun setSelectedSong(ref: SongRef, song: Song, closePicker: Boolean, toastMessage: String? = null) {
        PlaybackController.songRef = ref
        onSongSelected(song)
        if (closePicker) {
            removeSongPicker()
        }
        toastMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    private fun navigateSong(step: Int) {
        val songs = songsForNavigation()
        if (songs.isEmpty()) {
            Toast.makeText(context, "当前列表没有可切换的曲目", Toast.LENGTH_SHORT).show()
            return
        }
        val currentId = PlaybackController.songRef?.id
        val currentIndex = songs.indexOfFirst { it.id == currentId }
        val target = if (currentIndex >= 0) {
            songs[(currentIndex + step + songs.size) % songs.size]
        } else if (step >= 0) {
            songs.first()
        } else {
            songs.last()
        }
        selectSong(target, closePicker = false)
        Toast.makeText(context, if (step > 0) "已切到下一曲" else "已切到上一曲", Toast.LENGTH_SHORT).show()
    }

    private fun songsForNavigation(): List<SongRef> {
        val preferred = songsForMode(resolveSongModeForDisplay())
        return if (preferred.isNotEmpty()) preferred else songLibrary.songs(SongListMode.ALL)
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
        controlsParams?.let {
            controlsAnchorX = it.x
            controlsAnchorY = it.y
        }
        controls?.let { windowManager.removeView(it) }
        removeAuthorWatermark()
        controls = null
        controlsParams = null
        pauseButton = null
        endButton = null
        practiceButton = null
        positionButton = null
        favoriteButton = null
        volumeSuppressionButton = null
        songLabel = null
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
        val wasPickerOpen = songPickerView != null
        val wasPracticeOverlayOpen = practiceMode && positionView != null
        uiSizeIndex = (uiSizeIndex + 1) % uiScaleValues.size
        uiPrefs.edit().putInt("uiSizeIndex", uiSizeIndex).apply()

        if (wasPracticeOverlayOpen) {
            removePositionOverlay()
        }
        removeSongPicker()
        removeControls()
        showControls(controlsAnchorX, controlsAnchorY)
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

    private fun toggleFavoriteCurrentSong() {
        val ref = PlaybackController.songRef
        if (ref == null) {
            Toast.makeText(context, "请先选择乐谱", Toast.LENGTH_SHORT).show()
            return
        }
        val nowFavorite = songLibrary.toggleFavorite(ref)
        if (currentSongMode == SongListMode.PLAYLIST && songLibrary.favoriteCount() == 0) {
            currentSongMode = SongListMode.ALL
            saveSongMode(currentSongMode)
        }
        refreshCurrentSongUi()
        Toast.makeText(context, if (nowFavorite) "已加入收藏歌单" else "已移出收藏歌单", Toast.LENGTH_SHORT).show()
    }

    private fun toggleVolumeSuppression() {
        val enabled = !shortcutSettings.isVolumeSuppressionEnabled()
        shortcutSettings.setVolumeSuppressionEnabled(enabled)
        SkyAccessibilityService.activeService?.refreshKeyFilterState()
        syncVolumeSuppressionButton()
        Toast.makeText(context, if (enabled) "已开启音量控制抑制" else "已关闭音量控制抑制", Toast.LENGTH_SHORT).show()
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

    private fun refreshCurrentSongUi() {
        songLabel?.text = currentSongLabel()
        favoriteButton?.text = if (songLibrary.isFavorite(PlaybackController.songRef)) "取消收藏" else "收藏"
    }

    private fun syncVolumeSuppressionButton() {
        volumeSuppressionButton?.text = if (shortcutSettings.isVolumeSuppressionEnabled()) {
            "音量抑制 开"
        } else {
            "音量抑制 关"
        }
    }

    private fun currentSongLabel(): String {
        val current = PlaybackController.song?.name ?: "未选择"
        val modeLabel = if (resolveSongModeForDisplay() == SongListMode.PLAYLIST) "歌单" else "全部"
        return "乐谱: $current [$modeLabel]"
    }

    override fun onStateChanged(state: String) {
        Toast.makeText(context, state, Toast.LENGTH_SHORT).show()
    }

    override fun onPlaybackStarted() {
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
        ref: SongRef,
        index: Int,
        onToggleFavorite: () -> Unit,
        action: () -> Unit
    ): View {
        val isFavorite = songLibrary.isFavorite(ref)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(
                if (index % 2 == 0) Color.argb(54, 255, 255, 255) else Color.argb(34, 255, 255, 255),
                scaledDp(7).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(-1, scaledDp(42)).apply {
                setMargins(0, scaledDp(3), 0, scaledDp(3))
            }
            addView(TextView(context).apply {
                text = ref.name
                textSize = scaledText(15f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(scaledDp(12), 0, scaledDp(12), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(TextView(context).apply {
                text = if (isFavorite) "★" else "☆"
                textSize = scaledText(18f)
                setTextColor(if (isFavorite) Color.rgb(255, 215, 0) else Color.argb(200, 255, 255, 255))
                gravity = Gravity.CENTER
                setPadding(scaledDp(10), 0, scaledDp(10), 0)
                setOnClickListener { onToggleFavorite() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, -1))
        }
    }

    private fun emptySongRow(text: String): View {
        return TextView(context).apply {
            this.text = text
            textSize = scaledText(14f)
            setTextColor(Color.argb(190, 255, 255, 255))
            gravity = Gravity.CENTER
            background = rounded(Color.argb(34, 255, 255, 255), scaledDp(7).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, scaledDp(52)).apply {
                setMargins(0, scaledDp(6), 0, 0)
            }
        }
    }

    private fun styleModeButton(button: Button, selected: Boolean) {
        button.background = rounded(
            if (selected) Color.argb(168, 0, 150, 136) else Color.argb(78, 255, 255, 255),
            scaledDp(6).toFloat()
        )
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
                    controlsAnchorX = params.x
                    controlsAnchorY = params.y
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

    private fun songsForMode(mode: SongListMode): List<SongRef> {
        return songLibrary.songs(mode)
    }

    private fun resolveSongModeForDisplay(): SongListMode {
        return if (currentSongMode == SongListMode.PLAYLIST && songLibrary.favoriteCount() == 0) {
            SongListMode.ALL
        } else {
            currentSongMode
        }
    }

    private fun preferredSongMode(): SongListMode {
        return when (uiPrefs.getString(KEY_SONG_MODE, null)) {
            SongListMode.PLAYLIST.name -> if (songLibrary.favoriteCount() > 0) SongListMode.PLAYLIST else SongListMode.ALL
            SongListMode.ALL.name -> SongListMode.ALL
            else -> songLibrary.defaultMode()
        }
    }

    private fun saveSongMode(mode: SongListMode) {
        uiPrefs.edit().putString(KEY_SONG_MODE, mode.name).apply()
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

    private companion object {
        const val KEY_SONG_MODE = "song_mode"
    }
}
