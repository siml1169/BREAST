package de.breast.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import de.breast.telemetry.EmpiricalCurveResult
import de.breast.telemetry.HeatPumpTelemetrySnapshot
import de.breast.telemetry.HeatingCurveAnalyzer
import kotlin.math.hypot
import kotlin.math.pow

class HeatingCurveChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isDarkMode: Boolean = true
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var snapshots: List<HeatPumpTelemetrySnapshot> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var targetSetpointTempC: Double? = null
        set(value) {
            field = value
            invalidate()
        }

    var empiricalCurve: EmpiricalCurveResult? = null
        set(value) {
            field = value
            invalidate()
        }

    var currentOutdoorTempC: Double? = null
        set(value) {
            field = value
            invalidate()
        }

    var currentSupplyTempC: Double? = null
        set(value) {
            field = value
            invalidate()
        }

    // --- Overlay Toggles ---
    var showHpcCurve1: Boolean = true       // Bild 2 [1] (45°C @ -10°C, max 48°C) Standard Underfloor
        set(value) { field = value; invalidate() }

    var showHpcCurve2: Boolean = true       // Bild 2 [2] (40°C @ -10°C) Optimized Underfloor
        set(value) { field = value; invalidate() }

    var showHpcCurve3: Boolean = false      // Bild 2 [3] (35°C @ -20°C) Low-energy building
        set(value) { field = value; invalidate() }

    var showHpcCurve4: Boolean = false      // Bild 4 (35°C @ -10°C) Standard Flat Underfloor
        set(value) { field = value; invalidate() }

    var showHpcCurve5: Boolean = false      // Bild 5 (60°C @ -10°C) Radiators
        set(value) { field = value; invalidate() }

    var showEmpiricalCurve: Boolean = false // Empirical regression disabled (not reflective of physical heating curve)
        set(value) { field = value; invalidate() }

    var showScatterSteadyState: Boolean = true // Equilibrium points (>= 20 min)
        set(value) { field = value; invalidate() }

    var showScatterWarmup: Boolean = false  // Warm-up ramp transient points (< 20 min)
        set(value) { field = value; invalidate() }

    var showLivePoint: Boolean = true       // Live operating point crosshairs
        set(value) { field = value; invalidate() }

    var parallelShiftK: Double = 0.0        // Parallelverschiebung room target offset in K
        set(value) { field = value; invalidate() }

    // Click callback for fullscreen launch
    var onChartClickListener: (() -> Unit)? = null

    // Touch selection & tap gesture tracking
    private var touchOutdoorTempC: Double? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTimeMs: Long = 0L

    // Chart bounds & scales
    private val minOutdoorTemp = -15.0
    private val maxOutdoorTemp = 20.0
    private val minFlowTemp = 18.0
    private val maxFlowTemp = 55.0

    // Paints
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Reference Curves Paints
    private val boschCurve1Paint = Paint(Paint.ANTI_ALIAS_FLAG) // Sky-Blue
    private val boschCurve2Paint = Paint(Paint.ANTI_ALIAS_FLAG) // Indigo
    private val boschCurve3Paint = Paint(Paint.ANTI_ALIAS_FLAG) // Violet
    private val boschCurve4Paint = Paint(Paint.ANTI_ALIAS_FLAG) // Cyan
    private val boschCurve5Paint = Paint(Paint.ANTI_ALIAS_FLAG) // Coral / Orange

    private val empiricalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val steadyPointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val steadyPointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val warmupPointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val warmupPointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val livePointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val path = Path()
    private val curveAnalyzer = HeatingCurveAnalyzer()

    init {
        updatePaints()
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        )
    }

    private fun updatePaints() {
        val gridColor = if (isDarkMode) Color.parseColor("#263238") else Color.parseColor("#E2E8F0")
        val axisColor = if (isDarkMode) Color.parseColor("#455A64") else Color.parseColor("#CBD5E1")
        val labelColor = if (isDarkMode) Color.parseColor("#90A4AE") else Color.parseColor("#64748B")

        gridPaint.apply {
            color = gridColor
            strokeWidth = dp(1f)
            style = Paint.Style.STROKE
        }

        axisPaint.apply {
            color = axisColor
            strokeWidth = dp(1.5f)
            style = Paint.Style.STROKE
        }

        labelPaint.apply {
            color = labelColor
            textSize = sp(10.5f)
            textAlign = Paint.Align.CENTER
        }

        // Curve [1] - Sky Blue
        boschCurve1Paint.apply {
            color = if (isDarkMode) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
            strokeWidth = dp(2.2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        }

        // Curve [2] - Indigo
        boschCurve2Paint.apply {
            color = if (isDarkMode) Color.parseColor("#818CF8") else Color.parseColor("#6366F1")
            strokeWidth = dp(2.0f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
        }

        // Curve [3] - Violet / Purple
        boschCurve3Paint.apply {
            color = if (isDarkMode) Color.parseColor("#C084FC") else Color.parseColor("#9333EA")
            strokeWidth = dp(2.0f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
        }

        // Curve [4] - Cyan
        boschCurve4Paint.apply {
            color = if (isDarkMode) Color.parseColor("#22D3EE") else Color.parseColor("#0891B2")
            strokeWidth = dp(2.0f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
        }

        // Curve [5] - Coral / Orange
        boschCurve5Paint.apply {
            color = if (isDarkMode) Color.parseColor("#FB923C") else Color.parseColor("#EA580C")
            strokeWidth = dp(2.2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(3f)), 0f)
        }

        // Empirical regression - Emerald Green
        empiricalPaint.apply {
            color = if (isDarkMode) Color.parseColor("#10B981") else Color.parseColor("#059669")
            strokeWidth = dp(2.8f)
            style = Paint.Style.STROKE
            pathEffect = null
        }

        // Steady state points - Emerald
        steadyPointFillPaint.apply {
            color = if (isDarkMode) Color.parseColor("#4410B981") else Color.parseColor("#5510B981")
            style = Paint.Style.FILL
        }
        steadyPointStrokePaint.apply {
            color = if (isDarkMode) Color.parseColor("#10B981") else Color.parseColor("#047857")
            strokeWidth = dp(1.2f)
            style = Paint.Style.STROKE
        }

        // Warmup ramp points - Amber
        warmupPointFillPaint.apply {
            color = if (isDarkMode) Color.parseColor("#44F59E0B") else Color.parseColor("#55F59E0B")
            style = Paint.Style.FILL
        }
        warmupPointStrokePaint.apply {
            color = if (isDarkMode) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
            strokeWidth = dp(1.2f)
            style = Paint.Style.STROKE
        }

        livePointPaint.apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = dp(2f)
        }

        touchGuidePaint.apply {
            color = if (isDarkMode) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
            strokeWidth = dp(1.2f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
        }

        tooltipBgPaint.apply {
            color = if (isDarkMode) Color.parseColor("#1E293B") else Color.parseColor("#0F172A")
            style = Paint.Style.FILL
            setShadowLayer(dp(3f), 0f, dp(2f), Color.parseColor("#40000000"))
        }

        tooltipTextPaint.apply {
            color = Color.WHITE
            textSize = sp(10.5f)
            textAlign = Paint.Align.LEFT
        }
    }

    private fun xToPx(tempOut: Double, left: Float, width: Float): Float {
        val fraction = (tempOut - minOutdoorTemp) / (maxOutdoorTemp - minOutdoorTemp)
        return (left + fraction * width).toFloat()
    }

    private fun yToPx(tempFlow: Double, top: Float, height: Float): Float {
        val fraction = (tempFlow - minFlowTemp) / (maxFlowTemp - minFlowTemp)
        return (top + height - fraction * height).toFloat()
    }

    private fun pxToOutdoorTemp(x: Float, left: Float, width: Float): Double {
        val fraction = ((x - left) / width).coerceIn(0f, 1f)
        return minOutdoorTemp + fraction * (maxOutdoorTemp - minOutdoorTemp)
    }

    /**
     * Calculates the exact flow temperature according to Bosch HPC 410 manual (Bild 2, 3 & 4).
     */
    fun boschHpc410UnderfloorFlowTemp(
        tempOut: Double,
        tDesign: Double = 45.0,
        tMinOut: Double = -10.0,
        maxFlow: Double = 48.0,
        roomOffsetK: Double = parallelShiftK
    ): Double {
        val baseFlow = 25.0 + roomOffsetK * 1.33
        val designFlow = (tDesign + roomOffsetK * 1.33).coerceAtMost(maxFlow)
        return if (tempOut >= tMinOut) {
            val fraction = ((20.0 - tempOut) / (20.0 - tMinOut)).coerceAtLeast(0.0)
            (baseFlow + (designFlow - baseFlow) * fraction.pow(0.92)).coerceIn(minFlowTemp, maxFlow)
        } else {
            maxFlow
        }
    }

    /**
     * Calculates radiator flow temperature according to Bosch HPC 410 manual (Bild 5).
     */
    fun boschHpc410RadiatorFlowTemp(
        tempOut: Double,
        tDesign: Double = 60.0,
        tMinOut: Double = -10.0,
        maxFlow: Double = 65.0,
        roomOffsetK: Double = parallelShiftK
    ): Double {
        val baseFlow = 25.0 + roomOffsetK * 1.33
        val designFlow = (tDesign + roomOffsetK * 1.33).coerceAtMost(maxFlow)
        return if (tempOut >= tMinOut) {
            val fraction = ((20.0 - tempOut) / (20.0 - tMinOut)).coerceAtLeast(0.0)
            (baseFlow + (designFlow - baseFlow) * fraction.pow(0.85)).coerceIn(minFlowTemp, maxFlow)
        } else {
            maxFlow
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padLeft = dp(38f)
        val padRight = dp(16f)
        val padTop = dp(26f)
        val padBottom = dp(28f)

        val chartWidth = width - padLeft - padRight
        val chartHeight = height - padTop - padBottom

        if (chartWidth <= 0 || chartHeight <= 0) return

        // 1. Draw Grid Lines and Labels
        val xSteps = listOf(-15, -10, -5, 0, 5, 10, 15, 20)
        for (tx in xSteps) {
            val px = xToPx(tx.toDouble(), padLeft, chartWidth)
            canvas.drawLine(px, padTop, px, padTop + chartHeight, gridPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${tx}°", px, padTop + chartHeight + dp(14f), labelPaint)
        }

        val ySteps = listOf(20, 25, 30, 35, 40, 45, 50, 55)
        labelPaint.textAlign = Paint.Align.RIGHT
        for (ty in ySteps) {
            val py = yToPx(ty.toDouble(), padTop, chartHeight)
            canvas.drawLine(padLeft, py, padLeft + chartWidth, py, gridPaint)
            canvas.drawText("${ty}°C", padLeft - dp(6f), py + dp(3.5f), labelPaint)
        }

        // 2. Draw Main Axes
        canvas.drawLine(padLeft, padTop, padLeft, padTop + chartHeight, axisPaint)
        canvas.drawLine(padLeft, padTop + chartHeight, padLeft + chartWidth, padTop + chartHeight, axisPaint)

        val numSampleSteps = 35

        // 3. Draw HPC 410 Curve [1] - Default Underfloor 45°C @ -10°C, max 48°C (Sky-Blue)
        if (showHpcCurve1) {
            path.reset()
            for (i in 0..numSampleSteps) {
                val tOut = minOutdoorTemp + i * (maxOutdoorTemp - minOutdoorTemp) / numSampleSteps
                val tFlow = boschHpc410UnderfloorFlowTemp(tOut, tDesign = 45.0, tMinOut = -10.0, maxFlow = 48.0)
                val px = xToPx(tOut, padLeft, chartWidth)
                val py = yToPx(tFlow, padTop, chartHeight)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, boschCurve1Paint)
        }

        // 3b. Draw HPC 410 Curve [2] - Optimized Underfloor 40°C @ -10°C (Indigo)
        if (showHpcCurve2) {
            path.reset()
            for (i in 0..numSampleSteps) {
                val tOut = minOutdoorTemp + i * (maxOutdoorTemp - minOutdoorTemp) / numSampleSteps
                val tFlow = boschHpc410UnderfloorFlowTemp(tOut, tDesign = 40.0, tMinOut = -10.0, maxFlow = 48.0)
                val px = xToPx(tOut, padLeft, chartWidth)
                val py = yToPx(tFlow, padTop, chartHeight)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, boschCurve2Paint)
        }

        // 3c. Draw HPC 410 Curve [3] - Low-Energy Building 35°C @ -20°C (Violet)
        if (showHpcCurve3) {
            path.reset()
            for (i in 0..numSampleSteps) {
                val tOut = minOutdoorTemp + i * (maxOutdoorTemp - minOutdoorTemp) / numSampleSteps
                val tFlow = boschHpc410UnderfloorFlowTemp(tOut, tDesign = 35.0, tMinOut = -20.0, maxFlow = 45.0)
                val px = xToPx(tOut, padLeft, chartWidth)
                val py = yToPx(tFlow, padTop, chartHeight)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, boschCurve3Paint)
        }

        // 3d. Draw HPC 410 Curve [4] - Bild 4 Underfloor Standard 35°C @ -10°C (Cyan)
        if (showHpcCurve4) {
            path.reset()
            for (i in 0..numSampleSteps) {
                val tOut = minOutdoorTemp + i * (maxOutdoorTemp - minOutdoorTemp) / numSampleSteps
                val tFlow = boschHpc410UnderfloorFlowTemp(tOut, tDesign = 35.0, tMinOut = -10.0, maxFlow = 45.0)
                val px = xToPx(tOut, padLeft, chartWidth)
                val py = yToPx(tFlow, padTop, chartHeight)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, boschCurve4Paint)
        }

        // 3e. Draw HPC 410 Curve [5] - Bild 5 Radiators 60°C @ -10°C (Coral)
        if (showHpcCurve5) {
            path.reset()
            for (i in 0..numSampleSteps) {
                val tOut = minOutdoorTemp + i * (maxOutdoorTemp - minOutdoorTemp) / numSampleSteps
                val tFlow = boschHpc410RadiatorFlowTemp(tOut, tDesign = 60.0, tMinOut = -10.0, maxFlow = 65.0)
                val px = xToPx(tOut, padLeft, chartWidth)
                val py = yToPx(tFlow, padTop, chartHeight)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, boschCurve5Paint)
        }

        // 4. Note: Linear regression empirical fit omitted as physical curves dominate.
        // 5. Draw Scatter Points of Central Heating Runs
        val classified = curveAnalyzer.classifySnapshots(snapshots)
        val ptRadius = dp(3.5f)
        val targetSp = targetSetpointTempC
        for (item in classified) {
            val snap = item.snapshot
            if (!snap.isHeatingActive) continue
            // Discard scatter points that belong to an erroneous/mismatched target setpoint
            if (targetSp != null && snap.setpointTempC != null && kotlin.math.abs(snap.setpointTempC - targetSp) > 1.0) {
                continue
            }
            val out = snap.outdoorTempC ?: continue
            val flow = snap.supplyTempC ?: continue
            if (out !in minOutdoorTemp..maxOutdoorTemp || flow !in minFlowTemp..maxFlowTemp) continue

            val px = xToPx(out, padLeft, chartWidth)
            val py = yToPx(flow, padTop, chartHeight)

            if (item.isSteadyState) {
                if (showScatterSteadyState) {
                    canvas.drawCircle(px, py, ptRadius, steadyPointFillPaint)
                    canvas.drawCircle(px, py, ptRadius, steadyPointStrokePaint)
                }
            } else {
                if (showScatterWarmup) {
                    canvas.drawCircle(px, py, ptRadius, warmupPointFillPaint)
                    canvas.drawCircle(px, py, ptRadius, warmupPointStrokePaint)
                }
            }
        }

        // 6. Draw Current Live Heat Pump Operating Point (Red / Amber crosshair)
        val liveOut = currentOutdoorTempC
        val liveFlow = currentSupplyTempC
        if (showLivePoint && liveOut != null && liveFlow != null &&
            liveOut in minOutdoorTemp..maxOutdoorTemp &&
            liveFlow in minFlowTemp..maxFlowTemp
        ) {
            val px = xToPx(liveOut, padLeft, chartWidth)
            val py = yToPx(liveFlow, padTop, chartHeight)

            canvas.drawLine(px, padTop, px, padTop + chartHeight, touchGuidePaint)
            canvas.drawLine(padLeft, py, padLeft + chartWidth, py, touchGuidePaint)

            val pulseRadius = dp(6.5f)
            steadyPointFillPaint.color = Color.parseColor("#44EF4444")
            canvas.drawCircle(px, py, pulseRadius, steadyPointFillPaint)
            canvas.drawCircle(px, py, dp(4.5f), livePointPaint)
        }

        // 7. Draw Interactive Touch Tooltip
        touchOutdoorTempC?.let { selOut ->
            val px = xToPx(selOut, padLeft, chartWidth)
            canvas.drawLine(px, padTop, px, padTop + chartHeight, touchGuidePaint)

            val lines = mutableListOf<Pair<String, Int>>()
            lines.add(Pair(String.format("Outdoor: %.1f°C", selOut), Color.WHITE))

            if (showHpcCurve1) {
                val f1 = boschHpc410UnderfloorFlowTemp(selOut, 45.0, -10.0)
                lines.add(Pair(String.format("HPC [1] (45°): %.1f°C", f1), boschCurve1Paint.color))
            }
            if (showHpcCurve2) {
                val f2 = boschHpc410UnderfloorFlowTemp(selOut, 40.0, -10.0)
                lines.add(Pair(String.format("HPC [2] (40°): %.1f°C", f2), boschCurve2Paint.color))
            }
            if (showHpcCurve3) {
                val f3 = boschHpc410UnderfloorFlowTemp(selOut, 35.0, -20.0)
                lines.add(Pair(String.format("HPC [3] (35° LowE): %.1f°C", f3), boschCurve3Paint.color))
            }
            if (showHpcCurve4) {
                val f4 = boschHpc410UnderfloorFlowTemp(selOut, 35.0, -10.0)
                lines.add(Pair(String.format("HPC [4] (35° FBH): %.1f°C", f4), boschCurve4Paint.color))
            }
            if (showHpcCurve5) {
                val f5 = boschHpc410RadiatorFlowTemp(selOut, 60.0, -10.0)
                lines.add(Pair(String.format("HPC [5] (60° Rad): %.1f°C", f5), boschCurve5Paint.color))
            }

            val boxWidth = dp(160f)
            val lineHeight = dp(14f)
            val boxHeight = dp(10f) + lines.size * lineHeight
            val boxX = if (px + boxWidth + dp(8f) > width - padRight) px - boxWidth - dp(8f) else px + dp(8f)
            val boxY = padTop + dp(4f)

            val rect = RectF(boxX, boxY, boxX + boxWidth, boxY + boxHeight)
            canvas.drawRoundRect(rect, dp(6f), dp(6f), tooltipBgPaint)

            var curY = boxY + dp(12f)
            for ((text, color) in lines) {
                tooltipTextPaint.color = color
                canvas.drawText(text, boxX + dp(8f), curY, tooltipTextPaint)
                curY += lineHeight
            }
            tooltipTextPaint.color = Color.WHITE
        }

        // 8. Draw Legend Header along Top
        val legendY = padTop - dp(8f)
        val legendPaint = Paint(labelPaint).apply {
            textSize = sp(9.5f)
            textAlign = Paint.Align.LEFT
        }

        var curX = padLeft
        if (showHpcCurve1) {
            canvas.drawLine(curX, legendY - dp(3f), curX + dp(12f), legendY - dp(3f), boschCurve1Paint)
            curX += dp(16f)
            canvas.drawText("HPC[1] 45°", curX, legendY, legendPaint)
            curX += dp(66f)
        }
        if (showHpcCurve2) {
            canvas.drawLine(curX, legendY - dp(3f), curX + dp(12f), legendY - dp(3f), boschCurve2Paint)
            curX += dp(16f)
            canvas.drawText("HPC[2] 40°", curX, legendY, legendPaint)
            curX += dp(66f)
        }
        if (showHpcCurve3) {
            canvas.drawLine(curX, legendY - dp(3f), curX + dp(12f), legendY - dp(3f), boschCurve3Paint)
            curX += dp(16f)
            canvas.drawText("HPC[3] 35°", curX, legendY, legendPaint)
            curX += dp(66f)
        }
        if (showHpcCurve4) {
            canvas.drawLine(curX, legendY - dp(3f), curX + dp(12f), legendY - dp(3f), boschCurve4Paint)
            curX += dp(16f)
            canvas.drawText("HPC[4] FBH", curX, legendY, legendPaint)
            curX += dp(66f)
        }
        if (showHpcCurve5) {
            canvas.drawLine(curX, legendY - dp(3f), curX + dp(12f), legendY - dp(3f), boschCurve5Paint)
            curX += dp(16f)
            canvas.drawText("HPC[5] 60°", curX, legendY, legendPaint)
            curX += dp(66f)
        }
        if (showLivePoint && liveOut != null && liveFlow != null) {
            canvas.drawCircle(curX + dp(4f), legendY - dp(3f), dp(3.5f), livePointPaint)
            curX += dp(12f)
            canvas.drawText(String.format("Live: %.1f°/%.1f°", liveOut, liveFlow), curX, legendY, legendPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTimeMs = System.currentTimeMillis()
                val padLeft = dp(38f)
                val padRight = dp(16f)
                val chartWidth = width - padLeft - padRight
                if (chartWidth > 0) {
                    touchOutdoorTempC = pxToOutdoorTemp(event.x, padLeft, chartWidth)
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val padLeft = dp(38f)
                val padRight = dp(16f)
                val chartWidth = width - padLeft - padRight
                if (chartWidth > 0) {
                    touchOutdoorTempC = pxToOutdoorTemp(event.x, padLeft, chartWidth)
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - downTimeMs
                val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                if (event.action == MotionEvent.ACTION_UP && duration < 350 && dist < dp(18f)) {
                    onChartClickListener?.invoke() ?: performClick()
                }
                touchOutdoorTempC = null
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
