package com.redbird.angrybirds

import android.graphics.RectF

/**
 * 屏幕空间 UI 布局。
 * 渲染与点击判定共用同一套矩形，避免两处坐标不一致。
 */
object Ui {

    /** 左上角暂停按钮 */
    fun pauseButton(width: Float, height: Float): RectF {
        val size = height * 0.085f
        val margin = height * 0.03f
        return RectF(margin, margin, margin + size, margin + size)
    }

    /** 标题页「开始游戏」按钮 */
    fun playButton(width: Float, height: Float): RectF {
        val bw = width * 0.28f
        val bh = height * 0.135f
        val cx = width / 2f
        val top = height * 0.44f
        return RectF(cx - bw / 2f, top, cx + bw / 2f, top + bh)
    }

    /** 选关页返回按钮 */
    fun backButton(width: Float, height: Float): RectF {
        val size = height * 0.09f
        val margin = height * 0.03f
        return RectF(margin, margin, margin + size, margin + size)
    }

    /** 覆盖层面板 */
    fun panel(width: Float, height: Float): RectF {
        val pw = width * 0.70f
        val ph = height * 0.64f
        val cx = width / 2f
        val cy = height / 2f
        return RectF(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f)
    }

    /** 覆盖层按钮：index 从 0 开始，count 为本行按钮总数 */
    fun overlayButton(width: Float, height: Float, index: Int, count: Int): RectF {
        val p = panel(width, height)
        val gap = p.width() * 0.035f
        val available = p.width() * 0.86f
        val bw = (available - gap * (count - 1)) / count
        val bh = height * 0.10f
        val totalW = bw * count + gap * (count - 1)
        val startX = p.centerX() - totalW / 2f
        val top = p.bottom - bh - p.height() * 0.08f
        val left = startX + index * (bw + gap)
        return RectF(left, top, left + bw, top + bh)
    }

    /** 游戏内炸弹道具按钮（左下角） */
    fun bombButton(width: Float, height: Float): RectF {
        val size = height * 0.085f
        val margin = height * 0.03f
        return RectF(margin, height - margin - size, margin + size, height - margin)
    }

    /** 标题页「排行榜」按钮（左上角） */
    fun rankButton(width: Float, height: Float): RectF {
        val bw = width * 0.17f
        val bh = height * 0.10f
        val margin = height * 0.03f
        return RectF(margin, margin, margin + bw, margin + bh)
    }

    /** 标题页「设置」按钮（右上角） */
    fun settingsButton(width: Float, height: Float): RectF {
        val bw = width * 0.17f
        val bh = height * 0.10f
        val margin = height * 0.03f
        return RectF(width - margin - bw, margin, width - margin, margin + bh)
    }

    /** 设置页「清除所有进度」按钮 */
    fun clearButton(width: Float, height: Float): RectF =
        RectF(width * 0.36f, height * 0.53f, width * 0.64f, height * 0.63f)

    /** 清除进度确认弹窗的「确定」 */
    fun confirmYes(width: Float, height: Float): RectF =
        RectF(width * 0.31f, height * 0.55f, width * 0.47f, height * 0.64f)

    /** 清除进度确认弹窗的「取消」 */
    fun confirmNo(width: Float, height: Float): RectF =
        RectF(width * 0.53f, height * 0.55f, width * 0.69f, height * 0.64f)

    /** 选关页第 index 个关卡格子（5 列 x 3 行，含隐藏关） */
    fun levelCell(width: Float, height: Float, index: Int): RectF {
        val cols = 5
        val rows = 3
        val left = width * 0.09f
        val right = width * 0.91f
        val top = height * 0.24f
        val bottom = height * 0.88f
        val gapX = width * 0.022f
        val gapY = height * 0.035f
        val cw = (right - left - gapX * (cols - 1)) / cols
        val ch = (bottom - top - gapY * (rows - 1)) / rows
        val col = index % cols
        val row = index / cols
        val x = left + col * (cw + gapX)
        val y = top + row * (ch + gapY)
        return RectF(x, y, x + cw, y + ch)
    }
}
