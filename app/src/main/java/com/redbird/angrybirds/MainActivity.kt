package com.redbird.angrybirds

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 应用唯一入口 Activity。
 *
 * 职责：
 * 1. 沉浸式全屏（隐藏状态栏 / 导航栏）
 * 2. 横屏、屏幕常亮
 * 3. 承载并管理 [GameView] 的生命周期
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gameView = GameView(this)
        setContentView(gameView)

        // 必须在 setContentView 之后调用：此时 DecorView 才已创建，
        // 否则 window.insetsController 会抛 NullPointerException（Android 14+）。
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        gameView.resume()
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onBackPressed() {
        if (!gameView.onBack()) super.onBackPressed()
    }
}
