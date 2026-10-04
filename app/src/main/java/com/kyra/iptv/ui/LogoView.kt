package com.kyra.iptv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Logo do app desenhada em código (mesmo desenho do ícone): quadrado arredondado em degradê, play
 * branco e ondas de sinal. Com [animated] as ondas "pulsam" em sequência, indicando transmissão.
 */
class LogoView(context: Context, private val animated: Boolean = true) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val tri = Path()
    private val arc1 = RectF()
    private val arc2 = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF0B1B4D.toInt(), 0xFF00A8D6.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        rect.set(ox, oy, ox + s, oy + s)
        canvas.drawRoundRect(rect, s * 0.24f, s * 0.24f, bg)

        // Coordenadas do ícone (viewport 108): conteúdo entre ~(30,34) e (76,78); recorte 66x66 a partir de (21,21).
        val k = s / 66f
        fun x(v: Float) = ox + (v - 21f) * k
        fun y(v: Float) = oy + (v - 21f) * k
        stroke.strokeWidth = 4f * k
        fill.style = Paint.Style.FILL

        tri.reset()
        tri.moveTo(x(51f), y(36f)); tri.lineTo(x(51f), y(62f)); tri.lineTo(x(74f), y(49f)); tri.close()
        canvas.drawPath(tri, fill)
        stroke.alpha = 255
        canvas.drawPath(tri, stroke)

        // Ondas: arcos de 90° (topo → direita) centrados em (32,76).
        arc1.set(x(32f - 9f), y(76f - 9f), x(32f + 9f), y(76f + 9f))
        arc2.set(x(32f - 20f), y(76f - 20f), x(32f + 20f), y(76f + 20f))
        stroke.alpha = waveAlpha(0)
        canvas.drawArc(arc1, -90f, 90f, false, stroke)
        stroke.alpha = waveAlpha(1)
        canvas.drawArc(arc2, -90f, 90f, false, stroke)
    }

    /** Sem animação: ondas fixas. Com animação: cada onda acende na sua vez e apaga suavemente. */
    private fun waveAlpha(i: Int): Int {
        if (!animated) return if (i == 0) 255 else 204
        val d = abs(((phase * 3f) % 3f) - (i + 0.5f))
        return (255 * (0.25f + 0.75f * max(0f, 1f - d / 1.1f))).toInt().coerceIn(0, 255)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animated) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
