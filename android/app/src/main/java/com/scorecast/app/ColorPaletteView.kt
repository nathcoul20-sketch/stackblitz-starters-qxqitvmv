package com.scorecast.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Grille de pastilles de couleur (palette large, voir [Colors.palette]) + une dernière pastille
 * « + » qui ouvre le choix libre (teinte / saturation / luminosité, ou code hexadécimal).
 */
class ColorPaletteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var colors: List<Int> = Colors.palette
        set(v) { field = v; requestLayout(); invalidate() }

    var selected: Int = Colors.palette.first()
        set(v) { field = v; invalidate() }

    var onColorSelected: ((Int) -> Unit)? = null

    private val columns = 12
    private val density = resources.displayMetrics.density
    private val gap = 6 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }

    private val cellCount get() = colors.size + 1
    private val rows get() = (cellCount + columns - 1) / columns

    private fun cellSize(width: Int) = (width - paddingLeft - paddingRight - gap * (columns - 1)) / columns

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cell = cellSize(w)
        val h = (rows * cell + (rows - 1) * gap + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cell = cellSize(width)
        val r = cell / 2
        for (i in 0 until cellCount) {
            val cx = paddingLeft + (i % columns) * (cell + gap) + r
            val cy = paddingTop + (i / columns) * (cell + gap) + r
            if (i == colors.size) {
                // Pastille « couleur personnalisée »
                val custom = selected !in colors
                paint.color = if (custom) selected else Color.rgb(44, 48, 60)
                canvas.drawCircle(cx, cy, r * 0.92f, paint)
                plus.color = if (custom) Colors.onColor(selected) else Color.WHITE
                canvas.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, plus)
                canvas.drawLine(cx, cy - r * 0.4f, cx, cy + r * 0.4f, plus)
                if (custom) drawSelection(canvas, cx, cy, r)
                continue
            }
            val c = colors[i]
            paint.color = c
            canvas.drawCircle(cx, cy, r * 0.92f, paint)
            // Liseré discret autour des couleurs très sombres (visibles sur le fond noir)
            if (Colors.luminance(c) < 0.02) {
                ring.color = Color.rgb(70, 74, 86); ring.strokeWidth = 1 * density
                canvas.drawCircle(cx, cy, r * 0.92f, ring)
            }
            if (c == selected) drawSelection(canvas, cx, cy, r)
        }
    }

    private fun drawSelection(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        ring.color = Color.WHITE
        ring.strokeWidth = 2.5f * density
        canvas.drawCircle(cx, cy, r * 0.92f + 3 * density, ring)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val cell = cellSize(width)
        val col = ((event.x - paddingLeft) / (cell + gap)).toInt()
        val row = ((event.y - paddingTop) / (cell + gap)).toInt()
        if (col !in 0 until columns || row < 0) return true
        val i = row * columns + col
        when {
            i < colors.size -> choose(colors[i])
            i == colors.size -> showCustomDialog()
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun choose(color: Int) {
        selected = color
        onColorSelected?.invoke(color)
    }

    /** Couleur libre : teinte, saturation, luminosité, ou code hexadécimal (« #FFE600 »). */
    private fun showCustomDialog() {
        val hsv = FloatArray(3)
        Color.colorToHSV(selected, hsv)
        val pad = (20 * density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * density).toInt())
        }
        val hex = EditText(context).apply {
            hint = "#RRGGBB"
            setSingleLine()
            setText(Colors.hex(selected))
        }
        var current = selected
        var updatingHex = false
        fun refresh(fromHex: Boolean = false) {
            swatch.background = GradientDrawable().apply {
                setColor(current); cornerRadius = 14 * density
            }
            if (!fromHex) {
                updatingHex = true
                hex.setText(Colors.hex(current))
                updatingHex = false
            }
        }
        val bars = mutableListOf<SeekBar>()
        fun bar(label: String, max: Int, value: Int, onChange: (Int) -> Unit) {
            layout.addView(TextView(context).apply {
                text = label
                setPadding(0, (14 * density).toInt(), 0, 0)
            })
            layout.addView(SeekBar(context).apply {
                this.max = max
                progress = value
                bars += this
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        layout.addView(swatch)
        bar("Teinte", 360, hsv[0].toInt()) { hsv[0] = it.toFloat(); current = Color.HSVToColor(hsv); refresh() }
        bar("Intensité", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f; current = Color.HSVToColor(hsv); refresh() }
        bar("Luminosité", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f; current = Color.HSVToColor(hsv); refresh() }
        layout.addView(TextView(context).apply {
            text = "Code couleur"
            setPadding(0, (14 * density).toInt(), 0, 0)
        })
        layout.addView(hex)
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                val parsed = Colors.parse(s.toString()) ?: return
                current = parsed
                Color.colorToHSV(parsed, hsv)
                bars[0].progress = hsv[0].toInt()
                bars[1].progress = (hsv[1] * 100).toInt()
                bars[2].progress = (hsv[2] * 100).toInt()
                refresh(fromHex = true)
            }
        })
        refresh()
        AlertDialog.Builder(context)
            .setTitle("Couleur personnalisée")
            .setView(layout)
            .setPositiveButton("Choisir") { _, _ -> choose(current or 0xFF000000.toInt()) }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
