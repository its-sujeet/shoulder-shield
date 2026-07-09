package com.privacyguard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Manages decoy overlay views that are shown instead of a blank blur when
 * a privacy threat is detected. Decoys mask the protected content with a
 * plausible-looking app (calculator, notes, weather) so onlookers see
 * something innocuous rather than a suspicious black overlay.
 */
class DecoyScreenManager(private val context: Context) {

    enum class DecoyType { CALCULATOR, NOTES, WEATHER }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var decoyView: View? = null

    /**
     * Show a decoy overlay of the given type.
     * Removes any existing decoy first.
     */
    fun showDecoy(context: Context, type: DecoyType) {
        dismiss()

        val view = when (type) {
            DecoyType.CALCULATOR -> createCalculatorView(context)
            DecoyType.NOTES -> createNotesView(context)
            DecoyType.WEATHER -> createWeatherView(context)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply { dimAmount = 0.0f } // No dim — decoy should look real

        try {
            windowManager.addView(view, params)
            decoyView = view
        } catch (_: SecurityException) {
            decoyView = null
        }
    }

    /** Returns true if a decoy overlay is currently visible. */
    fun isShowing(): Boolean = decoyView != null

    /** Remove the decoy overlay. */
    fun dismiss() {
        decoyView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // View wasn't attached
            }
            decoyView = null
        }
    }

    fun destroy() {
        dismiss()
    }

    // ──────────────────────────────────────────────
    // Decoy UI builders (programmatic, no layout XML)
    // ──────────────────────────────────────────────

    private fun createCalculatorView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Display
        val display = TextView(context).apply {
            text = "0"
            textSize = 56f
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.BOTTOM
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(display)

        // Button grid
        val grid = GridLayout(context).apply {
            columnCount = 4
            rowCount = 5
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val buttonLabels = arrayOf(
            "AC", "⌫", "%", "÷",
            "7", "8", "9", "×",
            "4", "5", "6", "−",
            "1", "2", "3", "+",
            "0", ".", "±", "="
        )

        for (label in buttonLabels) {
            val button = Button(context).apply {
                text = label
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#2C2C2E"))
                val isOperator = label in arrayOf("÷", "×", "−", "+", "=")
                val isClear = label in arrayOf("AC", "⌫")
                if (isOperator) {
                    setBackgroundColor(Color.parseColor("#FF9F0A"))
                } else if (isClear) {
                    setBackgroundColor(Color.parseColor("#3A3A3C"))
                }
                val lp = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
                layoutParams = lp
            }
            grid.addView(button)
        }

        root.addView(grid)
        return root
    }

    private fun createNotesView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = TextView(context).apply {
            text = "Notes"
            textSize = 34f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            setPadding(24, 48, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(header)

        // Body — blank note area
        val scrollContent = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val body = TextView(context).apply {
            text = ""
            textSize = 18f
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.START
            setPadding(24, 16, 24, 16)
            hint = "Start writing..."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContent.addView(body)
        root.addView(scrollContent)

        return root
    }

    private fun createWeatherView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#4A90D9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // City
        val city = TextView(context).apply {
            text = "San Francisco"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(32, 56, 32, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(city)

        // Temperature
        val temp = TextView(context).apply {
            text = "72°F"
            textSize = 80f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(temp)

        // Condition
        val condition = TextView(context).apply {
            text = "☀️ Sunny"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(32, 8, 32, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(condition)

        // Forecast items
        val forecastData = arrayOf(
            "Mon" to "68°F",
            "Tue" to "71°F",
            "Wed" to "65°F",
            "Thu" to "70°F",
            "Fri" to "73°F"
        )

        val forecastRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for ((day, deg) in forecastData) {
            val dayCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            dayCol.addView(TextView(context).apply {
                text = day
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            dayCol.addView(TextView(context).apply {
                text = deg
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            forecastRow.addView(dayCol)
        }

        root.addView(forecastRow)
        return root
    }
}
