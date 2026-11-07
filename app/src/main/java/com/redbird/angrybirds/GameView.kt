package com.redbird.angrybirds

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 渲染与输入宿主（SurfaceView + 独立渲染线程）。
 *
 * 职责：
 * 1. 维护 Surface 生命周期与渲染循环（约 60 FPS）
 * 2. 维护场景路由：标题页 / 选关页 / 游戏页
 * 3. 将触摸坐标转换为世界坐标，分发给 [Game] 或 UI 按钮
 * 4. 调用 [Renderer] 完成绘制
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    enum class Scene {
        TITLE,
        LEVEL_SELECT,
        PLAYING,
        RANKING,
        SETTINGS
    }

    private data class TouchEvent(val action: Int, val x: Float, val y: Float)

    private val renderer = Renderer()
    private val progress = Progress(context.applicationContext)
    private val audio = GameAudio()
    private val haptics = Haptics(context.applicationContext)
    private val pendingTouches = ConcurrentLinkedQueue<TouchEvent>()

    private var scene = Scene.TITLE
    private var game: Game? = null
    private var levelIndex = 0
    private var confirmReset = false

    private var viewWidth = 0f
    private var viewHeight = 0f
    private var time = 0f

    @Volatile
    private var running = false
    private var renderThread: Thread? = null

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var moved = false

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    // ------------------------------------------------------------ 生命周期

    fun resume() {
        if (holder.surface.isValid) startLoop()
    }

    fun pause() {
        val current = game
        if (scene == Scene.PLAYING && current != null &&
            current.state != PlayState.WON && current.state != PlayState.LOST
        ) {
            current.paused = true
        }
        stopLoop()
    }

    fun stop() {
        stopLoop()
    }

    /** Activity 销毁时释放音频资源 */
    fun release() {
        stopLoop()
        audio.release()
    }

    /**
     * 处理系统返回键。
     * @return true 表示已消费（暂停 / 退回选关 / 退回标题）
     */
    fun onBack(): Boolean {
        return when (scene) {
            Scene.TITLE -> false
            Scene.LEVEL_SELECT -> {
                scene = Scene.TITLE
                true
            }
            Scene.RANKING -> {
                scene = Scene.TITLE
                true
            }
            Scene.SETTINGS -> {
                if (confirmReset) {
                    confirmReset = false
                } else {
                    scene = Scene.TITLE
                }
                true
            }
            Scene.PLAYING -> {
                val current = game
                if (current == null) {
                    goLevelSelect()
                } else if (current.paused ||
                    current.state == PlayState.WON ||
                    current.state == PlayState.LOST
                ) {
                    goLevelSelect()
                } else {
                    current.paused = true
                }
                true
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        game?.resize(viewWidth, viewHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> pendingTouches.add(
                TouchEvent(event.actionMasked, event.x, event.y)
            )
        }
        return true
    }

    // ------------------------------------------------------------ 渲染线程

    private fun startLoop() {
        if (running) return
        running = true
        renderThread = Thread({ loop() }, "angry-birds-render").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        val thread = renderThread
        renderThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(600)
            } catch (_: InterruptedException) {
                // 忽略
            }
        }
    }

    private fun loop() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            last = now
            time += dt

            drainTouches()
            updateGame(dt)
            drawFrame()

            val usedMs = (System.nanoTime() - now) / 1_000_000L
            val sleepMs = 16L - usedMs
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun drainTouches() {
        while (true) {
            val event = pendingTouches.poll() ?: break
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleDown(event.x, event.y)
                MotionEvent.ACTION_MOVE -> handleMove(event.x, event.y)
                MotionEvent.ACTION_UP -> handleUp(event.x, event.y)
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
        }
    }

    private fun updateGame(dt: Float) {
        if (scene != Scene.PLAYING) return
        val current = game ?: return
        current.resize(viewWidth, viewHeight)
        current.update(dt)

        for (sound in current.drainSounds()) {
            audio.play(sound)
            haptics.play(sound)
        }

        if (current.eggCollected && !current.eggSaved) {
            current.eggSaved = true
            progress.collectEgg(levelIndex)
        }

        if (!current.resultSaved && current.state == PlayState.WON) {
            current.resultSaved = true
            progress.recordResult(levelIndex, true, current.starsEarned, current.score)
        } else if (!current.resultSaved && current.state == PlayState.LOST) {
            current.resultSaved = true
            progress.recordResult(levelIndex, false, 0, current.score)
        }
    }

    private fun drawFrame() {
        if (!holder.surface.isValid) return
        val canvas = try {
            holder.lockCanvas()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            viewWidth = width
            viewHeight = height

            when (scene) {
                Scene.TITLE -> renderer.drawTitle(canvas, progress, width, height, time)
                Scene.LEVEL_SELECT ->
                    renderer.drawLevelSelect(canvas, progress, width, height, time)
                Scene.RANKING ->
                    renderer.drawRanking(canvas, progress, width, height, time)
                Scene.SETTINGS ->
                    renderer.drawSettings(canvas, progress, confirmReset, width, height, time)
                Scene.PLAYING -> {
                    val current = game
                    if (current != null) {
                        current.resize(width, height)
                        renderer.drawGame(canvas, current, progress, width, height, time)
                    } else {
                        renderer.drawLevelSelect(canvas, progress, width, height, time)
                    }
                }
            }
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
                // Surface 已失效
            }
        }
    }

    // ------------------------------------------------------------ 输入

    private fun handleDown(x: Float, y: Float) {
        downX = x
        downY = y
        moved = false
        dragging = false

        if (scene != Scene.PLAYING) return
        val current = game ?: return
        if (current.paused ||
            current.state == PlayState.WON ||
            current.state == PlayState.LOST
        ) return
        if (Ui.pauseButton(viewWidth, viewHeight).contains(x, y)) return
        if (Ui.bombButton(viewWidth, viewHeight).contains(x, y)) {
            current.armBomb()
            return
        }

        dragging = true
        current.onTouchDown(toWorldX(x, current), toWorldY(y, current))
    }

    private fun handleMove(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        val threshold = viewHeight * 0.02f
        if (!moved && dx * dx + dy * dy > threshold * threshold) moved = true

        if (!dragging) return
        val current = game ?: return
        current.onTouchMove(toWorldX(x, current), toWorldY(y, current))
    }

    private fun handleUp(x: Float, y: Float) {
        if (dragging) {
            dragging = false
            val current = game
            if (current != null) {
                current.onTouchUp(toWorldX(x, current), toWorldY(y, current))
            }
            return
        }
        if (!moved) handleTap(x, y)
    }

    private fun handleTap(x: Float, y: Float) {
        val width = viewWidth
        val height = viewHeight

        when (scene) {
            Scene.TITLE -> {
                when {
                    Ui.playButton(width, height).contains(x, y) -> scene = Scene.LEVEL_SELECT
                    Ui.rankButton(width, height).contains(x, y) -> scene = Scene.RANKING
                    Ui.settingsButton(width, height).contains(x, y) -> scene = Scene.SETTINGS
                }
            }

            Scene.LEVEL_SELECT -> {
                if (Ui.backButton(width, height).contains(x, y)) {
                    scene = Scene.TITLE
                    return
                }
                for (i in 0 until Levels.count) {
                    if (Ui.levelCell(width, height, i).contains(x, y)) {
                        if (progress.isUnlocked(i)) startLevel(i)
                        return
                    }
                }
            }

            Scene.PLAYING -> handlePlayTap(x, y)

            Scene.RANKING -> {
                if (Ui.backButton(width, height).contains(x, y)) {
                    scene = Scene.TITLE
                }
            }

            Scene.SETTINGS -> {
                if (confirmReset) {
                    when {
                        Ui.confirmYes(width, height).contains(x, y) -> {
                            progress.reset()
                            confirmReset = false
                        }
                        Ui.confirmNo(width, height).contains(x, y) -> confirmReset = false
                    }
                } else {
                    when {
                        Ui.backButton(width, height).contains(x, y) -> scene = Scene.TITLE
                        Ui.clearButton(width, height).contains(x, y) -> confirmReset = true
                    }
                }
            }
        }
    }

    private fun handlePlayTap(x: Float, y: Float) {
        val width = viewWidth
        val height = viewHeight
        val current = game ?: return

        if (current.paused) {
            when {
                Ui.overlayButton(width, height, 0, 3).contains(x, y) -> current.paused = false
                Ui.overlayButton(width, height, 1, 3).contains(x, y) -> startLevel(levelIndex)
                Ui.overlayButton(width, height, 2, 3).contains(x, y) -> goLevelSelect()
            }
            return
        }

        if (current.state == PlayState.WON) {
            when {
                Ui.overlayButton(width, height, 0, 3).contains(x, y) -> startLevel(levelIndex)
                Ui.overlayButton(width, height, 1, 3).contains(x, y) -> {
                    val next = levelIndex + 1
                    if (next < Levels.count && progress.isUnlocked(next)) {
                        startLevel(next)
                    } else {
                        goLevelSelect()
                    }
                }
                Ui.overlayButton(width, height, 2, 3).contains(x, y) -> goLevelSelect()
            }
            return
        }

        if (current.state == PlayState.LOST) {
            when {
                Ui.overlayButton(width, height, 0, 2).contains(x, y) -> startLevel(levelIndex)
                Ui.overlayButton(width, height, 1, 2).contains(x, y) -> goLevelSelect()
            }
            return
        }

        if (Ui.pauseButton(width, height).contains(x, y)) {
            current.paused = true
        }
    }

    private fun startLevel(index: Int) {
        if (index !in 0 until Levels.count) return
        levelIndex = index
        val newGame = Game(Levels.all[index], index)
        newGame.resize(viewWidth, viewHeight)
        game = newGame
        scene = Scene.PLAYING
    }

    private fun goLevelSelect() {
        game = null
        scene = Scene.LEVEL_SELECT
    }

    private fun toWorldX(x: Float, game: Game): Float =
        (x - game.viewWidth / 2f) / game.ppm + game.cameraX

    private fun toWorldY(y: Float, game: Game): Float =
        (game.viewHeight / 2f - y) / game.ppm + game.cameraY
}
