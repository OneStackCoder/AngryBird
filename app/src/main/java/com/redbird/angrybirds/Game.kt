package com.redbird.angrybirds

import org.jbox2d.callbacks.ContactImpulse
import org.jbox2d.callbacks.ContactListener
import org.jbox2d.collision.Manifold
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.contacts.Contact
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 单个关卡的运行时：Box2D 物理世界 + 弹弓发射 + 伤害结算 + 回合状态机。
 *
 * 世界坐标：1 单位 = 1 米，y 轴向上，地面 y = 0。
 * 渲染层通过 [ppm] / [cameraX] / [cameraY] 把世界坐标换算成屏幕坐标。
 */
class Game(
    val spec: LevelSpec,
    val levelIndex: Int
) {

    companion object {
        const val SLING_X = 4.2f
        const val SLING_Y = 2.35f
        const val LAUNCH_POWER = 17f
        const val GRAVITY = -10f
        const val BIRD_RADIUS = 0.36f

        private const val MAX_DRAG = 1.35f
        private const val FIXED_STEP = 1f / 60f
        private const val MAX_STEPS = 15
        private const val PIG_HEALTH = 1.8f

        private fun healthOf(m: Material): Float = when (m) {
            Material.WOOD -> 3f
            Material.ICE -> 2f
            Material.STONE -> 6f
            Material.TNT -> 1.2f
        }

        private fun densityOf(m: Material): Float = when (m) {
            Material.WOOD -> 2f
            Material.ICE -> 1.4f
            Material.STONE -> 5f
            Material.TNT -> 1.6f
        }
    }

    // ------------------------------------------------------------ 物理世界

    val world: World = World(Vec2(0f, GRAVITY))

    val blocks = mutableListOf<Entity>()
    val pigs = mutableListOf<Entity>()
    val birds = mutableListOf<Entity>()
    val eggs = mutableListOf<Entity>()
    val particles = mutableListOf<Particle>()
    val popups = mutableListOf<Popup>()

    // ------------------------------------------------------------ 视口 / 相机

    var viewWidth = 0f
        private set
    var viewHeight = 0f
        private set
    var ppm = 80f
        private set

    /** 用户捏合缩放因子（0.65 ~ 2.2），由 GameView 写入 */
    @Volatile
    var zoom = 1f

    var cameraX = 16f
    var cameraY = 0f

    /** 用户手动平移后暂缓自动跟随的剩余时间（秒），期间相机停留在用户拖动的位置 */
    private var panHold = 0f

    // ------------------------------------------------------------ 玩法状态

    var score = 0
        private set
    var starsEarned = 0
        private set

    /** 已发射的小鸟数量 */
    var birdsUsed = 0
        private set

    /** 剩余小鸟（含正挂在弹弓上的那只） */
    val birdsLeft: Int
        get() = (spec.birds.size - birdsUsed).coerceAtLeast(0)

    /** 挂在弹弓上的小鸟类型；飞行/结算阶段为 null */
    val currentBirdType: BirdType?
        get() = if (state == PlayState.AIM) spec.birds.getOrNull(birdsUsed) else null

    /** 弹弓后方排队的小鸟 */
    val waitingBirds: List<BirdType>
        get() = spec.birds.drop(birdsUsed + if (state == PlayState.AIM) 1 else 0)

    var state = PlayState.AIM
        private set

    var paused = false

    /** 快进中（飞行/结算阶段点击屏幕触发，3 倍速模拟，回到瞄准时自动关闭） */
    var fastForward = false
        private set

    var shake = 0f
        private set
    var flashAlpha = 0f
        private set

    /** 本局是否已收集金蛋（用于写入存档） */
    var eggCollected = false
        private set

    /** 金蛋是否已写入存档，避免重复记录 */
    var eggSaved = false

    /** 炸弹道具数量 */
    var bombs = if (levelIndex == Levels.hiddenIndex) 2 else 1
        private set

    /** 是否处于投弹瞄准模式 */
    var bombAiming = false
        private set

    /** 本局结果是否已经写入 Progress */
    var resultSaved = false

    /** 是否最后一关 */
    val isFinalLevel: Boolean
        get() = levelIndex >= Levels.count - 1

    /** 飞行中的小鸟类型 */
    val flyingBirdType: BirdType?
        get() = activeBird?.birdType

    /** 飞行中且技能尚未使用时，可以点击屏幕释放技能 */
    val abilityReady: Boolean
        get() {
            val bird = activeBird ?: return false
            if (state != PlayState.FLYING || bird.abilityUsed) return false
            return bird.birdType == BirdType.YELLOW || bird.birdType == BirdType.BLACK
        }

    /** 仅在发射后的前 2.2 秒提示技能，避免长期遮挡画面 */
    val abilityHint: Boolean
        get() = abilityReady && flyingTimer < 2.2f

    /** 仅在发射后的前 1.8 秒提示可平移 / 快进 */
    val panHint: Boolean
        get() = state == PlayState.FLYING && flyingTimer < 1.8f

    // ---- 弹弓拖拽 ----

    var aimX = SLING_X
        private set
    var aimY = SLING_Y
        private set
    var dragging = false
        private set

    // ---- 内部计时与队列 ----

    private var activeBird: Entity? = null
    private var accumulator = 0f
    private var warmup = 0.5f
    private var flyingTimer = 0f
    private var settleTimer = 0f

    private val pendingDestroy = mutableListOf<Entity>()
    private val pendingExplosions = mutableListOf<Explosion>()
    private val soundQueue = mutableListOf<GameSound>()
    private var hitSoundCooldown = 0f
    private var oinkCooldown = 0f
    private var idleOinkTimer = 3.5f
    /** 猪清空后的等待计时（-1 = 还有猪）。满 1 秒且世界静止（鸟滚完）后结算胜利，6 秒兜底 */
    private var pigClearedTimer = -1f

    private class Explosion(val x: Float, val y: Float, val radius: Float, val power: Float)

    /**
     * 碰撞回调：用两刚体的相对速度估算冲击强度。
     * 注意：回调内部只做数值修改，销毁与爆炸都排队到 step 之后处理。
     */
    private val contactListener = object : ContactListener {
        override fun beginContact(contact: Contact) {
            if (warmup > 0f) return
            val fa = contact.fixtureA ?: return
            val fb = contact.fixtureB ?: return

            val ea = fa.body.userData as? Entity
            val eb = fb.body.userData as? Entity
            if (ea == null && eb == null) return

            val va = fa.body.linearVelocity
            val vb = fb.body.linearVelocity
            val dx = va.x - vb.x
            val dy = va.y - vb.y
            val rel = sqrt(dx * dx + dy * dy)

            triggerBlackBird(ea, rel)
            triggerBlackBird(eb, rel)

            if (ea != null && ea.type == EntityType.EGG) collectEgg(ea)
            if (eb != null && eb.type == EntityType.EGG) collectEgg(eb)

            if (rel < 3f) return
            if (ea != null) damageFrom(ea, eb, rel)
            if (eb != null) damageFrom(eb, ea, rel)
        }

        override fun endContact(contact: Contact) {}

        override fun preSolve(contact: Contact, oldManifold: Manifold) {}

        override fun postSolve(contact: Contact, impulse: ContactImpulse) {}
    }

    // 弹弓鸟 / 排队鸟的碰撞体字段：必须在下方 init（buildWorld）执行前完成初始化，
    // 因此声明在这里而不是函数区中间——Kotlin 属性按声明顺序初始化。
    private var aimBirdBody: Body? = null
    private val waitingBirdBodies = mutableListOf<Body>()

    init {
        world.setContactListener(contactListener)
        buildWorld()
    }

    // ------------------------------------------------------------ 世界构建

    private fun buildWorld() {
        createGround()
        for (b in spec.blocks) createBlock(b)
        for (p in spec.pigs) createPig(p)
        spec.egg?.let { createEgg(it) }
        createAimBird()
        createWaitingBirds()
    }

    /**
     * 弹弓上待发射小鸟的碰撞体（kinematic，无限质量）。
     * 渲染层画在 aimX/aimY 的鸟本身没有物理体——没有它，猪/块滚过来会直接穿过去。
     * userData 保持 null：beginContact 会跳过伤害与音效链，仅提供物理阻挡。
     * （字段 aimBirdBody 声明在上方 init 块之前，见注释）
     */
    private fun createAimBird() {
        destroyAimBird()
        if (state != PlayState.AIM) return
        if (spec.birds.getOrNull(birdsUsed) == null) return

        val bd = BodyDef()
        bd.type = BodyType.KINEMATIC
        bd.position.set(aimX, aimY)

        val shape = CircleShape()
        shape.m_radius = BIRD_RADIUS

        val fd = FixtureDef()
        fd.shape = shape
        fd.density = 1f
        fd.friction = 0.5f
        fd.restitution = 0.25f

        aimBirdBody = world.createBody(bd).also { it.createFixture(fd) }
    }

    private fun destroyAimBird() {
        aimBirdBody?.let { world.destroyBody(it) }
        aimBirdBody = null
    }

    /** 弹弓后方排队小鸟的碰撞体（与渲染位置一一对应；字段声明在 init 之前） */
    private fun createWaitingBirds() {
        destroyWaitingBirds()
        val waiting = waitingBirds
        for (i in waiting.indices) {
            val bd = BodyDef()
            bd.type = BodyType.KINEMATIC
            bd.position.set(SLING_X - 1.35f - i * 0.82f, 0.36f)

            val shape = CircleShape()
            shape.m_radius = 0.34f

            val fd = FixtureDef()
            fd.shape = shape
            fd.density = 1f
            fd.friction = 0.5f
            fd.restitution = 0.2f

            waitingBirdBodies += world.createBody(bd).also { it.createFixture(fd) }
        }
    }

    private fun destroyWaitingBirds() {
        for (b in waitingBirdBodies) world.destroyBody(b)
        waitingBirdBodies.clear()
    }

    /** 拖拽 / 复位后同步碰撞体位置（圆体角度不影响碰撞，设为发射方向仅为语义一致） */
    private fun syncAimBird() {
        aimBirdBody?.setTransform(
            Vec2(aimX, aimY),
            atan2(SLING_Y - aimY, SLING_X - aimX)
        )
    }

    private fun createGround() {
        val bd = BodyDef()
        bd.type = BodyType.STATIC
        // 顶面保持 y = 0；加厚防止高速物体穿透，
        // 加宽覆盖出界判定区（x ∈ [width, width+12] 原先是悬空的）
        bd.position.set(0f, -2f)

        val shape = PolygonShape()
        shape.setAsBox(spec.width + 20f, 2f)

        val fd = FixtureDef()
        fd.shape = shape
        fd.friction = 0.85f

        val body = world.createBody(bd)
        body.createFixture(fd)
    }

    private fun createBlock(spec: BlockSpec) {
        val bd = BodyDef()
        bd.type = BodyType.DYNAMIC
        bd.position.set(spec.x, spec.y)
        bd.allowSleep = true

        val shape = PolygonShape()
        shape.setAsBox(spec.w / 2f, spec.h / 2f)

        val fd = FixtureDef()
        fd.shape = shape
        fd.density = densityOf(spec.material)
        fd.friction = 0.65f
        fd.restitution = 0.06f

        val body = world.createBody(bd)
        body.createFixture(fd)

        val health = healthOf(spec.material)
        val entity = Entity(
            type = EntityType.BLOCK,
            body = body,
            material = spec.material,
            width = spec.w,
            height = spec.h,
            maxHealth = health,
            health = health
        )
        body.userData = entity
        blocks += entity
    }

    private fun createPig(spec: PigSpec) {
        val bd = BodyDef()
        bd.type = BodyType.DYNAMIC
        bd.position.set(spec.x, spec.y)
        bd.allowSleep = true
        // 旋转阻尼：没有它圆形猪会无限滚动，被撞飞后能滚出半个地图
        bd.angularDamping = 0.85f

        val shape = CircleShape()
        shape.m_radius = spec.radius

        val fd = FixtureDef()
        fd.shape = shape
        fd.density = 2f
        fd.friction = 0.7f
        fd.restitution = 0.18f

        val body = world.createBody(bd)
        body.createFixture(fd)

        val entity = Entity(
            type = EntityType.PIG,
            body = body,
            material = Material.WOOD,
            radius = spec.radius,
            maxHealth = PIG_HEALTH,
            health = PIG_HEALTH
        )
        body.userData = entity
        pigs += entity
    }

    private fun createEgg(spec: EggSpec) {
        val bd = BodyDef()
        bd.type = BodyType.STATIC
        bd.position.set(spec.x, spec.y)

        val shape = CircleShape()
        shape.m_radius = 0.24f

        val fd = FixtureDef()
        fd.shape = shape
        fd.isSensor = true

        val body = world.createBody(bd)
        body.createFixture(fd)

        val entity = Entity(
            type = EntityType.EGG,
            body = body,
            radius = 0.24f
        )
        body.userData = entity
        eggs += entity
    }

    private fun spawnBird(type: BirdType, x: Float, y: Float, vx: Float, vy: Float): Entity {
        val bd = BodyDef()
        bd.type = BodyType.DYNAMIC
        bd.position.set(x, y)
        // 初始姿态对准发射方向：与拉弓时的渲染角度无缝衔接（圆形体角度不影响碰撞）
        bd.angle = atan2(vy, vx)
        bd.bullet = true
        bd.allowSleep = false
        bd.linearDamping = 0.04f
        bd.angularDamping = 0.35f

        val shape = CircleShape()
        shape.m_radius = BIRD_RADIUS

        val fd = FixtureDef()
        fd.shape = shape
        fd.density = 5f
        fd.friction = 0.5f
        fd.restitution = 0.32f

        val body = world.createBody(bd)
        body.createFixture(fd)
        body.setLinearVelocity(Vec2(vx, vy))

        val entity = Entity(
            type = EntityType.BIRD,
            body = body,
            radius = BIRD_RADIUS,
            width = BIRD_RADIUS * 2f,
            height = BIRD_RADIUS * 2f,
            birdType = type
        )
        body.userData = entity
        birds += entity
        activeBird = entity
        return entity
    }

    // ------------------------------------------------------------ 视口

    fun resize(viewWidth: Float, viewHeight: Float) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val first = this.viewWidth <= 0f
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        ppm = min(viewWidth / 22f, viewHeight / 11.5f) * zoom
        val baseY = 0.35f * (viewHeight / ppm)
        if (first) {
            cameraY = baseY
            cameraX = focusX()
        }
    }

    // ------------------------------------------------------------ 主循环

    /**
     * @param dt 秒
     */
    fun update(dt: Float) {
        if (dt <= 0f) return
        if (paused) {
            dragging = false
            bombAiming = false
            return
        }
        // 快进：以 3 倍速推进整个模拟（物理 / 粒子 / 相机一致加速）
        val d = if (fastForward) dt * 3f else dt

        if (state != PlayState.WON && state != PlayState.LOST) {
            warmup = (warmup - d).coerceAtLeast(0f)
            hitSoundCooldown = (hitSoundCooldown - d).coerceAtLeast(0f)
            oinkCooldown = (oinkCooldown - d).coerceAtLeast(0f)

            // 存活的猪每隔几秒随机"哼哼"一声，对齐原版的临场感
            idleOinkTimer -= d
            if (idleOinkTimer <= 0f) {
                idleOinkTimer = 2.5f + Math.random().toFloat() * 2.5f
                if (pigs.isNotEmpty() && !paused) emit(GameSound.PIG_OINK)
            }

            accumulator += d.coerceAtMost(0.05f)
            var steps = 0
            while (accumulator >= FIXED_STEP && steps < MAX_STEPS) {
                world.step(FIXED_STEP, 8, 3)
                accumulator -= FIXED_STEP
                steps++
            }
            if (steps >= MAX_STEPS) accumulator = 0f

            resolveDestruction()

            when (state) {
                PlayState.FLYING -> updateFlying(d)
                PlayState.SETTLE -> updateSettle(d)
                else -> {}
            }

            // 猪清光后：等鸟停止滚动、块落定（worldCalm）再结算；6 秒兜底防卡死
            if (state != PlayState.WON) {
                if (pigs.isEmpty()) {
                    if (pigClearedTimer < 0f) pigClearedTimer = 0f
                    pigClearedTimer += d
                    if (pigClearedTimer >= 1f && (worldCalm() || pigClearedTimer >= 6f)) finishWin()
                } else {
                    pigClearedTimer = -1f
                }
            }
        }

        updateEffects(d)
        updateCamera(d)
        shake = (shake - d * 2.4f).coerceAtLeast(0f)
        flashAlpha = (flashAlpha - d * 1.8f).coerceAtLeast(0f)
    }

    private fun updateFlying(dt: Float) {
        flyingTimer += dt
        val bird = activeBird
        if (bird == null) {
            state = PlayState.SETTLE
            settleTimer = 0f
            return
        }
        val p = bird.body.position
        if (p.y < -2.5f || p.x > spec.width + 12f || p.x < -8f) {
            state = PlayState.SETTLE
            settleTimer = 0f
            return
        }
        // 飞行总超时：避免小鸟长时间慢滚导致一直无法进入结算
        if (flyingTimer > 5f) {
            state = PlayState.SETTLE
            settleTimer = 0f
            return
        }
        if (flyingTimer > 1.4f && worldCalm()) {
            state = PlayState.SETTLE
            settleTimer = 0f
        }
    }

    private fun updateSettle(dt: Float) {
        settleTimer += dt
        if (settleTimer > 2f || (settleTimer > 0.5f && worldCalm())) nextTurn()
    }

    private fun nextTurn() {
        fastForward = false
        panHold = 0f
        val bird = activeBird
        if (bird != null) {
            birds.remove(bird)
            if (!bird.removed) {
                bird.removed = true
                world.destroyBody(bird.body)
            }
            activeBird = null
        }

        if (pigs.isEmpty()) {
            // 不在此处立即结算：交给 update 的 pigClearedTimer 延迟触发（演出收尾）
            return
        }
        if (birdsUsed >= spec.birds.size) {
            finishLose()
            return
        }

        state = PlayState.AIM
        aimX = SLING_X
        aimY = SLING_Y
        dragging = false
        bombAiming = false
        flyingTimer = 0f
        settleTimer = 0f
        createAimBird()
        createWaitingBirds()
    }

    private fun finishWin() {
        if (state == PlayState.WON) return
        fastForward = false
        destroyAimBird()
        destroyWaitingBirds()
        val remaining = (spec.birds.size - birdsUsed).coerceAtLeast(0)
        if (remaining > 0) {
            score += remaining * 10000
            addPopup(SLING_X, SLING_Y + 1.4f, "+${remaining * 10000}")
        }
        starsEarned = starsFor(score)
        state = PlayState.WON
        dragging = false
        bombAiming = false
        flashAlpha = 0.55f
        shake = max(shake, 0.5f)
        emit(GameSound.WIN)
    }

    private fun finishLose() {
        if (state == PlayState.LOST) return
        if (pigs.isEmpty()) {
            finishWin()
            return
        }
        fastForward = false
        destroyAimBird()
        destroyWaitingBirds()
        state = PlayState.LOST
        starsEarned = 0
        dragging = false
        bombAiming = false
        emit(GameSound.LOSE)
    }

    // ------------------------------------------------------------ 伤害与破坏

    private fun vulnerability(e: Entity): Float = when {
        e.type == EntityType.PIG -> 1.35f
        e.material == Material.ICE -> 1.7f
        e.material == Material.STONE -> 0.55f
        e.material == Material.TNT -> 2f
        else -> 1f
    }

    private fun triggerBlackBird(e: Entity?, rel: Float) {
        if (e == null) return
        if (e.type != EntityType.BIRD || e.birdType != BirdType.BLACK) return
        if (e.abilityUsed || e.removed || rel < 4f) return
        e.abilityUsed = true
        queueExplosion(e.x, e.y, 2.9f, 10f)
        e.removed = true
        pendingDestroy.add(e)
    }

    private fun damageFrom(target: Entity, source: Entity?, rel: Float) {
        if (target.removed) return
        if (target.type != EntityType.BLOCK && target.type != EntityType.PIG) return

        val sourceMass = when {
            source == null -> 1.7f * target.body.mass.coerceIn(0.3f, 4f)
            source.type == EntityType.BIRD -> source.body.mass.coerceAtLeast(0.8f) * 1.2f
            else -> source.body.mass.coerceAtLeast(0.3f)
        }
        val dmg = (rel - 3f) * sourceMass * 0.20f * vulnerability(target)
        if (dmg < 0.05f) return

        // 猪被撞到就"哼哼"叫（无论是否致死），独立节流避免连撞太吵
        if (target.type == EntityType.PIG && dmg > 0.06f && oinkCooldown <= 0f) {
            oinkCooldown = 1.2f
            emit(GameSound.PIG_OINK)
        }

        if (dmg > 0.6f) {
            spawnBurst(target.x, target.y, 3, colorOf(target), 2.5f)
        }
        if (dmg > 0.9f && hitSoundCooldown <= 0f) {
            hitSoundCooldown = 0.09f
            emit(soundOf(target))
        }
        damageEntity(target, dmg)
    }

    private fun damageEntity(e: Entity, amount: Float) {
        if (amount <= 0f || e.removed) return
        if (e.type != EntityType.BLOCK && e.type != EntityType.PIG) return
        e.health -= amount
        e.hitFlash = 0.2f
        if (e.health <= 0f) {
            e.removed = true
            pendingDestroy.add(e)
        }
    }

    private fun resolveDestruction() {
        var guard = 0
        while (guard++ < 24) {
            if (pendingExplosions.isNotEmpty()) {
                doExplode(pendingExplosions.removeAt(0))
                continue
            }
            if (pendingDestroy.isNotEmpty()) {
                destroyEntity(pendingDestroy.removeAt(0))
                continue
            }
            return
        }
    }

    private fun collectEgg(egg: Entity) {
        if (egg.removed) return
        egg.removed = true
        eggCollected = true
        score += 3000
        addPopup(egg.x, egg.y + 0.6f, "金蛋 +3000")
        spawnBurst(egg.x, egg.y, 18, 0xFFFFD54F.toInt(), 4.5f)
        shake = max(shake, 0.35f)
        emit(GameSound.GOLD)
        pendingDestroy.add(egg)
    }

    private fun destroyEntity(e: Entity) {
        if (e.type == EntityType.EGG) {
            eggs.remove(e)
            world.destroyBody(e.body)
            return
        }

        if (e.type == EntityType.BIRD) {
            birds.remove(e)
            if (activeBird === e) activeBird = null
            world.destroyBody(e.body)
            return
        }

        if (e.type == EntityType.PIG) {
            pigs.remove(e)
            score += 5000
            addPopup(e.x, e.y + 0.5f, "+5000")
            spawnBurst(e.x, e.y, 20, 0xFF86C43F.toInt(), 4.5f)
            shake = max(shake, 0.6f)
            emit(GameSound.PIG)
        } else {
            blocks.remove(e)
            score += 500
            addPopup(e.x, e.y + 0.4f, "+500")
            spawnBurst(e.x, e.y, 12, colorOf(e), 4f)
            shake = max(shake, 0.28f)
            if (e.material == Material.TNT) {
                queueExplosion(e.x, e.y, 3f, 11f)
            } else {
                emit(soundOf(e))
            }
        }
        world.destroyBody(e.body)
    }

    private fun queueExplosion(x: Float, y: Float, radius: Float, power: Float) {
        pendingExplosions.add(Explosion(x, y, radius, power))
    }

    private fun doExplode(ex: Explosion) {
        emit(GameSound.EXPLODE)
        spawnBurst(ex.x, ex.y, 26, 0xFFFFB300.toInt(), 8f)
        spawnBurst(ex.x, ex.y, 10, 0xFFFFF3B0.toInt(), 5f)
        shake = max(shake, 0.9f)
        flashAlpha = max(flashAlpha, 0.35f)

        val targets = ArrayList<Entity>(blocks.size + pigs.size)
        targets.addAll(blocks)
        targets.addAll(pigs)

        for (e in targets) {
            if (e.removed) continue
            val dx = e.x - ex.x
            val dy = e.y - ex.y
            val d = sqrt(dx * dx + dy * dy)
            if (d > ex.radius) continue

            val falloff = 1f - d / ex.radius
            val strength = ex.power * falloff
            val mass = e.body.mass.coerceAtLeast(0.2f)
            val nx = if (d > 0.01f) dx / d else 0f
            val ny = if (d > 0.01f) dy / d else 1f

            e.body.applyLinearImpulse(
                Vec2(nx * strength * mass, ny * strength * mass),
                e.body.worldCenter
            )
            damageEntity(e, strength * 1.4f * vulnerability(e))
        }
    }

    // ------------------------------------------------------------ 输入 / 弹弓

    fun onTouchDown(wx: Float, wy: Float) {
        if (paused) return

        if (bombAiming) {
            bombAiming = false
            bombs = (bombs - 1).coerceAtLeast(0)
            queueExplosion(wx, wy, 2.7f, 9f)
            return
        }

        if (state == PlayState.FLYING) {
            onAbilityTap()
            return
        }
        if (state == PlayState.SETTLE) return
        if (state != PlayState.AIM) return
        if (spec.birds.getOrNull(birdsUsed) == null) return

        val dx = wx - aimX
        val dy = wy - aimY
        if (dx * dx + dy * dy < 1.2f * 1.2f) {
            dragging = true
            updateAim(wx, wy)
        }
    }

    /** 取消弹弓拖拽并复位瞄准点（双指缩放开始时调用） */
    fun cancelDrag() {
        if (dragging) {
            dragging = false
            aimX = SLING_X
            aimY = SLING_Y
            syncAimBird()
        }
    }

    /**
     * 用户单指拖动平移镜头（向右拖 = 视野向左移）。
     * 平移后 [panHold] 秒内暂停自动跟随，超时或进入新回合后恢复。
     */
    fun panBy(dxPx: Float) {
        if (paused || state == PlayState.WON || state == PlayState.LOST) return
        if (ppm <= 0f || viewWidth <= 0f) return

        val half = viewWidth / ppm / 2f
        // 允许查看的范围：弹弓左侧留 5.5m，关卡右端外留 3m
        val minX = SLING_X - 5.5f + half
        val maxX = spec.width + 3f - half
        cameraX = if (maxX > minX) {
            (cameraX - dxPx / ppm).coerceIn(minX, maxX)
        } else {
            (minX + maxX) * 0.5f
        }
        panHold = 3f
    }

    /** 飞行/结算阶段在空白处轻点：快进等待（拖动镜头、点按钮不会触发） */
    fun onTapEmpty() {
        if (paused || bombAiming) return
        if (state == PlayState.FLYING && !abilityReady) fastForward = true
        else if (state == PlayState.SETTLE) fastForward = true
    }

    fun onTouchMove(wx: Float, wy: Float) {
        if (!dragging) return
        updateAim(wx, wy)
    }

    fun onTouchUp(wx: Float, wy: Float) {
        if (!dragging) return
        dragging = false
        updateAim(wx, wy)

        val dx = SLING_X - aimX
        val dy = SLING_Y - aimY
        if (dx * dx + dy * dy < 0.02f) {
            aimX = SLING_X
            aimY = SLING_Y
            syncAimBird()
            return
        }
        launch()
    }

    private fun updateAim(wx: Float, wy: Float) {
        var dx = wx - SLING_X
        var dy = wy - SLING_Y
        val len = sqrt(dx * dx + dy * dy)
        if (len > MAX_DRAG) {
            dx = dx / len * MAX_DRAG
            dy = dy / len * MAX_DRAG
        }
        aimX = SLING_X + dx
        aimY = SLING_Y + dy
        syncAimBird()
    }

    private fun launch() {
        val type = spec.birds.getOrNull(birdsUsed) ?: return
        // 发射瞬间：销毁弹弓鸟碰撞体，同位置由 spawnBird 接管为动态体；
        // 排队鸟碰撞体也要重建（发射后队列整体前移一位）
        destroyAimBird()
        destroyWaitingBirds()
        val vx = (SLING_X - aimX) * LAUNCH_POWER
        val vy = (SLING_Y - aimY) * LAUNCH_POWER

        spawnBird(type, aimX, aimY, vx, vy)
        emit(GameSound.LAUNCH)
        birdsUsed++
        state = PlayState.FLYING
        flyingTimer = 0f
        settleTimer = 0f
        panHold = 0f
        aimX = SLING_X
        aimY = SLING_Y
        // 飞行期间队列里的鸟仍渲染在排队位置（waitingBirds = drop(birdsUsed)），碰撞体保持存在
        createWaitingBirds()
    }

    /** 切换投弹瞄准模式；返回是否进入瞄准 */
    fun armBomb(): Boolean {
        if (paused || bombs <= 0) return false
        if (state == PlayState.WON || state == PlayState.LOST) return false
        bombAiming = !bombAiming
        return bombAiming
    }

    fun onAbilityTap() {
        if (paused || state != PlayState.FLYING) return
        val bird = activeBird ?: return
        if (bird.abilityUsed || bird.removed) return

        when (bird.birdType) {
            BirdType.YELLOW -> {
                bird.abilityUsed = true
                val v = bird.body.linearVelocity
                val len = v.length()
                val nx: Float
                val ny: Float
                if (len > 0.5f) {
                    nx = v.x / len
                    ny = v.y / len
                } else {
                    nx = 1f
                    ny = 0.35f
                }
                val speed = (len + 9f).coerceAtMost(34f)
                bird.body.setLinearVelocity(Vec2(nx * speed, ny * speed))
                // 姿态对准冲刺方向，并清掉残余自旋
                bird.body.setTransform(bird.body.position, atan2(ny, nx))
                bird.body.angularVelocity = 0f
                spawnBurst(bird.x, bird.y, 14, 0xFFFFE082.toInt(), 4f)
            }

            BirdType.BLACK -> {
                bird.abilityUsed = true
                queueExplosion(bird.x, bird.y, 2.9f, 10f)
                if (!bird.removed) {
                    bird.removed = true
                    pendingDestroy.add(bird)
                }
            }

            BirdType.RED, null -> {}
        }
    }

    // ------------------------------------------------------------ 辅助

    private fun worldCalm(): Boolean {
        for (e in blocks) {
            if (e.removed) continue
            if (e.body.linearVelocity.length() > 0.7f) return false
            if (abs(e.body.angularVelocity) > 1.8f) return false
        }
        for (e in pigs) {
            if (e.removed) continue
            if (e.body.linearVelocity.length() > 0.7f) return false
        }
        val bird = activeBird ?: return true
        return bird.body.linearVelocity.length() < 1f
    }

    private fun focusX(): Float {
        val half = if (ppm > 0f && viewWidth > 0f) viewWidth / ppm / 2f else 12f
        val minX = SLING_X - 3.2f
        val maxX = if (pigs.isEmpty()) SLING_X + 10f else pigs.maxOf { it.x } + 2.2f
        val span = maxX - minX
        return if (span <= half * 2f) (minX + maxX) * 0.5f else minX + half
    }

    private fun updateCamera(dt: Float) {
        if (state == PlayState.WON || state == PlayState.LOST) return

        val baseY = 0.35f * (viewHeight / ppm)

        // 手动平移观察期间：X 保持在用户拖动的位置，Y 仍跟随基准（缩放时地面位置稳定）
        if (panHold > 0f) {
            panHold -= dt
            val kY = (1f - exp(-dt * 3.2f)).coerceIn(0f, 1f)
            cameraY += (baseY - cameraY) * kY
            return
        }

        var tx = focusX()
        var ty = baseY

        val bird = activeBird
        if (state == PlayState.FLYING && bird != null) {
            tx = bird.x + 1.5f
            ty = baseY + (bird.y - 3f).coerceIn(0f, 7f) * 0.45f
        }

        val k = (1f - exp(-dt * 3.2f)).coerceIn(0f, 1f)
        cameraX += (tx - cameraX) * k
        cameraY += (ty - cameraY) * k
    }

    private fun updateEffects(dt: Float) {
        for (e in blocks) {
            if (e.hitFlash > 0f) e.hitFlash = (e.hitFlash - dt).coerceAtLeast(0f)
        }
        for (e in pigs) {
            if (e.hitFlash > 0f) e.hitFlash = (e.hitFlash - dt).coerceAtLeast(0f)
        }

        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) {
                particles.removeAt(i)
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += GRAVITY * 0.5f * dt
            p.vx *= (1f - 0.8f * dt)
            i++
        }

        i = 0
        while (i < popups.size) {
            val p = popups[i]
            p.life -= dt
            if (p.life <= 0f) {
                popups.removeAt(i)
                continue
            }
            p.y += 0.8f * dt
            i++
        }
    }

    private fun spawnBurst(x: Float, y: Float, count: Int, color: Int, speed: Float = 3.5f) {
        for (i in 0 until count) {
            val a = Random.nextFloat() * 6.2831855f
            val v = speed * (0.35f + Random.nextFloat() * 0.9f)
            val life = 0.35f + Random.nextFloat() * 0.55f
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(a) * v,
                    vy = sin(a) * v + speed * 0.25f,
                    life = life,
                    maxLife = life,
                    size = 0.04f + Random.nextFloat() * 0.07f,
                    color = color
                )
            )
        }
    }

    private fun addPopup(x: Float, y: Float, text: String) {
        popups.add(Popup(x, y, text, 1.1f, 1.1f))
    }

    private fun colorOf(e: Entity): Int = when {
        e.type == EntityType.PIG -> 0xFF86C43F.toInt()
        e.material == Material.WOOD -> 0xFFCE8C4E.toInt()
        e.material == Material.ICE -> 0xFFB2E4F6.toInt()
        e.material == Material.STONE -> 0xFF9C9C9C.toInt()
        else -> 0xFFD84315.toInt()
    }

    // ------------------------------------------------------------ 音效事件

    /** 取出并清空本帧产生的音效事件（由渲染线程消费） */
    fun drainSounds(): List<GameSound> {
        if (soundQueue.isEmpty()) return emptyList()
        val list = ArrayList<GameSound>(soundQueue)
        soundQueue.clear()
        return list
    }

    private fun emit(sound: GameSound) {
        if (soundQueue.size < 8) soundQueue.add(sound)
    }

    private fun soundOf(e: Entity): GameSound = when {
        e.type == EntityType.PIG -> GameSound.PIG
        e.material == Material.ICE -> GameSound.ICE
        e.material == Material.STONE -> GameSound.STONE
        e.material == Material.TNT -> GameSound.STONE
        else -> GameSound.WOOD
    }

    fun starsFor(score: Int): Int = when {
        score >= spec.star3 -> 3
        score >= spec.star2 -> 2
        else -> 1
    }
}
