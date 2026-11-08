package com.redbird.angrybirds

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * 游戏音效事件。
 * 由 [Game] 产生，[GameView] 在渲染线程消费后交给 [GameAudio] / [Haptics]。
 */
enum class GameSound {
    LAUNCH,
    WOOD,
    ICE,
    STONE,
    PIG,
    PIG_OINK,
    EXPLODE,
    GOLD,
    WIN,
    LOSE
}

/**
 * 纯代码合成的音效（不依赖任何音频资源文件）。
 *
 * 每种音效预生成一段 PCM，写入 MODE_STATIC 的 [AudioTrack]；
 * 播放时若同一音效仍在播放则直接忽略，避免叠加爆音。
 */
class GameAudio {

    private companion object {
        const val SAMPLE_RATE = 22050
        const val MASTER = 0.42f
        val TAU = (PI * 2.0).toFloat()
    }

    private class Voice(val track: AudioTrack, val frames: Int)

    private val voices = HashMap<GameSound, Voice>()
    private var seed = 987654321

    init {
        build(GameSound.LAUNCH, 0.22f) { t, p, n ->
            val f = 420f + 980f * p
            val env = (1f - p) * (1f - p)
            (sin(TAU * f * t) * 0.7f + n * 0.25f) * env
        }

        build(GameSound.WOOD, 0.13f) { t, _, n ->
            val env = exp(-22f * t)
            (sin(TAU * 190f * t) * 0.6f + n * 0.8f) * env
        }

        build(GameSound.ICE, 0.19f) { t, _, n ->
            val env = exp(-15f * t)
            (sin(TAU * 1750f * t) * 0.45f +
                sin(TAU * 2650f * t) * 0.3f + n * 0.22f) * env
        }

        build(GameSound.STONE, 0.17f) { t, _, n ->
            val env = exp(-18f * t)
            (sin(TAU * 82f * t) * 0.8f + n * 0.7f) * env
        }

        build(GameSound.PIG, 0.22f) { t, p, n ->
            val f = 620f - 320f * p
            val env = sin(PI.toFloat() * p.coerceIn(0f, 1f))
            (sin(TAU * f * t) * 0.85f + n * 0.1f) * env
        }

        // 猪"哼哼"两声：受击 / 闲置时发出，区别于死亡的 PIG（啵）。
        // 基频提高到 349/311Hz 并加强 2.5 倍共振峰——小扬声器低频还原差，
        // 之前的 292Hz 低嗡声在实际设备上几乎听不见。
        build(GameSound.PIG_OINK, 0.34f) { t, p, n ->
            val phase = when {
                p < 0.36f -> p / 0.36f
                p >= 0.56f -> (p - 0.56f) / 0.44f
                else -> -1f
            }
            if (phase < 0f) {
                0f
            } else {
                val f = if (p < 0.5f) 349f else 311f
                val env = sin(PI.toFloat() * phase)
                (sin(TAU * f * t) * 0.85f +
                    sin(TAU * f * 2.5f * t) * 0.35f +
                    n * 0.18f) * env
            }
        }

        build(GameSound.EXPLODE, 0.5f) { t, _, n ->
            val env = exp(-7f * t)
            (n * 0.9f + sin(TAU * 58f * t) * 0.6f) * env
        }

        build(GameSound.GOLD, 0.32f) { t, p, n ->
            val f = if (p < 0.45f) 1318.5f else 1760f
            val env = if (p < 0.45f) (1f - p / 0.45f) else (1f - (p - 0.45f) / 0.55f)
            (sin(TAU * f * t) * 0.55f + sin(TAU * f * 2f * t) * 0.2f + n * 0.03f) * env
        }

        build(GameSound.WIN, 0.56f) { t, p, _ ->
            val f = when {
                p < 0.3f -> 523.25f
                p < 0.6f -> 659.25f
                else -> 783.99f
            }
            val local = when {
                p < 0.3f -> p / 0.3f
                p < 0.6f -> (p - 0.3f) / 0.3f
                else -> (p - 0.6f) / 0.4f
            }
            val env = if (local < 0.12f) local / 0.12f else exp(-3.2f * (local - 0.12f))
            (sin(TAU * f * t) * 0.58f + sin(TAU * f * 2f * t) * 0.14f) * env
        }

        build(GameSound.LOSE, 0.6f) { t, p, _ ->
            val f = when {
                p < 0.33f -> 392f
                p < 0.66f -> 329.63f
                else -> 261.63f
            }
            val env = (1f - p) * 0.9f
            sin(TAU * f * t) * 0.55f * env
        }
    }

    fun play(sound: GameSound) {
        val voice = voices[sound] ?: return
        val track = voice.track
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < voice.frames
            ) {
                return
            }
            track.stop()
            track.setPlaybackHeadPosition(0)
            track.play()
        } catch (_: Exception) {
            // 设备音频不可用时静默降级
        }
    }

    fun release() {
        for (voice in voices.values) {
            try {
                voice.track.release()
            } catch (_: Exception) {
                // 忽略
            }
        }
        voices.clear()
    }

    // ------------------------------------------------------------ 合成

    private fun build(sound: GameSound, duration: Float, gen: (Float, Float, Float) -> Float) {
        val frames = max(1, (SAMPLE_RATE * duration).toInt())
        val data = ShortArray(frames)
        seed = 987654321

        for (i in 0 until frames) {
            val t = i.toFloat() / SAMPLE_RATE
            val p = i.toFloat() / frames
            val noise = nextNoise()
            val value = gen(t, p, noise) * MASTER
            data[i] = (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bytes = max(data.size * 2, if (minBytes > 0) minBytes else 4096)

        val track = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bytes,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (_: Exception) {
            null
        } ?: return

        try {
            track.write(data, 0, data.size)
        } catch (_: Exception) {
            try {
                track.release()
            } catch (_: Exception) {
                // 忽略
            }
            return
        }
        voices[sound] = Voice(track, frames)
    }

    /** 简单线性同余噪声源，范围 -1..1 */
    private fun nextNoise(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed shr 16) and 0x7FFF) / 16384f - 1f
    }
}

/**
 * 震动反馈。使用 VibrationEffect（API 26+），带最小间隔节流避免连续破坏时抖动过密。
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastVibrate = 0L

    fun play(sound: GameSound) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return

        val duration: Long
        val amplitude: Int
        when (sound) {
            GameSound.WOOD -> { duration = 12L; amplitude = 90 }
            GameSound.ICE -> { duration = 10L; amplitude = 70 }
            GameSound.STONE -> { duration = 16L; amplitude = 120 }
            GameSound.PIG -> { duration = 20L; amplitude = 140 }
            GameSound.PIG_OINK -> return // 哼哼声不震动
            GameSound.EXPLODE -> { duration = 55L; amplitude = 220 }
            GameSound.GOLD -> { duration = 18L; amplitude = 110 }
            GameSound.WIN -> { duration = 90L; amplitude = 180 }
            GameSound.LOSE -> { duration = 120L; amplitude = 140 }
            GameSound.LAUNCH -> return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastVibrate < 55L) return
        lastVibrate = now

        try {
            device.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } catch (_: Exception) {
            // 忽略不支持震动的设备
        }
    }
}
