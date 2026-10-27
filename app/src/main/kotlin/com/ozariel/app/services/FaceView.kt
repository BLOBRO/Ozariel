package com.ozariel.app.services

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Les humeurs du personnage Ozariel. */
enum class Mood { IDLE, LISTENING, THINKING, WORKING, HAPPY, SAD, SURPRISED, LOVE, WINK, GIGGLE, DIZZY, SLEEPY }

/**
 * Le visage d'Ozariel, dessiné en direct : deux yeux, une bouche, des joues et quelques effets
 * (points de réflexion, cœurs, Zzz, anneau d'écoute). Les traits glissent doucement d'une humeur à l'autre ;
 * l'animation s'arrête dès que le visage est immobile pour économiser la batterie.
 */
class FaceView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val oval = RectF()

    /** Les traits actuels, qui se rapprochent à chaque image de ceux de l'humeur visée. */
    private class Features(
        var eyeOpen: Float = 1f,     // 0 = fermés, 1 = ouverts
        var eyeSize: Float = 1f,     // grands yeux quand il écoute ou est surpris
        var smile: Float = 0.6f,     // -1 triste … 1 grand sourire
        var mouthOpen: Float = 0f,   // 0 = fermée, 1 = grande ouverte
        var brow: Float = 0f,        // 1 = sourcils froncés (concentré), -1 = sourcils tristes
        var blush: Float = 0f,       // joues roses
        var lookX: Float = 0f,       // direction du regard, -1 … 1
        var lookY: Float = 0f,
    )

    private val current = Features()
    private val target = Features()

    var mood: Mood = Mood.IDLE
        private set

    /** Les effets (points, cœurs, Zzz) s'affichent du côté libre de l'écran. */
    var effectsOnLeft: Boolean = false
        set(value) { field = value; animateFrame() }

    /** Volume de la voix pendant l'écoute, de 0 à 1. */
    var level: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); animateFrame() }

    /** Vrai pendant qu'Ozariel parle à voix haute : la bouche bouge. */
    var talking: Boolean = false
        set(value) { field = value; animateFrame() }

    private var squish = 0f          // petit écrasement quand on le touche
    private var bounceStart = 0L     // petit saut de joie ou rebond contre le bord
    private var blinkStart = 0L
    private var nextBlink = SystemClock.uptimeMillis() + 3000
    private var frameScheduled = false
    private val wakeUp = Runnable { animateFrame() }

    init {
        applyMood(Mood.IDLE)
    }

    fun setMood(newMood: Mood) {
        if (newMood == mood) return
        mood = newMood
        applyMood(newMood)
        if (newMood == Mood.HAPPY || newMood == Mood.LOVE || newMood == Mood.GIGGLE) bounce()
        animateFrame()
    }

    /** Fait regarder le personnage dans une direction (par exemple celle du doigt qui le déplace). */
    fun lookAt(x: Float, y: Float) {
        target.lookX = x.coerceIn(-1f, 1f)
        target.lookY = y.coerceIn(-1f, 1f)
        animateFrame()
    }

    fun squish() {
        squish = 1f
        animateFrame()
    }

    fun bounce() {
        bounceStart = SystemClock.uptimeMillis()
        animateFrame()
    }

    private fun applyMood(m: Mood) {
        val t = target
        t.eyeOpen = 1f; t.eyeSize = 1f; t.smile = 0.6f; t.mouthOpen = 0f; t.brow = 0f; t.blush = 0f
        t.lookX = 0f; t.lookY = 0f
        when (m) {
            Mood.IDLE -> Unit
            Mood.LISTENING -> { t.eyeSize = 1.25f; t.smile = 0.2f; t.mouthOpen = 0.15f }
            Mood.THINKING -> { t.lookX = 0.5f; t.lookY = -0.8f; t.smile = 0f }
            Mood.WORKING -> { t.brow = 1f; t.eyeOpen = 0.7f; t.smile = 0.1f; t.lookY = 0.3f }
            Mood.HAPPY -> { t.smile = 1f; t.mouthOpen = 0.45f; t.blush = 1f; t.eyeOpen = 0.9f }
            Mood.SAD -> { t.smile = -0.8f; t.brow = -1f; t.lookY = 0.5f; t.eyeOpen = 0.8f }
            Mood.SURPRISED -> { t.eyeSize = 1.35f; t.smile = 0f; t.mouthOpen = 0.7f }
            Mood.LOVE -> { t.smile = 0.9f; t.blush = 1f }
            Mood.WINK -> { t.smile = 0.8f; t.blush = 0.5f }
            Mood.GIGGLE -> { t.eyeOpen = 0f; t.smile = 1f; t.mouthOpen = 0.6f; t.blush = 1f }
            Mood.DIZZY -> { t.smile = -0.2f; t.mouthOpen = 0.3f }
            Mood.SLEEPY -> { t.eyeOpen = 0f; t.smile = 0.1f; t.lookY = 0.3f }
        }
    }

    /** Demande une nouvelle image au prochain rafraîchissement de l'écran. */
    private fun animateFrame() {
        if (!frameScheduled) {
            frameScheduled = true
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        frameScheduled = false
        val now = SystemClock.uptimeMillis()
        var moving = step(current::eyeOpen, target.eyeOpen) or step(current::eyeSize, target.eyeSize) or
            step(current::smile, target.smile) or step(current::mouthOpen, target.mouthOpen) or
            step(current::brow, target.brow) or step(current::blush, target.blush) or
            step(current::lookX, target.lookX) or step(current::lookY, target.lookY)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val radius = min(w, h) * 0.36f

        // Saut et écrasement.
        val bounceT = (now - bounceStart) / 450f
        val jump = if (bounceT in 0f..1f) sin(bounceT * PI).toFloat() * radius * 0.25f else 0f
        if (bounceT in 0f..1f) moving = true
        if (squish > 0.01f) { squish *= 0.85f; moving = true } else squish = 0f
        val sx = 1f + squish * 0.12f
        val sy = 1f - squish * 0.12f

        // Anneau d'écoute, qui grandit avec la voix.
        if (mood == Mood.LISTENING) {
            fill.color = Color.argb((60 + level * 120).toInt(), 239, 68, 68)
            canvas.drawCircle(cx, cy, radius * (1.12f + level * 0.25f), fill)
        }

        canvas.save()
        canvas.translate(cx, cy - jump)
        canvas.scale(sx, sy)

        // La tête.
        fill.color = if (mood == Mood.LISTENING) Color.rgb(239, 68, 68) else Color.rgb(79, 70, 229)
        canvas.drawCircle(0f, 0f, radius, fill)

        // Clignement des yeux de temps en temps, sauf quand ils sont déjà fermés.
        if (mood != Mood.SLEEPY && mood != Mood.GIGGLE && now >= nextBlink) {
            blinkStart = now
            nextBlink = now + Random.nextLong(3000, 6500)
        }
        val blinkT = (now - blinkStart) / 160f
        val blink = if (blinkT in 0f..2f) { moving = true; 1f - abs(1f - blinkT) } else 0f

        drawEyes(canvas, radius, (current.eyeOpen * (1f - blink)).coerceIn(0f, 1f), now)
        drawMouth(canvas, radius, now)
        if (talking) moving = true

        // Joues.
        if (current.blush > 0.02f) {
            fill.color = Color.argb((current.blush * 110).toInt(), 255, 140, 170)
            canvas.drawCircle(-radius * 0.55f, radius * 0.22f, radius * 0.14f, fill)
            canvas.drawCircle(radius * 0.55f, radius * 0.22f, radius * 0.14f, fill)
        }
        canvas.restore()

        moving = drawEffects(canvas, cx, cy - jump, radius, now) or moving
        if (moving) animateFrame()
        else {
            // Immobile : on se réveille seulement pour le prochain clignement.
            removeCallbacks(wakeUp)
            if (mood != Mood.SLEEPY) postDelayed(wakeUp, (nextBlink - now).coerceAtLeast(16))
        }
    }

    private fun drawEyes(canvas: Canvas, r: Float, open: Float, now: Long) {
        val eyeW = r * 0.2f * current.eyeSize
        val eyeH = r * 0.28f * current.eyeSize
        val ex = r * 0.34f
        val ey = -r * 0.12f
        val dx = current.lookX * r * 0.12f
        val dy = current.lookY * r * 0.1f
        stroke.color = Color.WHITE
        stroke.strokeWidth = r * 0.09f
        fill.color = Color.WHITE

        for (side in listOf(-1f, 1f)) {
            val x = side * ex + dx
            val y = ey + dy
            when {
                mood == Mood.DIZZY -> drawSpiral(canvas, x, y, eyeW * 1.1f, now)
                mood == Mood.LOVE -> drawHeart(canvas, x, y, eyeW * 1.3f, Color.WHITE)
                mood == Mood.WINK && side > 0 -> drawClosedEye(canvas, x, y, eyeW, happy = true)
                mood == Mood.GIGGLE || mood == Mood.HAPPY && open < 0.95f -> drawClosedEye(canvas, x, y, eyeW, happy = true)
                open < 0.12f -> drawClosedEye(canvas, x, y, eyeW, happy = false)
                else -> {
                    val half = eyeH * open
                    oval.set(x - eyeW, y - half, x + eyeW, y + half)
                    canvas.drawOval(oval, fill)
                }
            }
            // Sourcils : froncés (concentré) ou relevés au centre (triste).
            if (abs(current.brow) > 0.05f) {
                val by = y - eyeH - r * 0.08f
                val tilt = current.brow * r * 0.08f
                canvas.drawLine(x - eyeW * 1.1f, by + side * tilt, x + eyeW * 1.1f, by - side * tilt, stroke)
            }
        }
    }

    private fun drawClosedEye(canvas: Canvas, x: Float, y: Float, w: Float, happy: Boolean) {
        path.reset()
        if (happy) {
            // Yeux en « ^ ».
            path.moveTo(x - w, y + w * 0.4f)
            path.quadTo(x, y - w * 1.1f, x + w, y + w * 0.4f)
        } else {
            path.moveTo(x - w, y)
            path.quadTo(x, y + w * 0.6f, x + w, y)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawMouth(canvas: Canvas, r: Float, now: Long) {
        val mw = r * 0.38f
        val my = r * 0.38f
        val curve = current.smile * r * 0.22f
        var open = current.mouthOpen
        if (talking) open = max(open, 0.15f + 0.35f * abs(sin(now / 90.0)).toFloat())
        if (mood == Mood.LISTENING) open = max(open, level * 0.5f)

        stroke.color = Color.WHITE
        stroke.strokeWidth = r * 0.08f
        path.reset()
        path.moveTo(-mw, my - curve * 0.3f)
        path.quadTo(0f, my + curve, mw, my - curve * 0.3f)
        if (open > 0.05f) {
            // Bouche ouverte : on ferme la forme par une seconde courbe plus basse.
            path.quadTo(0f, my + curve + open * r * 0.45f, -mw, my - curve * 0.3f)
            fill.color = Color.rgb(40, 30, 90)
            canvas.drawPath(path, fill)
        }
        canvas.drawPath(path, stroke)
    }

    /** Effets autour de la tête. Renvoie vrai s'ils sont animés. */
    private fun drawEffects(canvas: Canvas, cx: Float, cy: Float, r: Float, now: Long): Boolean {
        val dir = if (effectsOnLeft) -1f else 1f
        return when (mood) {
        Mood.THINKING -> {
            // Trois points qui s'allument l'un après l'autre.
            for (i in 0..2) {
                val phase = ((now / 300) % 3).toInt()
                fill.color = if (i == phase) Color.rgb(79, 70, 229) else Color.argb(90, 79, 70, 229)
                canvas.drawCircle(cx + dir * (r * 0.3f + i * r * 0.24f), cy - r * 1.15f, r * 0.09f, fill)
            }
            true
        }
        Mood.SLEEPY -> {
            text.color = Color.rgb(79, 70, 229)
            text.textSize = r * 0.45f
            canvas.drawText("z", cx + dir * r * 0.75f, cy - r * 0.8f, text)
            text.textSize = r * 0.32f
            canvas.drawText("z", cx + dir * r * 0.95f, cy - r * 1.1f, text)
            false
        }
        Mood.LOVE -> {
            val t = (now % 1200) / 1200f
            drawHeart(canvas, cx + dir * r * 0.8f, cy - r * (0.8f + t * 0.35f), r * 0.18f, Color.argb(((1 - t) * 255).toInt(), 255, 90, 130))
            true
        }
        Mood.DIZZY -> true
        else -> false
        }
    }

    private fun drawSpiral(canvas: Canvas, x: Float, y: Float, size: Float, now: Long) {
        path.reset()
        val turn = (now % 1000) / 1000f * 2 * PI
        for (i in 0..24) {
            val a = turn + i * 0.55
            val d = size * i / 24f
            val px = x + (d * kotlin.math.cos(a)).toFloat()
            val py = y + (d * sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawHeart(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        fill.color = color
        path.reset()
        path.moveTo(x, y + size)
        path.cubicTo(x - size * 1.6f, y - size * 0.2f, x - size * 0.6f, y - size * 1.3f, x, y - size * 0.4f)
        path.cubicTo(x + size * 0.6f, y - size * 1.3f, x + size * 1.6f, y - size * 0.2f, x, y + size)
        canvas.drawPath(path, fill)
    }

    /** Rapproche un trait de sa valeur visée. Renvoie vrai s'il bouge encore. */
    private fun step(prop: kotlin.reflect.KMutableProperty0<Float>, goal: Float): Boolean {
        val value = prop.get()
        if (abs(value - goal) < 0.01f) {
            prop.set(goal)
            return false
        }
        prop.set(value + (goal - value) * 0.22f)
        return true
    }
}
