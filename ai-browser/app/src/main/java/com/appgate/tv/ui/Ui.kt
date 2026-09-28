package com.appgate.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny programmatic-UI helpers shared by the screens (no layout XML, no design library). */
object Ui {
    val bg: Int = Color.rgb(11, 16, 24)
    val card: Int = Color.rgb(28, 37, 52)
    val text: Int = Color.WHITE
    val muted: Int = Color.rgb(170, 190, 215)
    val good: Int = Color.rgb(145, 220, 155)
    val warn: Int = Color.rgb(230, 210, 150)
    val bad: Int = Color.rgb(235, 175, 145)
    val accent: Int = Color.rgb(135, 190, 255)

    fun text(context: Context, value: String, sp: Float = 14f, color: Int = text, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = value
        textSize = sp
        setTextColor(color)
        gravity = Gravity.START
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun button(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    fun row(context: Context, vararg buttons: Button): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    fun column(context: Context, padding: Int = 24): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 14, 16, 14)
        setBackgroundColor(card)
    }

    fun cardParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }

    fun money(v: Int?): String = if (v == null) "" else "$" + "%,d".format(v)
    fun number(v: Int?): String = if (v == null) "" else "%,d".format(v)
}
