package app.heartpact.ui.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.exercise.Pose

/** Lines drawn between landmarks for the stick figure: shoulders, arms, torso, legs. */
internal val BONES = listOf(
    Pose.L_SHOULDER to Pose.R_SHOULDER, Pose.L_SHOULDER to Pose.L_ELBOW, Pose.L_ELBOW to Pose.L_WRIST, Pose.R_SHOULDER to Pose.R_ELBOW,
    Pose.R_ELBOW to Pose.R_WRIST, Pose.L_SHOULDER to Pose.L_HIP, Pose.R_SHOULDER to Pose.R_HIP, Pose.L_HIP to Pose.R_HIP,
    Pose.L_HIP to Pose.L_KNEE, Pose.L_KNEE to Pose.L_ANKLE, Pose.R_HIP to Pose.R_KNEE, Pose.R_KNEE to Pose.R_ANKLE,
)

/** The joints drawn as dots: everything in the figure but the nose, which marks the head instead. */
private val DOTS = Pose.FIGURE.filter { it != Pose.NOSE }

/**
 * Draws the stick figure: bones between the joints [at] places (null for one not seen), a dot on each joint, and a
 * head over the nose, sized from the shoulders. Shared by the camera overlay and the partner's live view.
 */
internal fun DrawScope.stickFigure(at: (Int) -> Offset?, bone: Color, joint: Color, fill: Color = Color.White, width: Dp = 3.5.dp, dot: Dp = 6.dp, head: Boolean = false) {
    BONES.forEach { (a, b) ->
        val p = at(a); val q = at(b)
        if (p != null && q != null) drawLine(bone, p, q, width.toPx(), StrokeCap.Round)
    }
    if (head) {
        val nose = at(Pose.NOSE)
        val l = at(Pose.L_SHOULDER); val r = at(Pose.R_SHOULDER)
        if (nose != null) {
            val neck = if (l != null && r != null) Offset((l.x + r.x) / 2, (l.y + r.y) / 2) else null
            neck?.let { drawLine(bone, it, nose, width.toPx(), StrokeCap.Round) }
            val radius = (neck?.let { (it - nose).getDistance() * 0.55f } ?: 14.dp.toPx()).coerceIn(10.dp.toPx(), 34.dp.toPx())
            drawCircle(fill, radius, nose)
            drawCircle(bone, radius, nose, style = Stroke(width.toPx()))
        }
    }
    DOTS.forEach { index ->
        at(index)?.let { drawCircle(fill, dot.toPx(), it); drawCircle(joint, dot.toPx(), it, style = Stroke(2.5.dp.toPx())) }
    }
}
