package com.kyra.iptv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.kyra.iptv.data.repository.PlaylistError

internal const val COLOR_PRIMARY = 0xFF1565C0.toInt()
internal const val COLOR_TEXT = 0xFF212121.toInt()
internal const val COLOR_MUTED = 0xFF757575.toInt()
internal const val COLOR_CHIP = 0xFFE0E0E0.toInt()
internal const val COLOR_STAR = 0xFFF9A825.toInt()

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** O app desenha sob as barras do sistema (targetSdk 35): afasta o conteúdo delas. */
internal fun View.applySystemBarsPadding() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        v.setPadding(
            l + insets.systemWindowInsetLeft,
            t + insets.systemWindowInsetTop,
            r + insets.systemWindowInsetRight,
            b + insets.systemWindowInsetBottom,
        )
        insets
    }
    requestApplyInsets()
}

internal fun Context.chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = 13f
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
        background = GradientDrawable().apply {
            setCornerRadius(dp(18).toFloat())
            setColor(if (selected) COLOR_PRIMARY else COLOR_CHIP)
        }
        setOnClickListener { onClick() }
    }

/** Mensagem segura para o usuário: erros esperados têm texto próprio; o resto não vaza detalhes (URLs/tokens). */
internal fun userMessage(error: Throwable): String =
    if (error is PlaylistError) error.message ?: "Erro" else "Erro inesperado"
