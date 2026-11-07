package com.wonderhoylabs.akiyama.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.akiyama.R

/** Which way the internet is going across the cable. */
enum class LinkFlow { None, ToPhone, ToComputer }

private val END_SIZE = 56.dp
private val END_WIDTH = 96.dp
private const val FLOW_MILLIS = 700

/** The phone, the computer, and which of them is getting internet from the other. */
@Composable
fun LinkDiagram(flow: LinkFlow, moving: Boolean, computer: String?, modifier: Modifier = Modifier) {
    // The two ends are real objects with a direction between them, so this doesn't mirror.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
            LinkEnd(R.drawable.ic_phone, stringResource(R.string.this_phone))
            FlowLine(flow, moving, Modifier.weight(1f).height(END_SIZE))
            LinkEnd(R.drawable.ic_computer, computer ?: stringResource(R.string.computer))
        }
    }
}

@Composable
private fun LinkEnd(@DrawableRes icon: Int, label: String) {
    Column(
        modifier = Modifier.width(END_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(END_SIZE)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FlowLine(flow: LinkFlow, moving: Boolean, modifier: Modifier) {
    val color = if (flow == LinkFlow.None) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val progress = if (moving) flowProgress() else 0f
    Canvas(modifier) {
        val dash = 6.dp.toPx()
        val gap = 8.dp.toPx()
        val period = dash + gap
        val width = 3.dp.toPx()
        val inset = 8.dp.toPx()
        val y = size.height / 2
        // A growing phase pulls the dashes towards the start of the line, which is on the left.
        val phase = if (flow == LinkFlow.ToPhone) progress * period else (1 - progress) * period
        drawLine(
            color = color,
            start = Offset(inset, y),
            end = Offset(size.width - inset, y),
            strokeWidth = width,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase),
        )
        if (flow == LinkFlow.None) return@Canvas
        val tip = if (flow == LinkFlow.ToComputer) size.width - inset else inset
        val back = if (flow == LinkFlow.ToComputer) -1 else 1
        val arm = 10.dp.toPx()
        val arrow = Path().apply {
            moveTo(tip + back * arm, y - arm * 0.8f)
            lineTo(tip, y)
            lineTo(tip + back * arm, y + arm * 0.8f)
        }
        drawPath(arrow, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun flowProgress(): Float {
    val transition = rememberInfiniteTransition(label = "flow")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(FLOW_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "flow",
    )
    return progress
}
