package com.redbird.angrybirds

/**
 * 关卡数据与关卡表。
 */
data class BlockSpec(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val material: Material
)

data class PigSpec(
    val x: Float,
    val y: Float,
    val radius: Float
)

data class EggSpec(
    val x: Float,
    val y: Float
)

data class LevelSpec(
    val name: String,
    val birds: List<BirdType>,
    val blocks: List<BlockSpec>,
    val pigs: List<PigSpec>,
    val width: Float,
    val star2: Int,
    val star3: Int,
    val egg: EggSpec? = null
)

object Levels {

    private val RED = BirdType.RED
    private val YELLOW = BirdType.YELLOW
    private val BLACK = BirdType.BLACK

    val all: List<LevelSpec> = listOf(
        level1(), level2(), level3(), level4(),
        level5(), level6(), level7(), level8(),
        level9(), level10(), level11(), level12(),
        hidden()
    )

    val count: Int get() = all.size

    /** 隐藏关索引（列表最后一项） */
    val hiddenIndex: Int get() = all.size - 1

    /** 解锁隐藏关所需的金蛋数量 */
    const val EGGS_FOR_HIDDEN = 3

    // ---------------------------------------------------------------- 关卡

    private fun level1() = Builder("绿野初鸣", 40f, 15000, 24000).apply {
        col(19.0f, 0f, 2.4f)
        col(21.4f, 0f, 2.4f)
        beam(20.2f, 2.4f, 2.8f)
        pig(20.2f)
        pig(20.2f, 2.7f)
        egg(23.6f, 2.2f)
        birds(RED, RED, RED)
    }.build()

    private fun level2() = Builder("双子木塔", 46f, 20000, 30000).apply {
        col(18.0f, 0f, 2.4f)
        col(20.0f, 0f, 2.4f)
        beam(19.0f, 2.4f, 2.6f)
        pig(19.0f)
        pig(19.0f, 2.7f)

        col(24.5f, 0f, 2.4f)
        col(26.5f, 0f, 2.4f)
        beam(25.5f, 2.4f, 2.6f)
        pig(25.5f, 2.7f)

        birds(RED, YELLOW, RED)
    }.build()

    private fun level3() = Builder("冰晶小屋", 46f, 24000, 34000).apply {
        col(18.5f, 0f, 2.5f, Material.ICE)
        col(21.5f, 0f, 2.5f, Material.ICE)
        beam(20.0f, 2.5f, 3.4f, Material.ICE)

        col(19.2f, 2.8f, 1.6f)
        col(20.8f, 2.8f, 1.6f)
        beam(20.0f, 4.4f, 2.2f)

        pig(20.0f)
        pig(20.0f, 2.8f)
        egg(23.8f, 2.8f)
        birds(RED, RED, YELLOW)
    }.build()

    private fun level4() = Builder("岩石堡垒", 50f, 26000, 37000).apply {
        col(18.0f, 0f, 2.5f, Material.STONE, 0.4f)
        col(22.0f, 0f, 2.5f, Material.STONE, 0.4f)
        beam(20.0f, 2.5f, 4.4f, Material.STONE, 0.4f)

        col(19.0f, 2.9f, 1.4f)
        col(21.0f, 2.9f, 1.4f)
        beam(20.0f, 4.3f, 2.6f)

        box(23.4f, 0f, 0.8f, 1.2f, Material.STONE)
        pig(24.6f, 0f)

        pig(20.0f)
        pig(20.0f, 2.9f)
        birds(RED, BLACK, YELLOW)
    }.build()

    private fun level5() = Builder("高塔惊魂", 52f, 30000, 42000).apply {
        col(19.6f, 0f, 2.2f)
        col(22.4f, 0f, 2.2f)
        beam(21.0f, 2.2f, 3.0f)

        col(19.9f, 2.5f, 1.6f, Material.ICE)
        col(22.1f, 2.5f, 1.6f, Material.ICE)
        beam(21.0f, 4.1f, 2.6f)

        box(17.4f, 0f, 0.7f, 0.7f, Material.TNT)

        pig(21.0f)
        pig(21.0f, 2.5f)
        pig(21.0f, 4.4f)
        egg(24.8f, 3.2f)
        birds(RED, YELLOW, BLACK)
    }.build()

    private fun level6() = Builder("双峰对峙", 54f, 32000, 45000).apply {
        col(17.0f, 0f, 2.4f)
        col(19.0f, 0f, 2.4f)
        beam(18.0f, 2.4f, 2.6f)
        pig(18.0f)
        pig(18.0f, 2.7f)

        col(26.0f, 0f, 2.4f)
        col(28.0f, 0f, 2.4f)
        beam(27.0f, 2.4f, 2.6f)
        pig(27.0f)
        pig(27.0f, 2.7f)

        box(22.0f, 0f, 0.8f, 0.8f, Material.TNT)
        box(22.0f, 0.8f, 0.8f, 0.8f)

        birds(RED, YELLOW, BLACK, RED)
    }.build()

    private fun level7() = Builder("石墙之后", 54f, 32000, 45000).apply {
        box(16.5f, 0f, 0.9f, 1.6f, Material.STONE)
        box(16.5f, 1.6f, 0.9f, 1.6f, Material.STONE)

        col(21.0f, 0f, 2.0f)
        col(23.4f, 0f, 2.0f)
        beam(22.2f, 2.0f, 2.8f)

        box(24.2f, 0f, 0.8f, 1.4f)
        pig(25.4f, 0f)

        pig(22.2f)
        pig(22.2f, 2.3f)
        birds(RED, BLACK, YELLOW)
    }.build()

    private fun level8() = Builder("冰晶之城", 56f, 34000, 47000).apply {
        col(19.0f, 0f, 2.6f, Material.ICE)
        col(22.0f, 0f, 2.6f, Material.ICE)
        beam(20.5f, 2.6f, 3.6f, Material.ICE)

        col(19.4f, 2.9f, 1.8f, Material.ICE)
        col(21.6f, 2.9f, 1.8f, Material.ICE)
        beam(20.5f, 4.7f, 2.8f, Material.ICE)

        col(27.0f, 0f, 2.0f)
        col(29.0f, 0f, 2.0f)
        beam(28.0f, 2.0f, 2.6f)

        box(25.6f, 0f, 0.7f, 0.7f, Material.TNT)

        pig(20.5f)
        pig(20.5f, 2.9f)
        pig(28.0f, 2.3f)
        egg(25.0f, 4.0f)
        birds(RED, YELLOW, RED, BLACK)
    }.build()

    private fun level9() = Builder("悬空平台", 58f, 36000, 50000).apply {
        col(18.0f, 0f, 3.0f, Material.STONE, 0.4f)
        col(22.0f, 0f, 3.0f, Material.STONE, 0.4f)
        beam(20.0f, 3.0f, 5.0f, Material.STONE, 0.35f)

        col(19.0f, 3.35f, 1.5f)
        col(21.0f, 3.35f, 1.5f)
        beam(20.0f, 4.85f, 2.6f)

        col(25.5f, 0f, 2.2f)
        col(27.5f, 0f, 2.2f)
        beam(26.5f, 2.2f, 2.6f)

        pig(20.0f)
        pig(20.0f, 3.35f)
        pig(26.5f, 2.5f)
        birds(RED, YELLOW, BLACK, YELLOW)
    }.build()

    private fun level10() = Builder("火药工坊", 58f, 38000, 52000).apply {
        col(18.0f, 0f, 2.2f)
        col(20.2f, 0f, 2.2f)
        beam(19.1f, 2.2f, 2.7f)
        pig(19.1f)
        pig(19.1f, 2.5f)

        box(23.0f, 0f, 0.9f, 0.9f, Material.TNT)
        box(24.0f, 0f, 0.9f, 0.9f, Material.TNT)
        box(23.0f, 0.9f, 0.9f, 0.9f)

        col(27.0f, 0f, 2.6f, Material.STONE, 0.4f)
        col(30.0f, 0f, 2.6f, Material.STONE, 0.4f)
        beam(28.5f, 2.6f, 3.4f, Material.STONE, 0.4f)

        pig(28.5f)
        pig(28.5f, 3.0f)
        birds(RED, YELLOW, BLACK, BLACK)
    }.build()

    private fun level11() = Builder("钢铁要塞", 60f, 42000, 58000).apply {
        col(18.0f, 0f, 2.8f, Material.STONE, 0.4f)
        col(21.0f, 0f, 2.8f, Material.STONE, 0.4f)
        beam(19.5f, 2.8f, 3.6f, Material.STONE, 0.4f)

        col(18.5f, 3.2f, 1.4f, Material.STONE, 0.35f)
        col(20.5f, 3.2f, 1.4f, Material.STONE, 0.35f)
        beam(19.5f, 4.6f, 2.8f)

        col(26.0f, 0f, 2.4f)
        col(28.5f, 0f, 2.4f)
        beam(27.25f, 2.4f, 3.2f)

        box(31.6f, 0f, 0.9f, 1.5f, Material.STONE)
        pig(32.8f, 0f)

        pig(19.5f)
        pig(19.5f, 3.2f)
        pig(27.25f)
        pig(27.25f, 2.7f)
        birds(RED, YELLOW, BLACK, RED, YELLOW)
    }.build()

    private fun level12() = Builder("最终决战", 64f, 48000, 65000).apply {
        col(17.5f, 0f, 2.0f, Material.ICE)
        col(19.5f, 0f, 2.0f, Material.ICE)
        beam(18.5f, 2.0f, 2.6f, Material.ICE)
        pig(18.5f, 2.3f)

        col(23.0f, 0f, 3.0f, Material.STONE, 0.4f)
        col(27.0f, 0f, 3.0f, Material.STONE, 0.4f)
        beam(25.0f, 3.0f, 4.8f, Material.STONE, 0.4f)

        col(24.0f, 3.4f, 1.6f)
        col(26.0f, 3.4f, 1.6f)
        beam(25.0f, 5.0f, 2.8f)

        col(30.0f, 0f, 2.4f)
        col(32.0f, 0f, 2.4f)
        beam(31.0f, 2.4f, 2.6f)

        box(21.0f, 0f, 0.8f, 0.8f, Material.TNT)

        pig(25.0f)
        pig(25.0f, 3.4f)
        pig(25.0f, 5.3f)
        pig(31.0f)
        pig(31.0f, 2.7f)
        egg(34.4f, 3.4f)
        birds(RED, YELLOW, BLACK, RED, YELLOW)
    }.build()

    /**
     * 隐藏关：收集 3 枚金蛋后解锁。
     */
    private fun hidden() = Builder("黄金岛", 34f, 28000, 46000).apply {
        col(19.0f, 0f, 2.6f, Material.STONE, 0.4f)
        col(23.0f, 0f, 2.6f, Material.STONE, 0.4f)
        beam(21.0f, 2.6f, 4.8f, Material.STONE, 0.4f)

        col(19.8f, 3.0f, 1.4f)
        col(22.2f, 3.0f, 1.4f)
        beam(21.0f, 4.4f, 2.8f)

        box(16.8f, 0f, 0.8f, 0.8f, Material.TNT)

        pig(21.0f)
        pig(21.0f, 3.0f)
        pig(21.0f, 4.7f)
        birds(RED, YELLOW, BLACK, RED, YELLOW)
    }.build()

    // ---------------------------------------------------------------- 构造器

    /**
     * 关卡构建辅助：坐标单位为米，y 轴向上，地面 y = 0。
     * col / beam / box 的 bottom 均表示物体底面高度。
     */
    private class Builder(
        private val name: String,
        private val width: Float,
        private val star2: Int,
        private val star3: Int
    ) {
        private val blocks = mutableListOf<BlockSpec>()
        private val pigs = mutableListOf<PigSpec>()
        private val birdList = mutableListOf<BirdType>()
        private var eggSpec: EggSpec? = null

        fun col(
            x: Float,
            bottom: Float,
            h: Float,
            material: Material = Material.WOOD,
            t: Float = 0.3f
        ) {
            blocks += BlockSpec(x, bottom + h / 2f, t, h, material)
        }

        fun beam(
            cx: Float,
            bottom: Float,
            w: Float,
            material: Material = Material.WOOD,
            t: Float = 0.3f
        ) {
            blocks += BlockSpec(cx, bottom + t / 2f, w, t, material)
        }

        fun box(x: Float, bottom: Float, w: Float, h: Float, material: Material = Material.WOOD) {
            blocks += BlockSpec(x, bottom + h / 2f, w, h, material)
        }

        fun pig(x: Float, bottom: Float = 0f, r: Float = 0.34f) {
            pigs += PigSpec(x, bottom + r, r)
        }

        fun egg(x: Float, y: Float) {
            eggSpec = EggSpec(x, y)
        }

        fun birds(vararg types: BirdType) {
            birdList += types
        }

        fun build(): LevelSpec = LevelSpec(
            name = name,
            birds = birdList.toList(),
            blocks = blocks.toList(),
            pigs = pigs.toList(),
            width = width,
            star2 = star2,
            star3 = star3,
            egg = eggSpec
        )
    }
}
