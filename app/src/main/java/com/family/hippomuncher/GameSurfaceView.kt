package com.family.hippomuncher

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TextureView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The entire game lives here: a custom background [GameThread] renders
 * onto this SurfaceView's Canvas at ~60 fps, fully decoupled from the
 * UI thread and the camera/ML pipeline.
 *
 * Thread-safety model (deliberately simple — no frameworks):
 *  - The camera thread writes the latest [FaceFrame] into [@Volatile]
 *    fields via [onFaceFrame].
 *  - The game thread reads them each tick and lerps toward them.
 *  - State transitions requested from the UI thread (drawer buttons)
 *    set a volatile "requested state" the loop honors on its next tick.
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    // ======================== Game states & Difficulty ========================
    enum class State { WAITING_FOR_CAMERA, CALIBRATING, COUNTDOWN, PLAYING, PAUSED, GAME_OVER }
    enum class Difficulty { EASY, MEDIUM, HARD }

    @Volatile private var state = State.WAITING_FOR_CAMERA
    @Volatile var difficulty = Difficulty.MEDIUM

    // ==================== Face input (volatile bridge) ====================
    @Volatile private var faceX = 0.5f
    @Volatile private var faceY = 0.5f
    @Volatile private var faceSize = 0f
    @Volatile private var faceVisible = false

    /** Called from the CameraX analyzer thread. */
    fun onFaceFrame(f: FaceFrame) {
        faceX = f.normX
        faceY = f.normY
        faceSize = f.sizeRatio
        faceVisible = f.hasFace
    }

    // ======================== Public controls ========================
    val sound = SoundFx(context)
    @Volatile var highScore = 0
    var onNewHighScore: ((Int) -> Unit)? = null   // invoked on game thread

    fun startCalibration() {
        score = 0
        alignedSinceMs = 0L
        calibrationAccumX = 0f
        calibrationCount = 0
        calibratedCenterX = 0.5f
        state = State.CALIBRATING
    }

    fun pauseGame() {
        if (state == State.PLAYING || state == State.COUNTDOWN) {
            state = State.PAUSED
            sound.pauseMusic()
        }
    }

    fun resumeGame() {
        if (state == State.PAUSED) {
            state = State.PLAYING
            sound.resumeMusic()
        }
    }

    fun resetHighScore() { highScore = 0 }

    fun quitToMainScreen() {
        sound.stopMusic()
        items.clear()
        particles.clear()
        score = 0
        bombsEaten = 0
        fruitsDropped = 0
        alignedSinceMs = 0L
        state = State.WAITING_FOR_CAMERA
    }

    // ======================== Game entities ========================
    private var score = 0
    private var bombsEaten = 0
    private var fruitsDropped = 0
    private var hippoX = 0.5f            // smoothed render position (0..1)
    private var hippoDizzyUntil = 0L     // wobble animation end time
    private var alignedSinceMs = 0L      // calibration hold timer
    private var calibrationAccumX = 0f
    private var calibrationCount = 0
    private var calibratedCenterX = 0.5f
    private var countdownStartMs = 0L
    private var lastSpawnMs = 0L
    private var lastTickPlayed = -1

    private data class Item(
        var x: Float, var y: Float,       // normalized
        val good: Boolean,
        val kind: Int,                    // 0 melon, 1 banana, 2 star | 0 rock, 1 boot
        val speed: Float                  // normalized units / second
    )

    private data class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val color: Int
    )

    private val items = CopyOnWriteArrayList<Item>()
    private val particles = CopyOnWriteArrayList<Particle>()

    // ======================== Paints (allocated once) ========================
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val cCyan = Color.parseColor("#00F5FF")
    private val cYellow = Color.parseColor("#FFEB3B")
    private val cMagenta = Color.parseColor("#FF2BD6")
    private val cLime = Color.parseColor("#8BFF2B")
    private val cRed = Color.parseColor("#FF4D6A")
    private val cBg = Color.parseColor("#14122B")

    // ======================== Thread plumbing ========================
    private var thread: GameThread? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        thread = GameThread().also { it.running = true; it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.let {
            it.running = false
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state == State.GAME_OVER) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                startCalibration()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ======================== THE GAME LOOP ========================
    private inner class GameThread : Thread("GameLoop") {
        @Volatile var running = false

        override fun run() {
            var lastNs = System.nanoTime()
            while (running) {
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastNs) / 1_000_000_000.0f).coerceAtMost(0.05f)
                lastNs = nowNs

                update(dt)

                val canvas: Canvas? = try { this@GameSurfaceView.lockCanvas() } catch (e: Exception) { null }
                if (canvas != null) {
                    try { render(canvas) }
                    finally {
                        try { this@GameSurfaceView.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }

                // ~60 fps pacing
                val frameMs = (System.nanoTime() - nowNs) / 1_000_000
                val sleepMs = 16 - frameMs
                if (sleepMs > 0) try { sleep(sleepMs) } catch (_: InterruptedException) {}
            }
        }
    }

    // ======================== UPDATE ========================
    private fun update(dt: Float) {
        val now = System.currentTimeMillis()

        when (state) {
            State.WAITING_FOR_CAMERA -> if (faceVisible) state = State.CALIBRATING

            State.CALIBRATING -> updateCalibration(now)

            State.COUNTDOWN -> {
                val elapsed = now - countdownStartMs
                val secondsLeft = 3 - (elapsed / 1000).toInt()
                if (secondsLeft != lastTickPlayed && secondsLeft in 1..3) {
                    sound.tick(); lastTickPlayed = secondsLeft
                }
                if (elapsed >= 3000) {
                    sound.go()     // plays fanfare + starts background music
                    items.clear(); particles.clear()
                    score = 0; bombsEaten = 0; fruitsDropped = 0; lastSpawnMs = now
                    state = State.PLAYING
                }
            }

            State.PLAYING -> updatePlaying(dt, now)

            State.PAUSED -> { /* frozen — music already paused by pauseGame() */ }
            State.GAME_OVER -> { /* frozen */ }
        }

        // Particles update in every state so bursts finish nicely.
        particles.forEach { p ->
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vy += 1.2f * dt           // gravity
            p.life -= dt * 1.8f
        }
        particles.removeAll { it.life <= 0f }
    }

    private fun updateCalibration(now: Long) {
        val aligned = isFaceAligned()
        if (aligned) {
            if (alignedSinceMs == 0L) {
                alignedSinceMs = now
                calibrationAccumX = 0f
                calibrationCount = 0
            }
            calibrationAccumX += faceX
            calibrationCount++

            // Hold steady for 800 ms before launching the countdown —
            // forgiving for a wiggly 6-year-old, but avoids false starts.
            if (now - alignedSinceMs >= 800) {
                calibratedCenterX = if (calibrationCount > 0) calibrationAccumX / calibrationCount else 0.5f
                countdownStartMs = now
                lastTickPlayed = -1
                state = State.COUNTDOWN
            }
        } else {
            alignedSinceMs = 0L
        }
    }

    /** Face center inside the target circle AND at a sane distance. */
    private fun isFaceAligned(): Boolean {
        if (!faceVisible) return false
        val inCircle = abs(faceX - 0.5f) < CAL_RADIUS && abs(faceY - 0.5f) < CAL_RADIUS
        val goodDistance = faceSize in MIN_FACE_SIZE..MAX_FACE_SIZE
        return inCircle && goodDistance
    }

    private fun updatePlaying(dt: Float, now: Long) {
        // ---- Hippo follows the face with a render-side lerp on top of the
        // analyzer's low-pass filter: silky even if camera frames drop. ----
        val targetX = (0.5f + (faceX - calibratedCenterX) * 2.2f).coerceIn(0.05f, 0.95f)
        hippoX += (targetX - hippoX) * (LERP_SPEED * dt).coerceAtMost(1f)

        // ---- Difficulty settings ----
        val spawnInterval = when (difficulty) {
            Difficulty.EASY -> 1400L
            Difficulty.MEDIUM -> 1000L
            Difficulty.HARD -> 700L
        }
        val speedMultiplier = when (difficulty) {
            Difficulty.EASY -> 0.75f
            Difficulty.MEDIUM -> 1.00f
            Difficulty.HARD -> 1.40f
        }

        // ---- Spawn items ----
        if (now - lastSpawnMs > spawnInterval) {
            lastSpawnMs = now
            val good = Random.nextFloat() > 0.25f   // 75% good items
            items.add(
                Item(
                    x = Random.nextFloat() * 0.86f + 0.07f,
                    y = -0.08f,
                    good = good,
                    kind = if (good) Random.nextInt(3) else Random.nextInt(2),
                    speed = FALL_SPEED * speedMultiplier * (0.85f + Random.nextFloat() * 0.4f)
                )
            )
        }

        // ---- Move items & detect collisions (pure AABB, normalized) ----
        val hippoLeft = hippoX - HIPPO_HALF_W
        val hippoRight = hippoX + HIPPO_HALF_W
        val hippoTop = HIPPO_Y - HIPPO_HALF_H

        items.forEach { it.y += it.speed * dt }

        val eaten = items.filter { item ->
            item.y + ITEM_HALF > hippoTop &&
            item.y - ITEM_HALF < HIPPO_Y + HIPPO_HALF_H &&
            item.x + ITEM_HALF > hippoLeft &&
            item.x - ITEM_HALF < hippoRight
        }
        eaten.forEach { item ->
            if (item.good) {
                score++
                if (score > highScore) { highScore = score; onNewHighScore?.invoke(highScore) }
                sound.eatFruit()
                burst(item.x, item.y, cYellow)
                burst(item.x, item.y, cLime)
            } else {
                bombsEaten++
                hippoDizzyUntil = now + 1200
                sound.eatBomb()
                burst(item.x, item.y, Color.GRAY)
            }
        }
        items.removeAll(eaten.toSet())

        // ---- Detect and count dropped fruits ----
        val fellOff = items.filter { it.y > 1.15f }
        fellOff.forEach { item ->
            if (item.good) {
                fruitsDropped++
                sound.dropFruit()
            }
        }
        items.removeAll(fellOff.toSet())

        // ---- Check Game Over condition ----
        if (bombsEaten >= 3 || fruitsDropped >= 3) {
            sound.gameOver()   // stops music, plays game-over sting
            state = State.GAME_OVER
            items.clear()
        }
    }

    private fun burst(x: Float, y: Float, color: Int) {
        repeat(14) {
            val angle = Random.nextFloat() * (2 * Math.PI).toFloat()
            val speed = 0.25f + Random.nextFloat() * 0.45f
            particles.add(
                Particle(
                    x, y,
                    vx = speed * kotlin.math.cos(angle),
                    vy = speed * sin(angle) - 0.2f,
                    life = 1f,
                    color = color
                )
            )
        }
    }

    // ======================== RENDER ========================
    private fun render(c: Canvas) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        c.drawColor(cBg)
        drawStarfield(c, w, h)

        when (state) {
            State.WAITING_FOR_CAMERA -> drawCenteredMessage(c, w, h, "Looking for you…", "Stand in front of the screen! 👀")
            State.CALIBRATING -> drawCalibration(c, w, h)
            State.COUNTDOWN -> { drawWorld(c, w, h); drawCountdown(c, w, h) }
            State.PLAYING -> { drawWorld(c, w, h); drawHud(c, w, h) }
            State.PAUSED -> { drawWorld(c, w, h); drawCenteredMessage(c, w, h, "Paused", "Close the menu to keep playing!") }
            State.GAME_OVER -> { drawWorld(c, w, h); drawGameOver(c, w, h) }
        }
    }

    private fun drawStarfield(c: Canvas, w: Float, h: Float) {
        // Deterministic twinkly background dots — cheap and cheerful.
        paint.style = Paint.Style.FILL
        val t = System.currentTimeMillis() / 600.0
        for (i in 0 until 40) {
            val sx = ((i * 97) % 100) / 100f * w
            val sy = ((i * 53) % 100) / 100f * h
            val twinkle = (sin(t + i) * 0.5 + 0.5).toFloat()
            paint.color = Color.argb((40 + 90 * twinkle).toInt(), 255, 255, 255)
            c.drawCircle(sx, sy, 3f + 2f * twinkle, paint)
        }
    }

    // ---------------- Calibration screen ----------------
    private fun drawCalibration(c: Canvas, w: Float, h: Float) {
        val aligned = isFaceAligned()
        val cx = w / 2f
        val cy = h / 2f
        val radius = h * 0.30f
        val t = System.currentTimeMillis()

        // Big neon target circle: red/yellow → flashing green when aligned.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 14f
        paint.color = when {
            aligned && (t / 150) % 2 == 0L -> cLime
            aligned -> Color.parseColor("#33FF99")
            faceVisible -> cYellow
            else -> cRed
        }
        c.drawCircle(cx, cy, radius, paint)

        // Soft glow ring
        paint.strokeWidth = 4f
        paint.alpha = 90
        c.drawCircle(cx, cy, radius + 18f, paint)
        paint.alpha = 255

        // Live face dot so the child can "steer" into the circle.
        if (faceVisible) {
            paint.style = Paint.Style.FILL
            paint.color = cCyan
            c.drawCircle(faceX * w, faceY * h, 26f, paint)
        }

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.07f
        c.drawText("Put your face in the circle!", cx, cy - radius - h * 0.06f, textPaint)

        textPaint.textSize = h * 0.045f
        textPaint.color = when {
            !faceVisible -> cRed
            faceSize > MAX_FACE_SIZE -> cYellow
            faceSize < MIN_FACE_SIZE -> cYellow
            else -> cLime
        }
        val hint = when {
            !faceVisible -> "I can't see you yet! 🙈"
            faceSize > MAX_FACE_SIZE -> "Take a step back! ⬅️"
            faceSize < MIN_FACE_SIZE -> "Come a little closer! ➡️"
            !aligned -> "Almost there…"
            else -> "Perfect! Hold still… ⭐"
        }
        c.drawText(hint, cx, cy + radius + h * 0.09f, textPaint)
    }

    private fun drawCountdown(c: Canvas, w: Float, h: Float) {
        val elapsed = System.currentTimeMillis() - countdownStartMs
        val n = (3 - elapsed / 1000).coerceAtLeast(1)
        val phase = (elapsed % 1000) / 1000f
        textPaint.color = cMagenta
        textPaint.textSize = h * (0.35f - 0.1f * phase)   // shrinking pop
        c.drawText("$n", w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
    }

    // ---------------- Game world ----------------
    private fun drawWorld(c: Canvas, w: Float, h: Float) {
        items.forEach { drawItem(c, w, h, it) }
        drawHippo(c, w, h)
        paint.style = Paint.Style.FILL
        particles.forEach { p ->
            paint.color = p.color
            paint.alpha = (255 * p.life).toInt().coerceIn(0, 255)
            c.drawCircle(p.x * w, p.y * h, 8f * p.life + 3f, paint)
        }
        paint.alpha = 255
    }

    private fun drawItem(c: Canvas, w: Float, h: Float, item: Item) {
        val x = item.x * w
        val y = item.y * h
        val r = ITEM_HALF * h
        paint.style = Paint.Style.FILL
        if (item.good) when (item.kind) {
            0 -> { // watermelon slice
                paint.color = cLime
                c.drawArc(x - r, y - r, x + r, y + r, 0f, 180f, true, paint)
                paint.color = Color.parseColor("#FF5577")
                c.drawArc(x - r * .8f, y - r * .8f, x + r * .8f, y + r * .8f, 0f, 180f, true, paint)
            }
            1 -> { // banana
                paint.color = cYellow
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.55f
                paint.strokeCap = Paint.Cap.ROUND
                c.drawArc(x - r, y - r, x + r, y + r, 30f, 120f, false, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            else -> drawStar(c, x, y, r, cCyan)
        } else when (item.kind) {
            0 -> { // rock
                paint.color = Color.GRAY
                c.drawCircle(x, y, r * 0.9f, paint)
                paint.color = Color.DKGRAY
                c.drawCircle(x - r * 0.3f, y - r * 0.2f, r * 0.35f, paint)
            }
            else -> { // muddy boot
                paint.color = Color.parseColor("#6B4A2B")
                c.drawRect(x - r * .5f, y - r, x + r * .2f, y + r * .5f, paint)
                c.drawRect(x - r * .5f, y + r * .2f, x + r, y + r, paint)
            }
        }
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        val path = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = Math.PI / 5 * i - Math.PI / 2
            val px = x + rad * kotlin.math.cos(a).toFloat()
            val py = y + rad * sin(a).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawHippo(c: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val dizzy = now < hippoDizzyUntil
        val wobble = if (dizzy) sin(now / 40.0).toFloat() * 14f else 0f

        val cx = hippoX * w + wobble
        val cy = HIPPO_Y * h
        val rw = HIPPO_HALF_W * w
        val rh = HIPPO_HALF_H * h

        paint.style = Paint.Style.FILL
        // Body/head — friendly purple hippo
        paint.color = Color.parseColor("#9C6BFF")
        c.drawRoundRect(RectF(cx - rw, cy - rh, cx + rw, cy + rh), rh * .7f, rh * .7f, paint)
        // Snout
        paint.color = Color.parseColor("#B98CFF")
        c.drawRoundRect(RectF(cx - rw * .8f, cy - rh * .1f, cx + rw * .8f, cy + rh), rh * .6f, rh * .6f, paint)
        // Ears
        paint.color = Color.parseColor("#9C6BFF")
        c.drawCircle(cx - rw * .7f, cy - rh * 1.05f, rh * .35f, paint)
        c.drawCircle(cx + rw * .7f, cy - rh * 1.05f, rh * .35f, paint)
        // Open mouth (the "catcher")
        paint.color = Color.parseColor("#3A2353")
        c.drawArc(cx - rw * .55f, cy, cx + rw * .55f, cy + rh * .95f, 0f, 180f, true, paint)
        // Eyes — swirly when dizzy
        if (dizzy) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            paint.color = Color.WHITE
            c.drawCircle(cx - rw * .35f, cy - rh * .45f, rh * .22f, paint)
            c.drawCircle(cx + rw * .35f, cy - rh * .45f, rh * .22f, paint)
            paint.style = Paint.Style.FILL
        } else {
            paint.color = Color.WHITE
            c.drawCircle(cx - rw * .35f, cy - rh * .45f, rh * .26f, paint)
            c.drawCircle(cx + rw * .35f, cy - rh * .45f, rh * .26f, paint)
            paint.color = Color.BLACK
            c.drawCircle(cx - rw * .35f, cy - rh * .42f, rh * .12f, paint)
            c.drawCircle(cx + rw * .35f, cy - rh * .42f, rh * .12f, paint)
        }
        // Nostrils
        paint.color = Color.parseColor("#5E3DA8")
        c.drawCircle(cx - rw * .25f, cy + rh * .12f, rh * .09f, paint)
        c.drawCircle(cx + rw * .25f, cy + rh * .12f, rh * .09f, paint)
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        // Save current text alignment
        val originalAlign = textPaint.textAlign

        // Big bubbly score, top-center with outline for pop.
        textPaint.textSize = h * 0.12f
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 10f
        textPaint.color = cBg
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText("⭐ $score", w / 2f, h * 0.14f, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = cYellow
        c.drawText("⭐ $score", w / 2f, h * 0.14f, textPaint)

        // Best Score
        textPaint.textSize = h * 0.045f
        textPaint.color = cCyan
        c.drawText("Best: $highScore", w / 2f, h * 0.20f, textPaint)

        // Current Difficulty
        textPaint.textSize = h * 0.035f
        textPaint.color = Color.parseColor("#88FFFFFF")
        c.drawText("Difficulty: $difficulty", w / 2f, h * 0.25f, textPaint)

        // Draw Bombs Eaten indicator (top-left)
        textPaint.textSize = h * 0.04f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        c.drawText("Bombs: ", w * 0.05f, h * 0.08f, textPaint)
        val bombText = (1..3).joinToString(" ") { i ->
            if (i <= bombsEaten) "💥" else "⚪"
        }
        textPaint.color = cRed
        c.drawText(bombText, w * 0.05f + textPaint.measureText("Bombs: "), h * 0.08f, textPaint)

        // Draw Fruits Dropped indicator (top-right)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.WHITE
        val fruitsText = (1..3).joinToString(" ") { i ->
            if (i <= fruitsDropped) "❌" else "🍉"
        }
        val label = "Dropped: "
        c.drawText(fruitsText, w * 0.95f, h * 0.08f, textPaint)
        textPaint.color = cLime
        c.drawText(label, w * 0.95f - textPaint.measureText(fruitsText) - 10f, h * 0.08f, textPaint)

        // Restore original text alignment
        textPaint.textAlign = originalAlign
    }

    private fun drawCenteredMessage(c: Canvas, w: Float, h: Float, big: String, small: String) {
        textPaint.color = cCyan
        textPaint.textSize = h * 0.09f
        c.drawText(big, w / 2f, h / 2f - h * 0.02f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.05f
        c.drawText(small, w / 2f, h / 2f + h * 0.08f, textPaint)
    }

    private fun drawGameOver(c: Canvas, w: Float, h: Float) {
        // Semi-transparent overlay to dim the world
        paint.color = Color.parseColor("#D914122B")
        paint.style = Paint.Style.FILL
        c.drawRect(0f, 0f, w, h, paint)

        val cx = w / 2f
        val cy = h / 2f

        // "GAME OVER"
        textPaint.color = cRed
        textPaint.textSize = h * 0.10f
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText("GAME OVER", cx, cy - h * 0.15f, textPaint)

        // Reason
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.045f
        val reason = when {
            bombsEaten >= 3 && fruitsDropped >= 3 -> "Ate 3 bombs & dropped 3 fruits!"
            bombsEaten >= 3 -> "Ouch! You ate 3 bombs! 💣"
            else -> "Oops! You dropped 3 fruits! 🍉"
        }
        c.drawText(reason, cx, cy - h * 0.07f, textPaint)

        // Score info
        textPaint.textSize = h * 0.06f
        textPaint.color = cYellow
        c.drawText("Score: $score", cx, cy + h * 0.02f, textPaint)

        textPaint.textSize = h * 0.045f
        textPaint.color = cCyan
        c.drawText("Best score: $highScore", cx, cy + h * 0.08f, textPaint)

        // Tap to restart instructions with pulsing alpha
        textPaint.textSize = h * 0.05f
        textPaint.color = cLime
        val alpha = (180 + 75 * sin(System.currentTimeMillis() / 200.0)).toInt().coerceIn(0, 255)
        textPaint.alpha = alpha
        c.drawText("Tap screen to play again! 🎯", cx, cy + h * 0.18f, textPaint)
        textPaint.alpha = 255
    }

    // ======================== Tuning constants ========================
    private companion object {
        // Calibration
        const val CAL_RADIUS = 0.16f       // normalized half-extent of target zone
        const val MIN_FACE_SIZE = 0.10f    // too far if smaller
        const val MAX_FACE_SIZE = 0.45f    // too close if bigger

        // Hippo
        const val HIPPO_Y = 0.86f          // bottom 20% of the screen
        const val HIPPO_HALF_W = 0.07f
        const val HIPPO_HALF_H = 0.09f
        const val LERP_SPEED = 9f          // render-side smoothing factor

        // Items — gentle, child-friendly pace
        const val ITEM_HALF = 0.045f
        const val FALL_SPEED = 0.18f       // screen heights / second
        const val SPAWN_INTERVAL_MS = 1100L
    }
}
