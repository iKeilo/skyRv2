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
        fun onPracticeCountdown(seconds: Int)
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

        stopped = false
        paused = false
        main.post {
            listener?.onPlaybackStarted()
            val prefix = if (currentPracticeMode) "开始跟练" else "开始演奏"
            listener?.onStateChanged("$prefix: ${currentSong.name}")
        }

        worker = Thread {
            try {
                if (currentPracticeMode && !runPracticeCountdown(runGeneration)) {
                    return@Thread
                }
                for (index in currentSong.events.indices) {
                    val event = currentSong.events[index]
                    if (shouldStop(runGeneration)) break
                    waitIfPaused(runGeneration)
                    if (event.delayMs > 0L) {
                        sleepScaled(event.delayMs, runGeneration)
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
            main.post { listener?.onPlaybackFinished(completed = false) }
        }
        if (notifyUser) {
            notify("已结束")
        }
    }

    @Deprecated("Use stopCurrent().")
    fun stop() {
        stopCurrent()
    }

    private fun waitIfPaused(runGeneration: Int) {
        while (paused && !shouldStop(runGeneration)) {
            Thread.sleep(50L)
        }
    }

    private fun sleepScaled(delayMs: Long, runGeneration: Int) {
        var remaining = (delayMs * (1.0 / speed)).roundToLong().coerceAtLeast(0L)
        while (remaining > 0L && !shouldStop(runGeneration)) {
            waitIfPaused(runGeneration)
            val chunk = minOf(remaining, 40L)
            Thread.sleep(chunk)
            remaining -= chunk
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

    private const val DOUBLE_TAP_WINDOW_MS = 320L
}
