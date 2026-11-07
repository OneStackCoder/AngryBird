package com.redbird.angrybirds

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 全部绘制逻辑（纯 Canvas，无 XML 布局、无图片资源）。
 *
 * 职责：
 * 1. 游戏场景：天空 / 云 / 远山 / 草地、弹弓、小鸟、猪、木石冰箱、粒子、飘字
 * 2. HUD：暂停、分数、关卡名、剩余小鸟、瞄准提示
 * 3. 覆盖层：暂停 / 胜利（星级）/ 失败
 * 4. 标题页与关卡选择页
 */
class Renderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyPaint = Paint()
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val random = Random()

    private var skyShader: LinearGradient? = null
    private var skyShaderHeight = -1f

    private var shakeX = 0f
    private var shakeY = 0f

    private val starPath = Path()
    private val tempPath = Path()
    private val tempRect = RectF()

    // ------------------------------------------------------------------ 入口

    fun drawGame(
        canvas: Canvas,
        game: Game,
        progress: Progress,
        width: Float,
        height: Float,
        time: Float
    ) {
        game.resize(width, height)

        val shakePx = game.shake * game.ppm * 0.35f
        if (shakePx > 0.1f) {
            shakeX = (random.nextFloat() - 0.5f) * 2f * shakePx
            shakeY = (random.nextFloat() - 0.5f) * 2f * shakePx
        } else {
            shakeX = 0f
            shakeY = 0f
        }

        drawSky(canvas, width, height)
        drawSun(canvas, width, height)
        drawClouds(canvas, width, height, game.cameraX, game.ppm, time)

        val groundY = sy(game, 0f)
        drawHills(canvas, width, groundY, game.cameraX * game.ppm, height * 0.16f)
        drawGround(canvas, width, height, groundY, game.ppm, game.cameraX)

        // 动态实体：位置 / 旋转 / 破损都由物理世界驱动
        for (block in game.blocks) {
            if (block.removed) continue
            drawBlock(
                canvas,
                sx(game, block.x),
                sy(game, block.y),
                block.width * game.ppm,
                block.height * game.ppm,
                -block.angle,
                block.material,
                block.damage
            )
        }
        for (pig in game.pigs) {
            if (pig.removed) continue
            drawPig(
                canvas,
                sx(game, pig.x),
                sy(game, pig.y),
                pig.radius * game.ppm,
                pig.damage,
                pig.hitFlash,
                time
            )
        }
        for (egg in game.eggs) {
            if (egg.removed) continue
            drawEgg(canvas, sx(game, egg.x), sy(game, egg.y), egg.radius * game.ppm, time)
        }
        for (bird in game.birds) {
            if (bird.removed) continue
            drawBird(
                canvas,
                sx(game, bird.x),
                sy(game, bird.y),
                bird.radius * game.ppm,
                bird.birdType ?: BirdType.RED,
                -bird.angle,
                time
            )
        }

        drawParticles(canvas, game)
        drawPopups(canvas, game)
        drawTrajectory(canvas, game)
        drawWaitingBirds(canvas, game, time)
        drawSling(canvas, game, time)

        drawHud(canvas, game, width, height, time)
        drawOverlay(canvas, game, progress, width, height)

        if (game.flashAlpha > 0.01f) {
            fill.color = Color.argb(
                (game.flashAlpha.coerceIn(0f, 1f) * 255f).toInt(), 255, 255, 255
            )
            canvas.drawRect(0f, 0f, width, height, fill)
        }
    }

    fun drawTitle(canvas: Canvas, progress: Progress, width: Float, height: Float, time: Float) {
        shakeX = 0f
        shakeY = 0f

        drawSky(canvas, width, height)
        drawSun(canvas, width, height)
        drawClouds(canvas, width, height, time * 6f, height * 0.02f, time)

        val groundY = height * 0.88f
        drawHills(canvas, width, groundY, height * 0.0f, height * 0.14f)
        fill.color = GRASS
        canvas.drawRect(0f, groundY, width, height, fill)
        fill.color = GRASS_LIGHT
        canvas.drawRect(0f, groundY, width, groundY + height * 0.008f, fill)

        drawBigTitle(canvas, "愤怒的小鸟", width / 2f, height * 0.22f, height * 0.145f)
        drawText(
            canvas, "ANGRY BIRDS · 复刻版", width / 2f, height * 0.295f,
            height * 0.036f, 0xFF6B4A2B.toInt()
        )

        val r = height * 0.072f
        val baseY = groundY - r * 0.95f
        drawBird(canvas, width * 0.26f, baseY + sin(time * 2f) * height * 0.006f, r, BirdType.RED, 0f, time)
        drawBird(canvas, width * 0.5f, baseY + sin(time * 2f + 1.4f) * height * 0.006f, r * 0.92f, BirdType.YELLOW, 0f, time)
        drawBird(canvas, width * 0.74f, baseY + sin(time * 2f + 2.6f) * height * 0.006f, r * 0.95f, BirdType.BLACK, 0f, time)

        val btn = Ui.playButton(width, height)
        val pulse = 1f + sin(time * 2.6f) * 0.02f
        val scaledButton = RectF(
            btn.centerX() - btn.width() * pulse / 2f,
            btn.centerY() - btn.height() * pulse / 2f,
            btn.centerX() + btn.width() * pulse / 2f,
            btn.centerY() + btn.height() * pulse / 2f
        )
        drawButton(canvas, scaledButton, "开始游戏")

        drawButton(canvas, Ui.rankButton(width, height), "排行榜")
        drawButton(canvas, Ui.settingsButton(width, height), "设置")

        drawStar(canvas, width / 2f - height * 0.055f, height * 0.63f, height * 0.023f, true)
        drawText(
            canvas,
            "${progress.totalStars()} / ${Levels.count * 3}",
            width / 2f - height * 0.025f,
            height * 0.64f,
            height * 0.038f,
            0xFFFFFFFF.toInt(),
            Paint.Align.LEFT
        )

        drawText(
            canvas, "拖动小鸟，击倒所有猪", width / 2f, height * 0.955f,
            height * 0.032f, Color.argb(210, 255, 255, 255)
        )
    }

    fun drawLevelSelect(canvas: Canvas, progress: Progress, width: Float, height: Float, time: Float) {
        shakeX = 0f
        shakeY = 0f

        drawSky(canvas, width, height)
        drawSun(canvas, width, height)
        drawClouds(canvas, width, height, time * 4f, height * 0.02f, time)

        val groundY = height * 0.94f
        drawHills(canvas, width, groundY, 0f, height * 0.13f)
        fill.color = GRASS
        canvas.drawRect(0f, groundY, width, height, fill)

        drawText(
            canvas, "选择关卡", width / 2f, height * 0.125f,
            height * 0.075f, 0xFF4F3A22.toInt()
        )

        drawBackButton(canvas, Ui.backButton(width, height))

        drawStar(canvas, width - height * 0.175f, height * 0.078f, height * 0.024f, true)
        drawText(
            canvas,
            "${progress.totalStars()} / ${Levels.count * 3}",
            width - height * 0.145f,
            height * 0.09f,
            height * 0.04f,
            0xFF4F3A22.toInt(),
            Paint.Align.LEFT
        )

        drawEgg(canvas, width - height * 0.175f, height * 0.148f, height * 0.018f, time)
        drawText(
            canvas,
            "${progress.eggCount()} / ${Levels.EGGS_FOR_HIDDEN}",
            width - height * 0.145f,
            height * 0.158f,
            height * 0.032f,
            0xFF9A6A18.toInt(),
            Paint.Align.LEFT
        )

        for (i in 0 until Levels.count) {
            val cell = Ui.levelCell(width, height, i)
            val unlocked = progress.isUnlocked(i)
            val radius = height * 0.022f
            val hidden = i == Levels.hiddenIndex

            fill.color = when {
                !unlocked -> 0xFFB9B2A6.toInt()
                hidden -> 0xFFFFE9A8.toInt()
                else -> 0xFFFFF2D2.toInt()
            }
            canvas.drawRoundRect(cell, radius, radius, fill)

            stroke.color = when {
                !unlocked -> 0xFF8E877C.toInt()
                hidden -> 0xFFD9A02B.toInt()
                else -> 0xFFD9A54B.toInt()
            }
            stroke.strokeWidth = max(2f, height * 0.005f)
            canvas.drawRoundRect(cell, radius, radius, stroke)

            if (!unlocked) {
                drawLock(canvas, cell.centerX(), cell.centerY(), cell.height() * 0.34f)
                if (hidden) {
                    drawText(
                        canvas, "金蛋 ×${Levels.EGGS_FOR_HIDDEN}", cell.centerX(),
                        cell.bottom - cell.height() * 0.07f, cell.height() * 0.13f,
                        0xFF6F685E.toInt()
                    )
                }
                continue
            }

            if (hidden) {
                drawEgg(
                    canvas, cell.centerX(), cell.centerY() - cell.height() * 0.05f,
                    cell.height() * 0.19f, time
                )
            } else {
                drawText(
                    canvas, "${i + 1}", cell.centerX(),
                    cell.centerY() + cell.height() * 0.10f,
                    cell.height() * 0.40f, 0xFF7A4A22.toInt()
                )
            }

            val stars = progress.stars(i)
            val sr = cell.height() * 0.10f
            val sy0 = cell.bottom - sr * 1.5f
            for (k in 0 until 3) {
                drawStar(
                    canvas,
                    cell.centerX() + (k - 1) * sr * 2.5f,
                    sy0,
                    sr,
                    k < stars
                )
            }
        }
    }

    fun drawRanking(canvas: Canvas, progress: Progress, width: Float, height: Float, time: Float) {
        shakeX = 0f
        shakeY = 0f

        drawSky(canvas, width, height)
        drawSun(canvas, width, height)
        drawClouds(canvas, width, height, time * 4f, height * 0.02f, time)

        val groundY = height * 0.96f
        drawHills(canvas, width, groundY, 0f, height * 0.12f)
        fill.color = GRASS
        canvas.drawRect(0f, groundY, width, height, fill)

        drawText(
            canvas, "排行榜", width / 2f, height * 0.135f,
            height * 0.075f, 0xFF4F3A22.toInt()
        )

        drawBackButton(canvas, Ui.backButton(width, height))

        drawText(
            canvas, "总分 ${progress.totalScore()}", width * 0.32f, height * 0.235f,
            height * 0.040f, 0xFF7A4A22.toInt()
        )
        drawText(
            canvas, "星星 ${progress.totalStars()} / ${Levels.count * 3}",
            width * 0.60f, height * 0.235f,
            height * 0.040f, 0xFF7A4A22.toInt()
        )
        drawText(
            canvas, "金蛋 ${progress.eggCount()} / ${Levels.EGGS_FOR_HIDDEN}",
            width * 0.82f, height * 0.235f,
            height * 0.040f, 0xFF9A6A18.toInt()
        )

        val ranked = (0 until Levels.count).sortedByDescending { progress.bestScore(it) }
        val perCol = (Levels.count + 1) / 2
        val rowH = height * 0.062f
        val top = height * 0.29f
        val barW = width * 0.38f

        for ((pos, level) in ranked.withIndex()) {
            val col = pos / perCol
            val row = pos % perCol
            val x = width * 0.09f + col * width * 0.44f
            val y = top + row * rowH

            fill.color = if (pos % 2 == 0) 0x33FFFFFF else 0x22FFFFFF
            tempRect.set(x, y, x + barW, y + rowH * 0.84f)
            canvas.drawRoundRect(tempRect, rowH * 0.22f, rowH * 0.22f, fill)

            drawText(
                canvas, "${pos + 1}", x + width * 0.018f,
                y + rowH * 0.60f, height * 0.030f, 0xFF7A4A22.toInt(),
                Paint.Align.LEFT
            )

            val spec = Levels.all[level]
            val name = if (level == Levels.hiddenIndex) "金蛋关 · ${spec.name}"
            else "${level + 1}. ${spec.name}"
            drawText(
                canvas, name, x + width * 0.045f,
                y + rowH * 0.60f, height * 0.030f, 0xFF4F3A22.toInt(),
                Paint.Align.LEFT
            )

            drawText(
                canvas, "${progress.bestScore(level)}", x + barW - width * 0.015f,
                y + rowH * 0.60f, height * 0.030f, 0xFF7A4A22.toInt(),
                Paint.Align.RIGHT
            )
        }
    }

    fun drawSettings(
        canvas: Canvas,
        progress: Progress,
        confirmReset: Boolean,
        width: Float,
        height: Float,
        time: Float
    ) {
        shakeX = 0f
        shakeY = 0f

        drawSky(canvas, width, height)
        drawSun(canvas, width, height)
        drawClouds(canvas, width, height, time * 4f, height * 0.02f, time)

        val groundY = height * 0.96f
        drawHills(canvas, width, groundY, 0f, height * 0.12f)
        fill.color = GRASS
        canvas.drawRect(0f, groundY, width, height, fill)

        drawText(
            canvas, "设置", width / 2f, height * 0.135f,
            height * 0.075f, 0xFF4F3A22.toInt()
        )
        drawBackButton(canvas, Ui.backButton(width, height))

        val panel = RectF(width * 0.26f, height * 0.24f, width * 0.74f, height * 0.74f)
        fill.color = 0xFFF7EEDB.toInt()
        canvas.drawRoundRect(panel, height * 0.03f, height * 0.03f, fill)
        stroke.color = 0xFFD9A54B.toInt()
        stroke.strokeWidth = max(2f, height * 0.005f)
        canvas.drawRoundRect(panel, height * 0.03f, height * 0.03f, stroke)

        drawText(
            canvas, "当前进度", width / 2f, height * 0.335f,
            height * 0.042f, 0xFF7A4A22.toInt()
        )
        drawText(
            canvas, "星星 ${progress.totalStars()} / ${Levels.count * 3}",
            width / 2f, height * 0.405f, height * 0.036f, 0xFF4F3A22.toInt()
        )
        drawText(
            canvas, "金蛋 ${progress.eggCount()} / ${Levels.EGGS_FOR_HIDDEN}",
            width / 2f, height * 0.46f, height * 0.036f, 0xFF9A6A18.toInt()
        )

        drawButton(canvas, Ui.clearButton(width, height), "清除所有进度", danger = true)
        drawText(
            canvas, "清除后星级、最高分与金蛋将全部丢失",
            width / 2f, height * 0.68f, height * 0.028f, 0xFF8E877C.toInt()
        )

        if (confirmReset) {
            fill.color = 0x99000000.toInt()
            canvas.drawRect(0f, 0f, width, height, fill)

            val dialog = RectF(width * 0.26f, height * 0.30f, width * 0.74f, height * 0.72f)
            fill.color = 0xFFF7EEDB.toInt()
            canvas.drawRoundRect(dialog, height * 0.03f, height * 0.03f, fill)
            stroke.color = 0xFFC62828.toInt()
            stroke.strokeWidth = max(2f, height * 0.006f)
            canvas.drawRoundRect(dialog, height * 0.03f, height * 0.03f, stroke)

            drawText(
                canvas, "确定清除所有进度？", width / 2f, height * 0.40f,
                height * 0.045f, 0xFF4F3A22.toInt()
            )
            drawText(
                canvas, "此操作无法撤销", width / 2f, height * 0.47f,
                height * 0.032f, 0xFFC62828.toInt()
            )

            drawButton(canvas, Ui.confirmYes(width, height), "确定", danger = true)
            drawButton(canvas, Ui.confirmNo(width, height), "取消")
        }
    }

    // ------------------------------------------------------------------ 场景

    private fun drawSky(canvas: Canvas, width: Float, height: Float) {
        if (skyShader == null || skyShaderHeight != height) {
            skyShader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(SKY_TOP, SKY_MID, SKY_BOTTOM),
                null,
                Shader.TileMode.CLAMP
            )
            skyShaderHeight = height
        }
        skyPaint.shader = skyShader
        canvas.drawRect(0f, 0f, width, height, skyPaint)
    }

    private fun drawSun(canvas: Canvas, width: Float, height: Float) {
        val cx = width * 0.84f
        val cy = height * 0.15f
        val r = height * 0.075f
        fill.color = 0x22FFF3B0
        canvas.drawCircle(cx, cy, r * 2.0f, fill)
        fill.color = 0x55FFEF9A
        canvas.drawCircle(cx, cy, r * 1.5f, fill)
        fill.color = 0xFFFFE59A.toInt()
        canvas.drawCircle(cx, cy, r, fill)
    }

    private fun drawClouds(
        canvas: Canvas,
        width: Float,
        height: Float,
        cameraX: Float,
        ppm: Float,
        time: Float
    ) {
        val span = width + 700f
        for (i in 0 until 5) {
            var x = (i * width * 0.27f - cameraX * ppm * 0.15f) % span
            if (x < 0f) x += span
            x -= 280f
            val y = height * (0.09f + 0.07f * (i % 3)) + sin(time * 0.35f + i) * height * 0.006f
            drawCloud(canvas, x, y, height * 0.042f)
        }
    }

    private fun drawCloud(canvas: Canvas, x: Float, y: Float, r: Float) {
        fill.color = 0xE6FFFFFF.toInt()
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x + r * 1.15f, y + r * 0.18f, r * 0.78f, fill)
        canvas.drawCircle(x - r * 1.10f, y + r * 0.22f, r * 0.68f, fill)
        tempRect.set(x - r * 1.25f, y + r * 0.10f, x + r * 1.45f, y + r * 0.92f)
        canvas.drawRoundRect(tempRect, r * 0.42f, r * 0.42f, fill)
    }

    private fun drawHills(
        canvas: Canvas,
        width: Float,
        groundY: Float,
        offset: Float,
        radius: Float
    ) {
        var x = -((offset % (radius * 1.7f)) + radius * 1.7f) % (radius * 1.7f)
        fill.color = 0xFFA9D49B.toInt()
        while (x < width + radius * 2f) {
            canvas.drawCircle(x + radius * 0.8f, groundY + radius * 0.35f, radius, fill)
            x += radius * 1.7f
        }

        fill.color = 0xFF89C074.toInt()
        val r2 = radius * 0.72f
        var x2 = -(((offset * 0.6f) % (r2 * 2.2f)) + r2 * 2.2f) % (r2 * 2.2f)
        while (x2 < width + r2 * 2f) {
            canvas.drawCircle(x2 + r2, groundY + r2 * 0.45f, r2, fill)
            x2 += r2 * 2.2f
        }
    }

    private fun drawGround(
        canvas: Canvas,
        width: Float,
        height: Float,
        groundY: Float,
        ppm: Float,
        cameraX: Float
    ) {
        fill.color = SOIL
        canvas.drawRect(0f, groundY, width, height, fill)

        fill.color = SOIL_DARK
        val step = ppm * 1.7f
        var gx = -((cameraX * ppm) % step) - step
        var row = 0
        while (gx < width + step) {
            for (i in 0 until 3) {
                val x = gx + i * step * 0.33f
                val y = groundY + height * 0.13f + ((row + i) % 2) * height * 0.09f
                canvas.drawCircle(x, y, max(1f, ppm * 0.035f), fill)
            }
            gx += step
            row++
        }

        fill.color = GRASS
        canvas.drawRect(0f, groundY - ppm * 0.10f, width, groundY + ppm * 0.06f, fill)

        fill.color = GRASS_LIGHT
        val tuft = ppm * 0.85f
        var tx = -((cameraX * ppm) % tuft) - tuft
        while (tx < width + tuft) {
            canvas.drawRect(
                tx, groundY - ppm * 0.20f,
                tx + max(1.5f, ppm * 0.09f), groundY - ppm * 0.05f, fill
            )
            tx += tuft
        }
    }

    private fun drawWaitingBirds(canvas: Canvas, game: Game, time: Float) {
        val waiting = game.waitingBirds
        for (i in waiting.indices) {
            val type = waiting[i]
            val wx = Game.SLING_X - 1.35f - i * 0.82f
            val bob = sin(time * 2f + i * 1.3f) * 0.02f
            drawBird(
                canvas,
                sx(game, wx),
                sy(game, 0.36f + bob),
                0.34f * game.ppm,
                type,
                0f,
                time
            )
        }
    }

    private fun drawSling(canvas: Canvas, game: Game, time: Float) {
        val baseX = sx(game, Game.SLING_X)
        val baseY = sy(game, 0f)
        val forkY = sy(game, 1.55f)

        stroke.color = 0xFF6E4222.toInt()
        stroke.strokeWidth = game.ppm * 0.22f
        canvas.drawLine(baseX, baseY, baseX, forkY, stroke)

        val leftX = sx(game, Game.SLING_X - 0.30f)
        val leftY = sy(game, Game.SLING_Y + 0.15f)
        val rightX = sx(game, Game.SLING_X + 0.30f)
        val rightY = sy(game, Game.SLING_Y + 0.15f)

        stroke.color = 0xFF8A5A2E.toInt()
        stroke.strokeWidth = game.ppm * 0.15f
        canvas.drawLine(baseX, forkY, leftX, leftY, stroke)
        canvas.drawLine(baseX, forkY, rightX, rightY, stroke)

        val birdX = sx(game, game.aimX)
        val birdY = sy(game, game.aimY)
        val r = Game.BIRD_RADIUS * game.ppm

        stroke.color = 0xFF3E2414.toInt()
        stroke.strokeWidth = max(2f, game.ppm * 0.06f)
        canvas.drawLine(leftX, leftY, birdX - r * 0.7f, birdY, stroke)
        canvas.drawLine(rightX, rightY, birdX + r * 0.7f, birdY, stroke)

        val type = game.currentBirdType
        if (type != null) {
            val bob = if (game.state == PlayState.AIM && !game.dragging) {
                sin(time * 2.4f) * r * 0.02f
            } else {
                0f
            }
            val aimAngle = if (game.dragging) {
                -atan2(Game.SLING_Y - game.aimY, Game.SLING_X - game.aimX)
            } else {
                0f
            }
            drawBird(canvas, birdX, birdY + bob, r, type, aimAngle, time)
        }
    }

    /** 拖拽时的抛物线预测点 */
    private fun drawTrajectory(canvas: Canvas, game: Game) {
        if (!game.dragging || game.state != PlayState.AIM) return

        val vx = (Game.SLING_X - game.aimX) * Game.LAUNCH_POWER
        val vy = (Game.SLING_Y - game.aimY) * Game.LAUNCH_POWER

        for (i in 1..9) {
            val t = i * 0.085f
            val wx = game.aimX + vx * t
            val wy = game.aimY + vy * t + 0.5f * Game.GRAVITY * t * t
            if (wy < 0f) break
            fill.color = Color.argb((190 - i * 16).coerceIn(30, 190), 255, 255, 255)
            canvas.drawCircle(
                sx(game, wx),
                sy(game, wy),
                max(1.5f, game.ppm * (0.055f - i * 0.003f)),
                fill
            )
        }
    }

    private fun drawParticles(canvas: Canvas, game: Game) {
        for (p in game.particles) {
            val a = (p.alpha * 255f).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(sx(game, p.x), sy(game, p.y), max(1f, p.size * game.ppm), fill)
        }
    }

    private fun drawPopups(canvas: Canvas, game: Game) {
        for (p in game.popups) {
            val a = (p.alpha * 255f).toInt().coerceIn(0, 255)
            drawText(
                canvas, p.text,
                sx(game, p.x), sy(game, p.y),
                game.ppm * 0.42f,
                Color.argb(a, 255, 255, 255)
            )
        }
    }

    // ------------------------------------------------------------------ HUD

    private fun drawHud(canvas: Canvas, game: Game, width: Float, height: Float, time: Float) {
        val pause = Ui.pauseButton(width, height)
        fill.color = 0x66000000
        canvas.drawRoundRect(pause, height * 0.022f, height * 0.022f, fill)

        val barW = pause.width() * 0.13f
        val barH = pause.height() * 0.42f
        val gap = pause.width() * 0.14f
        fill.color = 0xFFFFFFFF.toInt()
        tempRect.set(
            pause.centerX() - gap - barW, pause.centerY() - barH / 2f,
            pause.centerX() - gap, pause.centerY() + barH / 2f
        )
        canvas.drawRoundRect(tempRect, barW * 0.4f, barW * 0.4f, fill)
        tempRect.set(
            pause.centerX() + gap, pause.centerY() - barH / 2f,
            pause.centerX() + gap + barW, pause.centerY() + barH / 2f
        )
        canvas.drawRoundRect(tempRect, barW * 0.4f, barW * 0.4f, fill)

        drawText(
            canvas, "分数 ${game.score}", pause.right + height * 0.03f,
            pause.centerY() + height * 0.015f, height * 0.038f,
            0xFFFFFFFF.toInt(), Paint.Align.LEFT
        )

        drawText(
            canvas, "剩余小鸟 ×${game.birdsLeft}", pause.right + height * 0.03f,
            pause.centerY() + height * 0.062f, height * 0.027f,
            0xCCFFFFFF.toInt(), Paint.Align.LEFT
        )

        drawText(
            canvas, "第 ${game.levelIndex + 1} 关 · ${game.spec.name}",
            width - height * 0.035f, pause.centerY() + height * 0.015f,
            height * 0.034f, 0xFFFFFFFF.toInt(), Paint.Align.RIGHT
        )

        // 炸弹道具按钮
        val bomb = Ui.bombButton(width, height)
        fill.color = if (game.bombs > 0) 0xD9333333.toInt() else 0x80333333.toInt()
        canvas.drawCircle(bomb.centerX(), bomb.centerY(), bomb.width() * 0.5f, fill)
        stroke.color = if (game.bombAiming) 0xFFFFC107.toInt() else 0x66FFFFFF
        stroke.strokeWidth = max(2f, height * 0.005f)
        canvas.drawCircle(bomb.centerX(), bomb.centerY(), bomb.width() * 0.5f, stroke)

        val br = bomb.width() * 0.20f
        fill.color = if (game.bombs > 0) 0xFF1B1B1B.toInt() else 0xFF555555.toInt()
        canvas.drawCircle(bomb.centerX(), bomb.centerY() + br * 0.12f, br, fill)
        stroke.color = if (game.bombs > 0) 0xFFFFB300.toInt() else 0xFF777777.toInt()
        stroke.strokeWidth = max(2f, height * 0.004f)
        canvas.drawLine(
            bomb.centerX() + br * 0.45f, bomb.centerY() - br * 0.75f,
            bomb.centerX() + br * 1.15f, bomb.centerY() - br * 1.45f, stroke
        )
        drawText(
            canvas, "×${game.bombs}", bomb.right - bomb.width() * 0.06f,
            bomb.bottom - bomb.height() * 0.04f, height * 0.028f,
            0xFFFFFFFF.toInt(), Paint.Align.RIGHT
        )

        if (game.bombAiming) {
            drawText(
                canvas, "点击目标位置投下炸弹", width / 2f, height * 0.90f,
                height * 0.036f, 0xFFFFF176.toInt()
            )
        } else if (game.fastForward) {
            drawText(
                canvas, ">> 快进中", width / 2f, height * 0.90f,
                height * 0.036f, 0xFFFFF176.toInt()
            )
        } else if (game.abilityHint) {
            val tip = when (game.flyingBirdType) {
                BirdType.YELLOW -> "点击屏幕：冲刺加速！"
                BirdType.BLACK -> "点击屏幕：引爆炸弹！"
                else -> ""
            }
            if (tip.isNotEmpty()) {
                val alpha = ((0.6f + 0.4f * sin(time * 4f)) * 255f).toInt().coerceIn(0, 255)
                drawText(
                    canvas, tip, width / 2f, height * 0.90f,
                    height * 0.034f, Color.argb(alpha, 255, 255, 255)
                )
            }
        } else if (game.panHint) {
            drawText(
                canvas, "拖动屏幕查看战场 · 轻点快进", width / 2f, height * 0.90f,
                height * 0.032f, 0xCCFFFFFF.toInt()
            )
        }

        if (game.state == PlayState.AIM && !game.paused && !game.bombAiming &&
            game.currentBirdType != null
        ) {
            val alpha = ((0.55f + 0.45f * sin(time * 2.2f)) * 255f).toInt().coerceIn(0, 255)
            drawText(
                canvas, "按住小鸟向后拖动，松手发射", width / 2f, height * 0.93f,
                height * 0.032f, Color.argb(alpha, 255, 255, 255)
            )
        }
    }

    private fun drawOverlay(
        canvas: Canvas,
        game: Game,
        progress: Progress,
        width: Float,
        height: Float
    ) {
        val won = game.state == PlayState.WON
        val lost = game.state == PlayState.LOST
        if (!game.paused && !won && !lost) return

        fill.color = 0xB3000000.toInt()
        canvas.drawRect(0f, 0f, width, height, fill)

        val panel = Ui.panel(width, height)
        val radius = height * 0.03f
        fill.color = 0xFFFFF6E3.toInt()
        canvas.drawRoundRect(panel, radius, radius, fill)
        stroke.color = 0xFFC08A4E.toInt()
        stroke.strokeWidth = height * 0.008f
        canvas.drawRoundRect(panel, radius, radius, stroke)

        val title = when {
            game.paused -> "游戏暂停"
            won && game.isFinalLevel -> "全部关卡通关！"
            won -> "关卡完成！"
            else -> "挑战失败"
        }
        val titleColor = if (lost) 0xFFB7332A.toInt() else 0xFF6B4A2B.toInt()
        drawText(
            canvas, title, panel.centerX(), panel.top + panel.height() * 0.21f,
            panel.height() * 0.13f, titleColor
        )

        if (won) {
            val starY = panel.top + panel.height() * 0.43f
            val sr = panel.height() * 0.085f
            for (i in 0 until 3) {
                drawStar(
                    canvas,
                    panel.centerX() + (i - 1) * sr * 1.75f,
                    starY,
                    sr,
                    i < game.starsEarned
                )
            }
            drawText(
                canvas, "得分 ${game.score}", panel.centerX(),
                panel.top + panel.height() * 0.61f,
                panel.height() * 0.075f, 0xFF6B4A2B.toInt()
            )
            val best = max(progress.bestScore(game.levelIndex), game.score)
            drawText(
                canvas, "最高分 $best", panel.centerX(),
                panel.top + panel.height() * 0.705f,
                panel.height() * 0.052f, 0xFF9A8468.toInt()
            )
        } else if (lost) {
            drawText(
                canvas, "得分 ${game.score}", panel.centerX(),
                panel.top + panel.height() * 0.45f,
                panel.height() * 0.075f, 0xFF6B4A2B.toInt()
            )
            val best = max(progress.bestScore(game.levelIndex), game.score)
            drawText(
                canvas, "最高分 $best", panel.centerX(),
                panel.top + panel.height() * 0.545f,
                panel.height() * 0.052f, 0xFF9A8468.toInt()
            )
            drawText(
                canvas, "还剩 ${game.pigs.size} 只猪 · 再试一次", panel.centerX(),
                panel.top + panel.height() * 0.64f,
                panel.height() * 0.052f, 0xFF9A8468.toInt()
            )
        } else {
            drawText(
                canvas, "得分 ${game.score}", panel.centerX(),
                panel.top + panel.height() * 0.47f,
                panel.height() * 0.075f, 0xFF6B4A2B.toInt()
            )
            drawText(
                canvas, "剩余小鸟 ×${game.birdsLeft}", panel.centerX(),
                panel.top + panel.height() * 0.58f,
                panel.height() * 0.055f, 0xFF9A8468.toInt()
            )
        }

        val labels = when {
            game.paused -> listOf("继续", "重玩", "选关")
            won -> listOf("重玩", "下一关", "选关")
            else -> listOf("重玩", "选关")
        }
        for (i in labels.indices) {
            val rect = Ui.overlayButton(width, height, i, labels.size)
            val next = game.levelIndex + 1
            val enabled = !(won && i == 1 &&
                (next >= Levels.count || !progress.isUnlocked(next)))
            drawButton(canvas, rect, labels[i], enabled)
        }
    }

    // ------------------------------------------------------------------ 通用 UI

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        enabled: Boolean = true,
        danger: Boolean = false
    ) {
        val radius = rect.height() / 2f
        fill.color = when {
            !enabled -> 0xFFA6A6A6.toInt()
            danger -> 0xFFC62828.toInt()
            else -> 0xFFEE6C2B.toInt()
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        fill.color = 0x33FFFFFF
        tempRect.set(
            rect.left + rect.width() * 0.06f,
            rect.top + rect.height() * 0.10f,
            rect.right - rect.width() * 0.06f,
            rect.centerY()
        )
        canvas.drawRoundRect(tempRect, radius, radius, fill)

        stroke.color = when {
            !enabled -> 0xFF7E7E7E.toInt()
            danger -> 0xFF8E1B1B.toInt()
            else -> 0xFFB54418.toInt()
        }
        stroke.strokeWidth = max(2f, rect.height() * 0.055f)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        drawText(
            canvas, label, rect.centerX(), rect.centerY() + rect.height() * 0.16f,
            rect.height() * 0.42f, 0xFFFFFFFF.toInt()
        )
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.CENTER,
        shadow: Boolean = true
    ) {
        textPaint.typeface = bold
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        if (shadow) {
            textPaint.setShadowLayer(size * 0.16f, 0f, size * 0.10f, 0x70000000)
        } else {
            textPaint.clearShadowLayer()
        }
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawBigTitle(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
        titlePaint.typeface = bold
        titlePaint.textSize = size
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.style = Paint.Style.STROKE
        titlePaint.strokeWidth = size * 0.10f
        titlePaint.strokeJoin = Paint.Join.ROUND
        titlePaint.color = 0xFF7A2C1E.toInt()
        canvas.drawText(text, x, y, titlePaint)

        titlePaint.style = Paint.Style.FILL
        titlePaint.color = 0xFFF0503A.toInt()
        canvas.drawText(text, x, y, titlePaint)
    }

    private fun drawBackButton(canvas: Canvas, rect: RectF) {
        fill.color = 0x55000000
        canvas.drawRoundRect(rect, rect.height() * 0.25f, rect.height() * 0.25f, fill)
        stroke.color = 0xFFFFFFFF.toInt()
        stroke.strokeWidth = rect.height() * 0.09f
        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.drawLine(cx + rect.width() * 0.10f, cy - rect.height() * 0.18f, cx - rect.width() * 0.12f, cy, stroke)
        canvas.drawLine(cx - rect.width() * 0.12f, cy, cx + rect.width() * 0.10f, cy + rect.height() * 0.18f, stroke)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean) {
        starPath.reset()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.45f
            val angle = Math.toRadians((-90 + i * 36).toDouble())
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()

        fill.color = if (filled) 0xFFFFC928.toInt() else 0xFFD8CDBB.toInt()
        canvas.drawPath(starPath, fill)
        stroke.color = if (filled) 0xFFE09A00.toInt() else 0xFFB8AC98.toInt()
        stroke.strokeWidth = max(1.5f, r * 0.10f)
        canvas.drawPath(starPath, stroke)
    }

    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        fill.color = 0xFF7C7C7C.toInt()
        tempRect.set(cx - size * 0.42f, cy - size * 0.05f, cx + size * 0.42f, cy + size * 0.55f)
        canvas.drawRoundRect(tempRect, size * 0.10f, size * 0.10f, fill)

        stroke.color = 0xFF7C7C7C.toInt()
        stroke.strokeWidth = max(2f, size * 0.13f)
        tempRect.set(cx - size * 0.28f, cy - size * 0.50f, cx + size * 0.28f, cy + size * 0.08f)
        canvas.drawArc(tempRect, 180f, 180f, false, stroke)

        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy + size * 0.20f, size * 0.07f, fill)
    }

    // ------------------------------------------------------------------ 实体

    private fun drawBlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        angleRad: Float,
        material: Material,
        damage: Float
    ) {
        canvas.save()
        canvas.rotate(angleRad * RAD_TO_DEG, cx, cy)

        val left = cx - w / 2f
        val top = cy - h / 2f
        val right = cx + w / 2f
        val bottom = cy + h / 2f
        val radius = min(w, h) * 0.18f

        when (material) {
            Material.WOOD -> {
                fill.color = 0xFFCE8C4E.toInt()
                tempRect.set(left, top, right, bottom)
                canvas.drawRoundRect(tempRect, radius, radius, fill)

                stroke.color = 0xFFA26B33.toInt()
                stroke.strokeWidth = max(1.5f, h * 0.05f)
                if (w >= h) {
                    for (i in 1..2) {
                        val y = top + h * i / 3f
                        canvas.drawLine(left + radius, y, right - radius, y, stroke)
                    }
                } else {
                    for (i in 1..2) {
                        val x = left + w * i / 3f
                        canvas.drawLine(x, top + radius, x, bottom - radius, stroke)
                    }
                }

                stroke.color = 0xFF7C4E22.toInt()
                stroke.strokeWidth = max(1.5f, min(w, h) * 0.07f)
                canvas.drawRoundRect(tempRect, radius, radius, stroke)
            }

            Material.ICE -> {
                fill.color = Color.argb(205, 178, 228, 246)
                tempRect.set(left, top, right, bottom)
                canvas.drawRoundRect(tempRect, radius, radius, fill)

                stroke.color = 0xCCFFFFFF.toInt()
                stroke.strokeWidth = max(2f, min(w, h) * 0.06f)
                canvas.drawRoundRect(tempRect, radius, radius, stroke)

                stroke.color = 0x88FFFFFF.toInt()
                stroke.strokeWidth = max(2f, min(w, h) * 0.05f)
                canvas.drawLine(
                    left + w * 0.20f, bottom - h * 0.20f,
                    right - w * 0.25f, top + h * 0.20f, stroke
                )
            }

            Material.STONE -> {
                fill.color = 0xFF9C9C9C.toInt()
                tempRect.set(left, top, right, bottom)
                canvas.drawRoundRect(tempRect, radius, radius, fill)

                fill.color = 0xFF7E7E7E.toInt()
                val dot = max(1f, min(w, h) * 0.045f)
                for (i in 0 until 4) {
                    val fx = left + w * (0.22f + 0.16f * i)
                    val fy = top + h * (if (i % 2 == 0) 0.34f else 0.64f)
                    canvas.drawCircle(fx, fy, dot, fill)
                }

                stroke.color = 0xFF6C6C6C.toInt()
                stroke.strokeWidth = max(1.5f, min(w, h) * 0.06f)
                canvas.drawRoundRect(tempRect, radius, radius, stroke)
            }

            Material.TNT -> {
                fill.color = 0xFFD84315.toInt()
                tempRect.set(left, top, right, bottom)
                canvas.drawRoundRect(tempRect, radius, radius, fill)

                stroke.color = 0xFF8E2A0C.toInt()
                stroke.strokeWidth = max(2f, min(w, h) * 0.07f)
                canvas.drawRoundRect(tempRect, radius, radius, stroke)

                drawText(
                    canvas, "TNT", cx, cy + min(w, h) * 0.18f,
                    min(w, h) * 0.46f, 0xFFFFFFFF.toInt(),
                    Paint.Align.CENTER, shadow = false
                )
            }
        }

        if (damage > 0.33f) {
            stroke.color = Color.argb((damage * 220f).toInt().coerceIn(0, 255), 40, 26, 12)
            stroke.strokeWidth = max(1.5f, min(w, h) * 0.05f)
            canvas.drawLine(left + w * 0.22f, top + h * 0.10f, left + w * 0.46f, top + h * 0.46f, stroke)
            canvas.drawLine(left + w * 0.46f, top + h * 0.46f, left + w * 0.36f, top + h * 0.72f, stroke)
            if (damage > 0.66f) {
                canvas.drawLine(right - w * 0.22f, top + h * 0.14f, right - w * 0.40f, top + h * 0.52f, stroke)
                canvas.drawLine(right - w * 0.40f, top + h * 0.52f, right - w * 0.20f, bottom - h * 0.12f, stroke)
            }
        }

        canvas.restore()
    }

    private fun drawPig(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        damage: Float,
        hitFlash: Float,
        time: Float
    ) {
        val flash = (hitFlash / 0.2f).coerceIn(0f, 1f)
        val x = cx + if (flash > 0f) sin(time * 80f) * r * 0.12f * flash else 0f
        val bob = sin(time * 2.2f + x * 0.01f) * r * 0.03f
        val y = cy + bob

        fill.color = 0xFF6FAF37.toInt()
        canvas.drawCircle(x - r * 0.55f, y - r * 0.74f, r * 0.32f, fill)
        canvas.drawCircle(x + r * 0.55f, y - r * 0.74f, r * 0.32f, fill)

        fill.color = 0xFF86C43F.toInt()
        canvas.drawCircle(x, y, r, fill)

        stroke.color = 0xFF5E8F2B.toInt()
        stroke.strokeWidth = r * 0.08f
        canvas.drawCircle(x, y, r, stroke)

        fill.color = 0xFFA9D96B.toInt()
        tempRect.set(x - r * 0.55f, y + r * 0.05f, x + r * 0.55f, y + r * 0.75f)
        canvas.drawOval(tempRect, fill)

        fill.color = 0xFFB8E180.toInt()
        tempRect.set(x - r * 0.42f, y + r * 0.05f, x + r * 0.42f, y + r * 0.44f)
        canvas.drawOval(tempRect, fill)

        fill.color = 0xFF4C7A22.toInt()
        canvas.drawCircle(x - r * 0.15f, y + r * 0.24f, r * 0.075f, fill)
        canvas.drawCircle(x + r * 0.15f, y + r * 0.24f, r * 0.075f, fill)

        // 眼白
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x - r * 0.32f, y - r * 0.28f, r * 0.20f, fill)
        canvas.drawCircle(x + r * 0.32f, y - r * 0.28f, r * 0.20f, fill)

        if (damage > 0.72f) {
            // 重伤：X 眼
            stroke.color = 0xFF1B1B1B.toInt()
            stroke.strokeWidth = r * 0.085f
            val e = r * 0.11f
            for (s in -1..1 step 2) {
                val ex = x + s * r * 0.32f
                val ey = y - r * 0.28f
                canvas.drawLine(ex - e, ey - e, ex + e, ey + e, stroke)
                canvas.drawLine(ex - e, ey + e, ex + e, ey - e, stroke)
            }
        } else {
            fill.color = 0xFF1B1B1B.toInt()
            val shrink = 1f - damage * 0.35f
            canvas.drawCircle(x - r * 0.28f, y - r * 0.26f, r * 0.09f * shrink, fill)
            canvas.drawCircle(x + r * 0.36f, y - r * 0.26f, r * 0.09f * shrink, fill)
        }

        stroke.color = 0xFF3E5E1A.toInt()
        stroke.strokeWidth = r * 0.085f
        canvas.drawLine(x - r * 0.56f, y - r * 0.56f, x - r * 0.18f, y - r * 0.42f, stroke)
        canvas.drawLine(x + r * 0.56f, y - r * 0.56f, x + r * 0.18f, y - r * 0.42f, stroke)

        if (damage > 0f) {
            fill.color = Color.argb((damage * 120f).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(x, y, r, fill)
        }

        // 受击瞬间闪白
        if (flash > 0f) {
            fill.color = Color.argb((flash * 190f).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(x, y, r, fill)
        }

        // 重伤时头顶转圈的小星星
        if (damage > 0.72f) {
            for (k in 0 until 3) {
                val a = time * 3.2f + k * 2.094f
                drawStar(
                    canvas,
                    x + cos(a) * r * 0.95f,
                    y - r * 1.15f + sin(a) * r * 0.28f,
                    r * 0.13f,
                    true
                )
            }
        }
    }

    private fun drawEgg(canvas: Canvas, cx: Float, cy: Float, r: Float, time: Float) {
        val bob = sin(time * 2.4f + cx * 0.02f) * r * 0.12f
        val y = cy + bob

        fill.color = 0x33FFD54F
        canvas.drawCircle(cx, y, r * 2.1f, fill)
        fill.color = 0x55FFD54F
        canvas.drawCircle(cx, y, r * 1.5f, fill)

        tempRect.set(cx - r, y - r * 1.15f, cx + r, y + r * 1.15f)
        fill.color = 0xFFFFD54F.toInt()
        canvas.drawOval(tempRect, fill)

        stroke.color = 0xFFC79A28.toInt()
        stroke.strokeWidth = r * 0.1f
        canvas.drawOval(tempRect, stroke)

        fill.color = 0xCCFFFFFF.toInt()
        tempRect.set(cx - r * 0.45f, y - r * 0.75f, cx - r * 0.05f, y - r * 0.15f)
        canvas.drawOval(tempRect, fill)
    }

    private fun drawBird(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        type: BirdType,
        angleRad: Float,
        time: Float
    ) {
        canvas.save()
        canvas.rotate(angleRad * RAD_TO_DEG, cx, cy)
        when (type) {
            BirdType.RED -> drawRedBird(canvas, cx, cy, r)
            BirdType.YELLOW -> drawYellowBird(canvas, cx, cy, r)
            BirdType.BLACK -> drawBlackBird(canvas, cx, cy, r)
        }
        canvas.restore()
    }

    private fun drawRedBird(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0xFFC22F25.toInt()
        tempPath.reset()
        tempPath.moveTo(cx - r * 0.80f, cy - r * 0.20f)
        tempPath.lineTo(cx - r * 1.55f, cy - r * 0.55f)
        tempPath.lineTo(cx - r * 1.35f, cy + r * 0.12f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        tempPath.reset()
        tempPath.moveTo(cx - r * 0.85f, cy + r * 0.05f)
        tempPath.lineTo(cx - r * 1.50f, cy + r * 0.28f)
        tempPath.lineTo(cx - r * 1.20f, cy + r * 0.55f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        fill.color = 0xFFE23B30.toInt()
        canvas.drawCircle(cx, cy, r, fill)

        fill.color = 0xFFF7CFC4.toInt()
        tempRect.set(cx - r * 0.52f, cy + r * 0.10f, cx + r * 0.60f, cy + r * 0.92f)
        canvas.drawOval(tempRect, fill)

        fill.color = 0xFFC22F25.toInt()
        tempPath.reset()
        tempPath.moveTo(cx - r * 0.26f, cy - r * 0.90f)
        tempPath.lineTo(cx - r * 0.44f, cy - r * 1.36f)
        tempPath.lineTo(cx + r * 0.02f, cy - r * 1.02f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        tempPath.reset()
        tempPath.moveTo(cx + r * 0.08f, cy - r * 0.98f)
        tempPath.lineTo(cx + r * 0.18f, cy - r * 1.42f)
        tempPath.lineTo(cx + r * 0.44f, cy - r * 0.92f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx - r * 0.26f, cy - r * 0.28f, r * 0.30f, fill)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.28f, r * 0.30f, fill)

        fill.color = 0xFF1B1B1B.toInt()
        canvas.drawCircle(cx - r * 0.20f, cy - r * 0.26f, r * 0.13f, fill)
        canvas.drawCircle(cx + r * 0.40f, cy - r * 0.26f, r * 0.13f, fill)

        stroke.color = 0xFF4A0F0C.toInt()
        stroke.strokeWidth = r * 0.14f
        canvas.drawLine(cx - r * 0.72f, cy - r * 0.70f, cx - r * 0.08f, cy - r * 0.50f, stroke)
        canvas.drawLine(cx + r * 0.78f, cy - r * 0.68f, cx + r * 0.14f, cy - r * 0.48f, stroke)

        fill.color = 0xFFF5A21E.toInt()
        tempPath.reset()
        tempPath.moveTo(cx + r * 0.30f, cy - r * 0.04f)
        tempPath.lineTo(cx + r * 1.18f, cy + r * 0.18f)
        tempPath.lineTo(cx + r * 0.30f, cy + r * 0.44f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        fill.color = 0x44FFFFFF
        canvas.drawCircle(cx - r * 0.42f, cy - r * 0.45f, r * 0.18f, fill)
    }

    private fun drawYellowBird(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0xFFD9A21A.toInt()
        tempPath.reset()
        tempPath.moveTo(cx - r * 0.80f, cy + r * 0.35f)
        tempPath.lineTo(cx - r * 1.45f, cy + r * 0.55f)
        tempPath.lineTo(cx - r * 0.95f, cy + r * 0.85f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        fill.color = 0xFFF8C62C.toInt()
        tempPath.reset()
        tempPath.moveTo(cx - r * 0.92f, cy + r * 0.78f)
        tempPath.lineTo(cx + r * 0.95f, cy + r * 0.30f)
        tempPath.lineTo(cx - r * 0.10f, cy - r * 1.05f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        stroke.color = 0xFFD9A21A.toInt()
        stroke.strokeWidth = r * 0.10f
        canvas.drawPath(tempPath, stroke)

        fill.color = 0xFFFDE9A6.toInt()
        tempRect.set(cx - r * 0.55f, cy + r * 0.16f, cx + r * 0.45f, cy + r * 0.72f)
        canvas.drawOval(tempRect, fill)

        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx - r * 0.16f, cy - r * 0.28f, r * 0.22f, fill)
        canvas.drawCircle(cx + r * 0.30f, cy - r * 0.22f, r * 0.20f, fill)

        fill.color = 0xFF1B1B1B.toInt()
        canvas.drawCircle(cx - r * 0.12f, cy - r * 0.26f, r * 0.10f, fill)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.20f, r * 0.09f, fill)

        stroke.color = 0xFF8A5A0A.toInt()
        stroke.strokeWidth = r * 0.12f
        canvas.drawLine(cx - r * 0.50f, cy - r * 0.62f, cx - r * 0.04f, cy - r * 0.46f, stroke)
        canvas.drawLine(cx + r * 0.55f, cy - r * 0.55f, cx + r * 0.10f, cy - r * 0.40f, stroke)

        fill.color = 0xFFF08A1E.toInt()
        tempPath.reset()
        tempPath.moveTo(cx + r * 0.40f, cy + r * 0.02f)
        tempPath.lineTo(cx + r * 1.20f, cy + r * 0.24f)
        tempPath.lineTo(cx + r * 0.42f, cy + r * 0.48f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)
    }

    private fun drawBlackBird(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0xFF2B2B2B.toInt()
        canvas.drawCircle(cx, cy, r, fill)

        stroke.color = 0xFF111111.toInt()
        stroke.strokeWidth = r * 0.09f
        canvas.drawCircle(cx, cy, r, stroke)

        fill.color = 0xFF4A4A4A.toInt()
        tempRect.set(cx - r * 0.45f, cy + r * 0.15f, cx + r * 0.55f, cy + r * 0.85f)
        canvas.drawOval(tempRect, fill)

        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx - r * 0.26f, cy - r * 0.26f, r * 0.26f, fill)
        canvas.drawCircle(cx + r * 0.30f, cy - r * 0.26f, r * 0.26f, fill)

        fill.color = 0xFF1B1B1B.toInt()
        canvas.drawCircle(cx - r * 0.20f, cy - r * 0.24f, r * 0.11f, fill)
        canvas.drawCircle(cx + r * 0.36f, cy - r * 0.24f, r * 0.11f, fill)

        stroke.color = 0xFF000000.toInt()
        stroke.strokeWidth = r * 0.13f
        canvas.drawLine(cx - r * 0.70f, cy - r * 0.62f, cx - r * 0.10f, cy - r * 0.44f, stroke)
        canvas.drawLine(cx + r * 0.74f, cy - r * 0.60f, cx + r * 0.14f, cy - r * 0.42f, stroke)

        fill.color = 0xFFF5A21E.toInt()
        tempPath.reset()
        tempPath.moveTo(cx + r * 0.28f, cy - r * 0.02f)
        tempPath.lineTo(cx + r * 1.05f, cy + r * 0.18f)
        tempPath.lineTo(cx + r * 0.28f, cy + r * 0.42f)
        tempPath.close()
        canvas.drawPath(tempPath, fill)

        stroke.color = 0xFF555555.toInt()
        stroke.strokeWidth = r * 0.10f
        canvas.drawLine(cx + r * 0.05f, cy - r * 0.95f, cx + r * 0.28f, cy - r * 1.35f, stroke)

        fill.color = 0xFFFFD54F.toInt()
        canvas.drawCircle(cx + r * 0.30f, cy - r * 1.40f, r * 0.12f, fill)
    }

    // ------------------------------------------------------------------ 坐标

    private fun sx(game: Game, wx: Float): Float =
        (wx - game.cameraX) * game.ppm + game.viewWidth / 2f + shakeX

    private fun sy(game: Game, wy: Float): Float =
        game.viewHeight / 2f - (wy - game.cameraY) * game.ppm + shakeY

    private companion object {
        private const val RAD_TO_DEG = 57.29578f

        private val SKY_TOP = 0xFF6FC8EC.toInt()
        private val SKY_MID = 0xFFB7E7F6.toInt()
        private val SKY_BOTTOM = 0xFFEFF9DC.toInt()

        private val GRASS = 0xFF5FAE3C.toInt()
        private val GRASS_LIGHT = 0xFF7CC94F.toInt()
        private val SOIL = 0xFF8A5A2B.toInt()
        private val SOIL_DARK = 0xFF734720.toInt()
    }
}
