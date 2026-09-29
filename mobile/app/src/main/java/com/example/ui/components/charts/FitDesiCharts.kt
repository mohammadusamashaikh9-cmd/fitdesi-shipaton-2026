package com.example.ui.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
data class ChartPoint(val label: String, val value: Double?, val valueLabel: String)

@Immutable
data class ChartSeries(val name: String, val color: Color, val points: List<ChartPoint>)

@Immutable
data class ChartBucket(val label: String, val values: List<Double?>)

@Immutable
data class ChartMarkerValue(val seriesName: String, val bucketLabel: String, val valueLabel: String)

@Immutable
data class ChartSemanticSummary(
    val title: String,
    val dateRange: String,
    val summary: String,
    val metricRows: List<String>
)

@Composable
fun FitDesiLineChart(
    series: ChartSeries,
    semantics: ChartSemanticSummary,
    modifier: Modifier = Modifier
) {
    val reveal = rememberChartReveal(series.points, 320)
    var selectedIndex by remember(series.points) { mutableIntStateOf(-1) }
    ChartFrame(semantics, modifier) {
        val grid = MaterialTheme.colorScheme.outlineVariant
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(184.dp)
                    .pointerInput(series.points) {
                        detectTapGestures { offset ->
                            selectedIndex = nearestLineBucketIndex(offset.x, size.width, series.points.size)
                        }
                    }
                    .clearAndSetSemantics { }
            ) {
                repeat(4) { index ->
                    val y = size.height * index / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val values = series.points.mapNotNull(ChartPoint::value)
                val maximum = max(values.maxOrNull() ?: 0.0, 1.0)
                val slots = max(series.points.size - 1, 1)
                if (selectedIndex in series.points.indices) {
                    val selectedX = size.width * selectedIndex / slots
                    drawLine(
                        series.color.copy(alpha = 0.35f),
                        Offset(selectedX, 0f),
                        Offset(selectedX, size.height),
                        1.dp.toPx()
                    )
                }
                val path = Path()
                var hasPoint = false
                series.points.forEachIndexed { index, point ->
                    val value = point.value
                    if (value == null) {
                        hasPoint = false
                    } else {
                        val x = size.width * index / slots
                        val y = size.height - (size.height * (value / maximum).toFloat() * reveal)
                        if (!hasPoint) path.moveTo(x, y) else path.lineTo(x, y)
                        if (index == selectedIndex) {
                            drawCircle(series.color.copy(alpha = 0.22f), radius = 8.dp.toPx(), center = Offset(x, y))
                        }
                        drawCircle(series.color, radius = 3.dp.toPx(), center = Offset(x, y))
                        hasPoint = true
                    }
                }
                if (values.isNotEmpty()) drawPath(path, series.color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            SparseXAxisLabels(series.points)
            series.points.getOrNull(selectedIndex)?.let { point ->
                FitDesiChartMarker(
                    ChartMarkerValue(series.name, point.label, point.valueLabel),
                    series.color
                )
            }
        }
    }
}

@Composable
fun FitDesiColumnChart(
    series: ChartSeries,
    semantics: ChartSemanticSummary,
    modifier: Modifier = Modifier
) {
    val reveal = rememberChartReveal(series.points, 300)
    var selectedIndex by remember(series.points) { mutableIntStateOf(-1) }
    ChartFrame(semantics, modifier) {
        val track = MaterialTheme.colorScheme.surfaceVariant
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(184.dp)
                    .pointerInput(series.points) {
                        detectTapGestures { offset ->
                            selectedIndex = nearestColumnBucketIndex(offset.x, size.width, series.points.size)
                        }
                    }
                    .clearAndSetSemantics { }
            ) {
                val maximum = max(series.points.mapNotNull(ChartPoint::value).maxOrNull() ?: 0.0, 1.0)
                val slotWidth = size.width / max(series.points.size, 1)
                val barWidth = (slotWidth * 0.52f).coerceAtLeast(3.dp.toPx())
                series.points.forEachIndexed { index, point ->
                    val left = slotWidth * index + (slotWidth - barWidth) / 2f
                    drawRoundRect(track, Offset(left, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 2f))
                    point.value?.let { value ->
                        val height = size.height * (value / maximum).toFloat() * reveal
                        drawRoundRect(
                            series.color,
                            Offset(left, size.height - height),
                            Size(barWidth, height),
                            CornerRadius(barWidth / 2f)
                        )
                    }
                    if (index == selectedIndex) {
                        drawRoundRect(
                            series.color,
                            Offset(left, 0f),
                            Size(barWidth, size.height),
                            CornerRadius(barWidth / 2f),
                            style = Stroke(2.dp.toPx())
                        )
                    }
                }
            }
            SparseXAxisLabels(series.points)
            series.points.getOrNull(selectedIndex)?.let { point ->
                FitDesiChartMarker(
                    ChartMarkerValue(series.name, point.label, point.valueLabel),
                    series.color
                )
            }
        }
    }
}

@Composable
fun FitDesiMetricChart(
    series: ChartSeries,
    semantics: ChartSemanticSummary,
    modifier: Modifier = Modifier,
    useLine: Boolean = false
) {
    if (useLine) FitDesiLineChart(series, semantics, modifier) else FitDesiColumnChart(series, semantics, modifier)
}

@Composable
fun FitDesiComparisonBars(
    title: String,
    currentLabel: String,
    current: Double?,
    previousLabel: String,
    previous: Double?,
    color: Color,
    modifier: Modifier = Modifier
) {
    val maximum = max(max(current ?: 0.0, previous ?: 0.0), 1.0)
    Column(
        modifier.semantics {
            contentDescription = "$title. $currentLabel ${current?.toReadable() ?: "not logged"}. " +
                "$previousLabel ${previous?.toReadable() ?: "not logged"}."
        },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        ComparisonRow(currentLabel, current, maximum, color)
        ComparisonRow(previousLabel, previous, maximum, color.copy(alpha = 0.45f))
    }
}

@Composable
fun FitDesiMacroChart(
    buckets: List<ChartBucket>,
    colors: List<Color>,
    semantics: ChartSemanticSummary,
    modifier: Modifier = Modifier
) {
    val reveal = rememberChartReveal(buckets, 300)
    ChartFrame(semantics, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(190.dp).clearAndSetSemantics { }) {
                val maximum = max(buckets.maxOfOrNull { it.values.sumOf { value -> value ?: 0.0 } } ?: 0.0, 1.0)
                val slot = size.width / max(buckets.size, 1)
                val width = slot * 0.55f
                buckets.forEachIndexed { index, bucket ->
                    var bottom = size.height
                    bucket.values.forEachIndexed { valueIndex, value ->
                        val segment = size.height * ((value ?: 0.0) / maximum).toFloat() * reveal
                        drawRect(
                            colors[valueIndex % colors.size],
                            Offset(slot * index + (slot - width) / 2f, bottom - segment),
                            Size(width, segment)
                        )
                        bottom -= segment
                    }
                }
            }
            SparseXAxisLabels(buckets.map { ChartPoint(it.label, null, "") })
        }
    }
}

@Composable
fun FitDesiDonutChart(
    series: ChartSeries,
    semantics: ChartSemanticSummary,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(series.color)
) {
    val reveal = rememberChartReveal(series.points, 300)
    ChartFrame(semantics, modifier) {
        Canvas(Modifier.fillMaxWidth().height(210.dp).clearAndSetSemantics { }) {
            val values = series.points.map { (it.value ?: 0.0).coerceAtLeast(0.0) }
            val total = values.sum().takeIf { it > 0.0 } ?: 1.0
            val diameter = size.minDimension * 0.76f
            val rect = Rect(
                Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                Size(diameter, diameter)
            )
            var start = -90f
            values.forEachIndexed { index, value ->
                val sweep = (value / total * 360.0).toFloat() * reveal
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = diameter * 0.22f, cap = StrokeCap.Butt)
                )
                start += sweep
            }
        }
    }
}

@Composable
fun FitDesiChartMarker(value: ChartMarkerValue, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                contentDescription = "${value.seriesName}, ${value.bucketLabel}, ${value.valueLabel}"
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("${value.bucketLabel}  ${value.valueLabel}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AccessibleChartSummary(summary: ChartSemanticSummary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = buildString {
                append(summary.title).append(". ").append(summary.dateRange).append(". ")
                append(summary.summary).append(". ")
                append(summary.metricRows.joinToString(". "))
            }
        },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(summary.summary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SparseXAxisLabels(points: List<ChartPoint>) {
    if (points.isEmpty()) return
    val indexes = sparseXAxisIndexes(points.size)
    Row(Modifier.fillMaxWidth()) {
        indexes.forEachIndexed { position, index ->
            val alignment = when {
                indexes.size == 1 -> TextAlign.Center
                position == 0 -> TextAlign.Start
                position == indexes.lastIndex -> TextAlign.End
                else -> TextAlign.Center
            }
            Text(
                text = points[index].label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = alignment,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChartFrame(
    semantics: ChartSemanticSummary,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
        AccessibleChartSummary(semantics)
    }
}

@Composable
private fun ComparisonRow(label: String, value: Double?, maximum: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(76.dp), style = MaterialTheme.typography.labelMedium)
        Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Box(
                Modifier
                    .fillMaxWidth(((value ?: 0.0) / maximum).toFloat().coerceIn(0f, 1f))
                    .height(10.dp)
                    .background(color, CircleShape)
            )
        }
        Text(value?.toReadable() ?: "—", modifier = Modifier.padding(start = 8.dp))
    }
}

private fun Double.toReadable(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(this)

internal fun sparseXAxisIndexes(bucketCount: Int): List<Int> = when {
    bucketCount <= 0 -> emptyList()
    bucketCount <= 7 -> (0 until bucketCount).toList()
    else -> listOf(0, bucketCount / 2, bucketCount - 1).distinct()
}

private fun nearestLineBucketIndex(x: Float, width: Int, bucketCount: Int): Int = when {
    bucketCount <= 0 || width <= 0 -> -1
    bucketCount == 1 -> 0
    else -> (x.coerceIn(0f, width.toFloat()) / width * (bucketCount - 1)).roundToInt()
}

private fun nearestColumnBucketIndex(x: Float, width: Int, bucketCount: Int): Int = when {
    bucketCount <= 0 || width <= 0 -> -1
    else -> (x.coerceIn(0f, width.toFloat() - 0.001f) / width * bucketCount).toInt().coerceIn(0, bucketCount - 1)
}

@Composable
private fun rememberChartReveal(dataKey: Any, durationMillis: Int): Float {
    val reveal = remember(dataKey) { Animatable(0f) }
    LaunchedEffect(reveal) {
        reveal.animateTo(1f, animationSpec = tween(durationMillis))
    }
    return reveal.value
}
