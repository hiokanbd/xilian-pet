package com.xilian.pet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class ControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(32, 48, 32, 48)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 40)
        }

        card.addView(title("昔涟 · 桌宠控制"))
        card.addView(space(16))
        card.addView(btn("秋千") { sendAction(FloatingPetService.ACTION_SWING) })
        card.addView(btn("睡觉") { sendAction(FloatingPetService.ACTION_SLEEP) })
        card.addView(btn("撒娇") { sendAction(FloatingPetService.ACTION_SHY) })
        card.addView(btn("看书") { sendAction(FloatingPetService.ACTION_READ) })
        card.addView(space(8))
        card.addView(btn("隐藏/显示") { sendAction(
            FloatingPetService.ACTION_TOGGLE
        )})
        card.addView(space(16))
        card.addView(subtitle("透明度"))
        card.addView(row(
            btn("-10%") { sendAction(FloatingPetService.ACTION_OPACITY_DOWN) },
            btn("+10%") { sendAction(FloatingPetService.ACTION_OPACITY_UP) }
        ))
        card.addView(space(24))
        card.addView(btn("关闭控制面板") { finish() })

        root.addView(card)
        setContentView(root)
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, FloatingPetService::class.java).apply { this.action = action }
        startService(intent)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 22f; setTextColor(0xFF3A3028.toInt())
        gravity = Gravity.CENTER; setPadding(0, 8, 0, 8)
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(0xFF8B7B6B.toInt())
        setPadding(0, 0, 0, 8)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF8B6B5A.toInt())
        setOnClickListener { onClick() }
        val p = dp(14)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, dp(4))
        layoutParams = lp
        setPadding(p, p, p, p)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            addView(v, lp)
        }
    }

    private fun space(dpVal: Int) = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpVal))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
