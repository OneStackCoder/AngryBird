package com.redbird.angrybirds

import android.content.Context
import kotlin.math.min

/**
 * 本地进度存储（SharedPreferences）。
 *
 * 记录：已解锁关卡数、每关星级、每关最高分、是否通关。
 */
class Progress(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("angry_birds_progress", Context.MODE_PRIVATE)

    fun isUnlocked(level: Int): Boolean {
        if (level == Levels.hiddenIndex) {
            return eggCount() >= Levels.EGGS_FOR_HIDDEN
        }
        return level >= 0 && level < Levels.hiddenIndex &&
            level < prefs.getInt(KEY_UNLOCKED, 1)
    }

    fun stars(level: Int): Int = prefs.getInt("stars_$level", 0)

    fun bestScore(level: Int): Int = prefs.getInt("best_$level", 0)

    /** 是否已收集该关的金蛋 */
    fun hasEgg(level: Int): Boolean = prefs.getBoolean("egg_$level", false)

    fun collectEgg(level: Int) {
        if (level < 0 || level >= Levels.count) return
        prefs.edit().putBoolean("egg_$level", true).apply()
    }

    /** 已收集金蛋总数 */
    fun eggCount(): Int {
        var sum = 0
        for (i in 0 until Levels.count) if (hasEgg(i)) sum++
        return sum
    }

    fun recordResult(level: Int, isWin: Boolean, stars: Int, score: Int) {
        if (level < 0 || level >= Levels.count) return
        val editor = prefs.edit()

        if (score > bestScore(level)) {
            editor.putInt("best_$level", score)
        }
        if (isWin && stars > stars(level)) {
            editor.putInt("stars_$level", stars)
        }
        editor.apply()

        if (isWin) {
            val unlocked = prefs.getInt(KEY_UNLOCKED, 1)
            // 隐藏关不参与顺序解锁，最多解锁到第 12 关
            val needed = min(level + 2, Levels.hiddenIndex)
            if (needed > unlocked) {
                prefs.edit().putInt(KEY_UNLOCKED, needed).apply()
            }
        }
    }

    fun totalStars(): Int {
        var sum = 0
        for (i in 0 until Levels.count) sum += stars(i)
        return sum
    }

    /** 所有关卡最高分之和（本地排行榜用） */
    fun totalScore(): Int {
        var sum = 0
        for (i in 0 until Levels.count) sum += bestScore(i)
        return sum
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_UNLOCKED = "unlocked_count"
    }
}
