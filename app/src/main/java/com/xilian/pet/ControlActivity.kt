package com.xilian.pet

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class ControlActivity : Activity() {

    private val stateKeys = listOf("open","half","closed","sleep","shy","read","swing0","swing1","swing2")
    private val stateLabels = listOf("睁眼","半睁","闭眼","睡觉","撒娇","看书","秋千1","秋千2","秋千3")
    private val stateRequired = listOf(true,true,true,false,false,false,false,false,false)
    private var pendingSlot: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply { setBackgroundColor(0xFFF8F4F0.toInt()); setPadding(dp(20), dp(40), dp(20), dp(40)) }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        card.addView(sectionTitle("动作"))
        card.addView(actionRow())
        card.addView(space(4))
        card.addView(wanderRow())
        card.addView(space(8))
        card.addView(opacityRow())
        card.addView(space(20))

        card.addView(sectionTitle("换装"))
        card.addView(subtitle("点击选择图片，至少需设 睁眼/半睁/闭眼"))
        card.addView(space(8))

        for (i in stateKeys.indices) {
            card.addView(imageSlotRow(stateKeys[i], stateLabels[i], stateRequired[i]))
        }

        card.addView(space(16))
        card.addView(fullBtn("恢复默认图片") { resetAllImages() })
        card.addView(space(8))
        card.addView(fullBtn("关闭") { finish() })

        root.addView(card)
        setContentView(root)
    }

    // ── action row ──

    private fun actionRow(): View {
        val actions: List<Pair<String, String>> = listOf(
            "秋千" to FloatingPetService.ACTION_SWING,
            "睡觉" to FloatingPetService.ACTION_SLEEP,
            "撒娇" to FloatingPetService.ACTION_SHY,
            "看书" to FloatingPetService.ACTION_READ
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            actions.forEach { (label, action) ->
                addView(smallBtn(label) { sendAction(action) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3),0,dp(3),0) })
            }
        }
    }

    private fun wanderRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val wLabel = if (FloatingPetService.wanderOn) "游走: 开" else "游走: 关"
        val bLabel = if (FloatingPetService.wanderBubbleOn) "气泡: 开" else "气泡: 关"
        addView(smallBtn(wLabel) {
            sendAction(FloatingPetService.ACTION_WANDER_TOGGLE)
            FloatingPetService.wanderOn = !FloatingPetService.wanderOn
            recreate()
        })
        addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 0) })
        addView(smallBtn(bLabel) {
            sendAction(FloatingPetService.ACTION_WANDER_BUBBLE_TOGGLE)
            FloatingPetService.wanderBubbleOn = !FloatingPetService.wanderBubbleOn
            recreate()
        })
    }

    // ── opacity ──

    private fun opacityRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val label = TextView(this).apply {
            text = "透明度 (100%)"; setTextColor(0xFF8B7B6B.toInt()); textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        row.addView(label)

        val seek = SeekBar(this).apply {
            max = 100; setPadding(dp(8), 0, dp(8), 0)
            // default 100% — but we don't know current value from service easily
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        label.text = "透明度 ($progress%)"
                        val intent = Intent(this@ControlActivity, FloatingPetService::class.java).apply {
                            action = FloatingPetService.ACTION_SET_OPACITY
                            putExtra("value", progress)
                        }
                        startService(intent)
                    }
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        row.addView(seek)
        row.addView(space(4))
        row.addView(smallBtn("显/隐") { sendAction(FloatingPetService.ACTION_TOGGLE) })
        return row
    }

    // ── image slot ──

    private fun imageSlotRow(key: String, label: String, required: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val star = if (required) " *" else ""
        row.addView(TextView(this).apply {
            text = "$label$star"; setTextColor(0xFF3A3028.toInt()); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f)
        })

        val preview = ImageView(this).apply {
            setBackgroundColor(0xFFE8DFD6.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(8),0,dp(8),0) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            // load existing saved image
            val saved = getSavedFile(key)
            if (saved.exists()) setImageURI(Uri.fromFile(saved))
        }
        row.addView(preview)

        row.addView(smallBtn("选择") {
            pendingSlot = key
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }
            startActivityForResult(intent, 100)
        })

        return row
    }

    // ── helpers ──

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 18f; setTextColor(0xFF3A3028.toInt())
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFFA09080.toInt()); setPadding(0,0,0,dp(4))
    }

    private fun smallBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setBackgroundColor(0xFF8B6B5A.toInt())
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun fullBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setBackgroundColor(0xFF6B5B4A.toInt())
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun space(dpVal: Int) = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dpVal))
    }

    // ── image persistence ──

    private fun getSavedFile(key: String): File {
        return File(filesDir, "pet_$key.png")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        val key = pendingSlot ?: return
        pendingSlot = null

        try {
            // copy to internal storage
            val input = contentResolver.openInputStream(uri) ?: return
            val saved = getSavedFile(key)
            FileOutputStream(saved).use { output -> input.copyTo(output) }
            input.close()

            // notify service to reload
            sendAction(FloatingPetService.ACTION_RELOAD_IMAGES)
        } catch (_: Exception) {}

        // refresh activity to show updated preview
        recreate()
    }

    private fun resetAllImages() {
        for (key in stateKeys) {
            getSavedFile(key).delete()
        }
        sendAction(FloatingPetService.ACTION_RELOAD_IMAGES)
        recreate()
    }

    private fun sendAction(action: String) {
        startService(Intent(this, FloatingPetService::class.java).apply { this.action = action })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
