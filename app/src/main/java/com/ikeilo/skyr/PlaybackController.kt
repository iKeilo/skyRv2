package com.ikeilo.skyr

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToLong

object PlaybackController {
    enum class PracticeCueKind {
        SINGLE,
        SIMULTANEOUS,
        DOUBLE,
        SIMULTANEOUS_DOUBLE
    }

    interface Listener {
        fun onStateChanged(state: String)
        fun onPlaybackFinished(completed: Boolean)
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackResumed()
        fun onPlaybackProgress(positionMs: Long, totalMs: Long)
        fun onPracticeCountdown(seconds: Int)
        fun onPracticePreview(keys: List<Int>, kind: PracticeCueKind, leadTimeMs: Long)
        fun onPracticeCue(keys: List<Int>, kind: PracticeCueKind, durationMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var worker: Thread? = null
    @Volatile private var paused = false
    @Volatile private var stopped = true
    @Volatile private var generation = 0

    var listener: Listener? = null
    var song: Song? = null
    var songRef: SongRef? = null
    var keyPoints: List<PointF> = emptyList()
    var speed: Double = 1.0
    var practiceMode: Boolean = false

    val isPlaying: Boolean
        get() = worker != null && !stopped && !paused

    val isPaused: Boolean
        get() = worker != null && !stopped && paused

    fun start() {
        val currentSong = song ?: return notify("请先选择乐谱")
        val currentPracticeMode = practiceMode
        if (!currentPracticeMode && keyPoints.size != 15) return notify("请先定位琴键")
        val service = if (currentPracticeMode) {
            null
        } else {
            SkyAccessibilityService.activeService ?: return notify("无障碍服务未启动")
        }
        synchronized(lock) {
            if (worker?.isAlive == true && !paused) {
                notify("正在演奏")
                return
            }
            if (worker?.isAlive == true && paused) {
                stopInternal(notifyUser = false, callbackFinished = false)
            }
            generation += 1
        }
        val runGeneration = generation
        val totalProgressMs = totalScaledPlaybackMs(currentSong).coerceAtLeast(1L)

        stopped = false
        paused = false
        main.post {
            listener?.onPlaybackStarted()
            listener?.onPlaybackProgress(0L, totalProgressMs)
            val prefix = if (currentPracticeMode) "开始跟练" else "开始演奏"
            listener?.onStateChanged("$prefix: ${currentSong.name}")
        }

        worker = Thread {
            var progressMs = 0L

            fun reportProgress(force: Boolean = false) {
                if (shouldStop(runGeneration)) return
                val clamped = progressMs.coerceIn(0L, totalProgressMs)
                if (force || clamped == totalProgressMs || clamped == 0L) {
                    main.post { listener?.onPlaybackProgress(clamped, totalProgressMs) }
                } else {
                    main.post { listener?.onPlaybackProgress(clamped, totalProgressMs) }
                }
            }

            fun sleepWithProgress(durationMs: Long) {
                var remaining = durationMs.coerceAtLeast(0L)
                while (remaining > 0L && !shouldStop(runGeneration)) {
                    waitIfPaused(runGeneration)
                    val chunk = minOf(remaining, 40L)
                    Thread.sleep(chunk)
                    remaining -= chunk
                    progressMs += chunk
                    reportProgress()
                }
            }

            try {
                if (currentPracticeMode && !runPracticeCountdown(runGeneration)) {
                    return@Thread
                }
                for (index in currentSong.events.indices) {
                    val event = currentSong.events[index]
                    if (shouldStop(runGeneration)) break
                    waitIfPaused(runGeneration)
                    if (event.delayMs > 0L) {
                        val scaledDelayMs = scaledDelayMs(event.delayMs)
                        val practiceKeys = if (currentPracticeMode && event.keys.isNotEmpty()) {
                            event.keys.filter { it in 0 until 15 }
                        } else {
                            emptyList()
                        }
                        if (practiceKeys.isNotEmpty()) {
                            val kind = practiceCueKind(currentSong.events, index, practiceKeys)
                            val previewLeadMs = practicePreviewLeadMs(event.delayMs)
                            if (previewLeadMs in 1 until scaledDelayMs) {
                                sleepWithProgress(scaledDelayMs - previewLeadMs)
                                if (shouldStop(runGeneration)) break
                                main.post {
                                    listener?.onPracticePreview(practiceKeys, kind, previewLeadMs)
                                }
                                sleepWithProgress(previewLeadMs)
                            } else {
                                sleepWithProgress(scaledDelayMs)
                            }
                        } else {
                            sleepWithProgress(scaledDelayMs)
                        }
                    }
                    if (shouldStop(runGeneration)) break
                    if (event.keys.isNotEmpty()) {
                        if (currentPracticeMode) {
                            val keys = event.keys.filter { it in 0 until 15 }
                            if (keys.isNotEmpty()) {
                                val kind = practiceCueKind(currentSong.events, index, keys)
                                main.post {
                                    listener?.onPracticeCue(keys, kind, event.durationMs)
                                }
                            }
                        } else {
                            val points = event.keys.mapNotNull { keyPoints.getOrNull(it) }
                            if (service?.tap(points) != true) {
                                notify("手势派发失败")
                            }
                        }
                    }
                }
                progressMs = totalProgressMs
                reportProgress(force = true)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                val isCurrent = generation == runGeneration
                if (isCurrent) {
                    stopped = true
                    paused = false
                    worker = null
                    main.post {
                        listener?.onPlaybackFinished(completed = true)
                    }
                }
            }
        }.apply {
            name = "SkyRPlayback"
            start()
        }
    }

    fun pauseOrResume() {
        if (worker?.isAlive != true) return
        paused = !paused
        main.post {
            if (paused) {
                listener?.onPlaybackPaused()
                listener?.onStateChanged("已暂停")
            } else {
                listener?.onPlaybackResumed()
                listener?.onStateChanged("继续演奏")
            }
        }
    }

    fun stopCurrent() {
        stopInternal(notifyUser = true, callbackFinished = true)
    }

    @Deprecated("Use stopCurrent().")
    fun stop() {
        stopCurrent()
    }

    private fun stopInternal(notifyUser: Boolean, callbackFinished: Boolean) {
        val oldWorker = synchronized(lock) {
            generation += 1
            stopped = true
            paused = false
            val current = worker
            worker = null
            current
        }
        oldWorker?.interrupt()
        if (callbackFinished) {
            main.post {
                listener?.onPlaybackProgress(0L, totalScaledPlaybackMs(song).coerceAtLeast(1L))
                listener?.onPlaybackFinished(completed = false)
            }
        }
        if (notifyUser) {
            notify("已结束")
        }
    }

    private fun waitIfPaused(runGeneration: Int) {
        while (paused && !shouldStop(runGeneration)) {
            Thread.sleep(50L)
        }
    }

    private fun sleepUnscaled(delayMs: Long, runGeneration: Int) {
        var remaining = delayMs.coerceAtLeast(0L)
        while (remaining > 0L && !shouldStop(runGeneration)) {
            waitIfPaused(runGeneration)
            val chunk = minOf(remaining, 40L)
            Thread.sleep(chunk)
            remaining -= chunk
        }
    }

    private fun shouldStop(runGeneration: Int): Boolean {
        return stopped || generation != runGeneration
    }

    private fun runPracticeCountdown(runGeneration: Int): Boolean {
        for (seconds in 3 downTo 1) {
            if (shouldStop(runGeneration)) return false
            main.post { listener?.onPracticeCountdown(seconds) }
            sleepUnscaled(1000L, runGeneration)
        }
        if (shouldStop(runGeneration)) return false
        main.post { listener?.onPracticeCountdown(0) }
        return true
    }

    private fun practiceCueKind(events: List<MusicEvent>, index: Int, keys: List<Int>): PracticeCueKind {
        val next = nextKeyEvent(events, index)
        val isDouble = next != null &&
            next.delayMs <= DOUBLE_TAP_WINDOW_MS &&
            next.keys.toSet() == keys.toSet()
        return when {
            isDouble && keys.size > 1 -> PracticeCueKind.SIMULTANEOUS_DOUBLE
            isDouble -> PracticeCueKind.DOUBLE
            keys.size > 1 -> PracticeCueKind.SIMULTANEOUS
            else -> PracticeCueKind.SINGLE
        }
    }

    private fun nextKeyEvent(events: List<MusicEvent>, index: Int): MusicEvent? {
        var delay = 0L
        for (i in index + 1 until events.size) {
            val event = events[i]
            delay += event.delayMs
            if (event.keys.isNotEmpty()) {
                return event.copy(delayMs = delay)
            }
        }
        return null
    }

    private fun notify(message: String) {
        main.post { listener?.onStateChanged(message) }
    }

    private fun scaledDelayMs(delayMs: Long): Long {
        return (delayMs * (1.0 / speed)).roundToLong().coerceAtLeast(0L)
    }

    private fun totalScaledPlaybackMs(song: Song?): Long {
        if (song == null) return 0L
        return song.events.sumOf { scaledDelayMs(it.delayMs) }
    }

    private fun practicePreviewLeadMs(delayMs: Long): Long {
        val scaledDelay = scaledDelayMs(delayMs)
        if (scaledDelay < MIN_PREVIEW_GAP_MS) return 0L
        return (scaledDelay * PREVIEW_LEAD_RATIO).roundToLong()
            .coerceIn(MIN_PREVIEW_GAP_MS, MAX_PREVIEW_GAP_MS)
            .coerceAtMost(scaledDelay - 1L)
    }

    private const val DOUBLE_TAP_WINDOW_MS = 320L
    private const val MIN_PREVIEW_GAP_MS = 220L
    private const val MAX_PREVIEW_GAP_MS = 680L
    private const val PREVIEW_LEAD_RATIO = 0.42
}
