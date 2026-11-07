package com.redbird.angrybirds

import org.jbox2d.dynamics.Body

/**
 * 实体与基础枚举定义。
 *
 * Entity 把 Box2D 的 Body 与游戏语义（血量、材质、计分）绑定起来。
 * 位置/角度直接读取物理刚体，渲染层每帧取最新值即可。
 */
enum class EntityType {
    BIRD,
    PIG,
    BLOCK,
    EGG,
    GROUND
}

enum class Material {
    WOOD,
    ICE,
    STONE,
    TNT
}

enum class BirdType {
    RED,     // 基础红鸟
    YELLOW,  // 飞行中点击加速
    BLACK    // 飞行中点击 / 落地爆炸
}

/**
 * 关卡内的回合状态。
 */
enum class PlayState {
    AIM,      // 小鸟挂在弹弓上，可拖拽瞄准
    FLYING,   // 小鸟飞行中
    SETTLE,   // 已发射，等待场景静止后切换下一只
    WON,
    LOST
}

class Entity(
    val type: EntityType,
    val body: Body,
    var material: Material = Material.WOOD,
    var width: Float = 0f,
    var height: Float = 0f,
    var radius: Float = 0f,
    var maxHealth: Float = 1f,
    var health: Float = 1f,
    val birdType: BirdType? = null
) {
    /** 技能是否已使用（黄鸟加速 / 黑鸟爆炸） */
    var abilityUsed = false

    /** 已排队等待销毁，避免重复结算 */
    var removed = false

    /** 受击闪烁计时（秒），渲染层用于抖动/闪白动画 */
    var hitFlash = 0f

    val x: Float get() = body.position.x
    val y: Float get() = body.position.y
    val angle: Float get() = body.angle

    /** 0 = 完好，1 = 濒临破坏（渲染裂纹强度） */
    val damage: Float
        get() = if (maxHealth <= 0f) 0f else (1f - health / maxHealth).coerceIn(0f, 1f)
}

/**
 * 命中 / 破坏 / 爆炸粒子。
 */
class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    var size: Float,
    var color: Int
) {
    val alpha: Float get() = (life / maxLife).coerceIn(0f, 1f)
}

/**
 * 得分飘字。
 */
class Popup(
    var x: Float,
    var y: Float,
    val text: String,
    var life: Float,
    val maxLife: Float
) {
    val alpha: Float get() = (life / maxLife).coerceIn(0f, 1f)
}
