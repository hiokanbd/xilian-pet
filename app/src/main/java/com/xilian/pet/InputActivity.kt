package com.xilian.pet

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.concurrent.thread

class InputActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // dim background
        window.setFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setDimAmount(0.5f)
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() } // tap outside to dismiss
        }

        val card = createCard()
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            setMargins(48, 0, 48, 0)
        }
        root.addView(card, params)

        setContentView(root)
    }

    private fun createCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
            setBackgroundColor(Color.WHITE)
            elevation = 12f
            radius = 24f  // clipToOutline is auto if setOutlineProvider?
            clipToOutline = true
        }

        val title = TextView(this).apply {
            text = "跟人家说什么？"
            textSize = 18f
            setTextColor(0xFF3A3028.toInt())
            setPadding(0, 0, 0, 16)
        }
        card.addView(title)

        val input = EditText(this).apply {
            hint = "在这里写…"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(20, 14, 20, 14)
            setTextColor(0xFF3A3028.toInt())
            setHintTextColor(0xFFBBAFA5.toInt())
            setBackgroundColor(0xFFF8F4F0.toInt())
            radius = 12f
            clipToOutline = true
            gravity = Gravity.TOP
        }
        card.addView(input)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 18, 0, 0)
        }

        val cancelBtn = TextView(this).apply {
            text = "取消"
            textSize = 15f
            setTextColor(0xFF8B7B6B.toInt())
            setPadding(32, 12, 32, 12)
            setOnClickListener { finish() }
        }
        buttonRow.addView(cancelBtn)

        val sendBtn = TextView(this).apply {
            text = "发送"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(40, 14, 40, 14)
            setBackgroundColor(0xFF8B6B5A.toInt())
            radius = 20f
            clipToOutline = true
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendToTermux(text)
                    finish()
                }
            }
        }
        buttonRow.addView(sendBtn)

        card.addView(buttonRow)

        // submit on keyboard action
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendToTermux(text)
                    finish()
                }
                true
            } else false
        }

        // auto-show keyboard
        input.postDelayed({ input.requestFocus() }, 200)

        return card
    }

    private fun sendToTermux(text: String) {
        postChatting(true)
        postBubble("…")

        PetBridge.streamChat(
            text = text,
            onToken = { partial -> postBubble(partial) },
            onComplete = { full ->
                postChatting(false)
                postBubble(full)
            },
            onError = { err ->
                postChatting(false)
                postBubble(err)
            }
        )
    }

    private fun postChatting(active: Boolean) {
        thread {
            try {
                val url = java.net.URL("http://127.0.0.1:${PetBridge.BRIDGE_PORT}/chatting")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().apply { put("active", active) }.toString()
                conn.outputStream.write(body.toByteArray())
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    private fun postBubble(text: String) {
        thread {
            try {
                val url = java.net.URL("http://127.0.0.1:${PetBridge.BRIDGE_PORT}/bubble")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().apply {
                    put("text", text.take(300))
                }.toString()
                conn.outputStream.write(body.toByteArray())
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    // extension helpers
    private var View.radius: Float
        get() = 0f
        set(value) {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, value)
                }
            }
        }
}
