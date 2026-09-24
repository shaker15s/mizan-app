package app.mizan.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.mizan.design.theme.LocalMizanColors

/** Geometric balance mark. The name MIZAN means scale. */
@Composable
fun MizanMark(modifier: Modifier = Modifier) {
    val color = LocalMizanColors.current.accent
    Canvas(modifier.size(22.dp)) {
        val stroke = size.minDimension * 0.08f
        val cx = size.width / 2f
        val top = size.height * 0.18f
        val beamY = size.height * 0.38f
        val foot = size.height * 0.86f
        drawLine(color, Offset(cx, top), Offset(cx, foot), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx - size.width * 0.18f, foot), Offset(cx + size.width * 0.18f, foot), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.12f, beamY), Offset(size.width * 0.88f, beamY), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.12f, beamY), Offset(size.width * 0.22f, beamY + size.height * 0.22f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.88f, beamY), Offset(size.width * 0.78f, beamY + size.height * 0.22f), stroke, StrokeCap.Round)
    }
}
