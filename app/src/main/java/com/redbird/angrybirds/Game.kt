package com.redbird.angrybirds

import org.jbox2d.callbacks.ContactImpulse
import org.jbox2d.callbacks.ContactListener
import org.jbox2d.collision.Manifold
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.contacts.Contact
import kotlin.math.abs
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
        private const val MAX_STEPS = 5
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

    var cameraX = 16f
    var cameraY = 0f

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
    }

    private fun createGround() {
        val bd = BodyDef()
        bd.type = BodyType.STATIC
        bd.position.set(0f, -0.5f)

        val shape = PolygonShape()
        shape.setAsBox(spec.width, 0.5f)

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
        ppm = min(viewWidth / 22f, viewHeight / 11.5f)
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

        if (state != PlayState.WON && state != PlayState.LOST) {
            warmup = (warmup - dt).coerceAtLeast(0f)
            hitSoundCooldown = (hitSoundCooldown - dt).coerceAtLeast(0f)

            accumulator += dt.coerceAtMost(0.05f)
            var steps = 0
            while (accumulator >= FIXED_STEP && steps < MAX_STEPS) {
                world.step(FIXED_STEP, 8, 3)
                accumulator -= FIXED_STEP
                steps++
            }
            if (steps >= MAX_STEPS) accumulator = 0f

            resolveDestruction()

            when (state) {
                PlayState.FLYING -> updateFlying(dt)
                PlayState.SETTLE -> updateSettle(dt)
                else -> {}
            }

            if (pigs.isEmpty() && state != PlayState.WON) finishWin()
        }

        updateEffects(dt)
        updateCamera(dt)
        shake = (shake - dt * 2.4f).coerceAtLeast(0f)
        flashAlpha = (flashAlpha - dt * 1.8f).coerceAtLeast(0f)
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
        if (flyingTimer > 1.4f && worldCalm()) {
            state = PlayState.SETTLE
            settleTimer = 0f
        }
    }

    private fun updateSettle(dt: Float) {
        settleTimer += dt
        if (settleTimer > 4.5f || (settleTimer > 0.6f && worldCalm())) nextTurn()
    }

    private fun nextTurn() {
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
            finishWin()
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
    }

    private fun finishWin() {
        if (state == PlayState.WON) return
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
        if (state != PlayState.AIM) return
        if (spec.birds.getOrNull(birdsUsed) == null) return

        val dx = wx - aimX
        val dy = wy - aimY
        if (dx * dx + dy * dy < 1.2f * 1.2f) {
            dragging = true
            updateAim(wx, wy)
        }
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
    }

    private fun launch() {
        val type = spec.birds.getOrNull(birdsUsed) ?: return
        val vx = (SLING_X - aimX) * LAUNCH_POWER
        val vy = (SLING_Y - aimY) * LAUNCH_POWER

        spawnBird(type, aimX, aimY, vx, vy)
        emit(GameSound.LAUNCH)
        birdsUsed++
        state = PlayState.FLYING
        flyingTimer = 0f
        settleTimer = 0f
        aimX = SLING_X
        aimY = SLING_Y
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
            if (e.body.linearVelocity.length() > 0.55f) return false
            if (abs(e.body.angularVelocity) > 1.8f) return false
        }
        for (e in pigs) {
            if (e.removed) continue
            if (e.body.linearVelocity.length() > 0.55f) return false
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
