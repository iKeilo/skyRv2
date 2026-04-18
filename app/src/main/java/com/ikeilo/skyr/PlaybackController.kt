package com.ikeilo.skyr

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToLong

object PlaybackController {
    interface Listener {
        fun onStateChanged(state: String)
        fun onPlaybackFinished()
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackResumed()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var worker: Thread? = null
    @Volatile private var paused = false
    @Volatile private var stopped = true
    @Volatile private var generation = 0

    var listener: Listener? = null
    var song: Song? = null
    var keyPoints: List<PointF> = emptyList()
    var speed: Double = 1.0

    val isPlaying: Boolean
        get() = worker?.isAlive == true && !paused

    val isPaused: Boolean
        get() = worker?.isAlive == true && paused

    fun start() {
        val currentSong = song ?: return notify("请先选择乐谱")
        if (keyPoints.size != 15) return notify("请先定位琴键")
        val service = SkyAccessibilityService.activeService ?: return notify("无障碍服务未启动")
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
            listener?.onStateChanged("开始演奏: ${currentSong.name}")
        }

        worker = Thread {
            try {
                for (event in currentSong.events) {
                    if (shouldStop(runGeneration)) break
                    waitIfPaused(runGeneration)
                    if (event.delayMs > 0L) {
                        sleepScaled(event.delayMs, runGeneration)
                    }
                    if (shouldStop(runGeneration)) break
                    if (event.keys.isNotEmpty()) {
                        val points = event.keys.mapNotNull { keyPoints.getOrNull(it) }
                        if (!service.tap(points)) {
                            notify("手势派发失败")
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
                        listener?.onPlaybackFinished()
                        listener?.onStateChanged("演奏结束")
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
            main.post { listener?.onPlaybackFinished() }
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

    private fun shouldStop(runGeneration: Int): Boolean {
        return stopped || generation != runGeneration
    }

    private fun notify(message: String) {
        main.post { listener?.onStateChanged(message) }
    }
}
