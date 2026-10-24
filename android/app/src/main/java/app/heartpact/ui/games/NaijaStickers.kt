package app.heartpact.ui.games

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Naija memory cards (theme "naija"), drawn like the classic six: one-colour 24-unit icons that [androidx.compose.material3.Icon]
 * tints. Holes are even-odd cut-outs, details are round-capped strokes. Values 1 to 6, in [GamePlay.cardLabel]'s order.
 */
object NaijaStickers {
    fun card(value: Int): ImageVector = when (value) {
        1 -> jollof
        2 -> suya
        3 -> talkingDrum
        4 -> danfo
        5 -> palmTree
        else -> mortar
    }

    private class Shape(val d: String, val stroke: Float = 0f)

    private fun icon(name: String, vararg shapes: Shape): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        shapes.forEach { shape ->
            if (shape.stroke > 0f) builder.addPath(addPathNodes(shape.d), stroke = SolidColor(Color.Black), strokeLineWidth = shape.stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            else builder.addPath(addPathNodes(shape.d), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        }
        return builder.build()
    }

    /** A party pot of jollof: lid, handles and steam. */
    private val jollof by lazy {
        icon(
            "Jollof",
            Shape("M4,11.5h16v4.5a4.5,4.5 0,0 1,-4.5,4.5h-7a4.5,4.5 0,0 1,-4.5,-4.5z"),
            Shape("M1.5,12.2h3v2.3h-3z M19.5,12.2h3v2.3h-3z"),
            Shape("M3.2,9h17.6a0.9,0.9 0,0 1,0 1.8h-17.6a0.9,0.9 0,0 1,0 -1.8z M10.5,7h3a0.8,0.8 0,0 1,0.8 0.8v1.2h-4.6v-1.2a0.8,0.8 0,0 1,0.8 -0.8z"),
            Shape("M8,5.5c-0.9,-0.9 0.9,-1.7 0,-2.8 M12,4.8c-0.9,-0.9 0.9,-1.7 0,-2.8 M16,5.5c-0.9,-0.9 0.9,-1.7 0,-2.8", stroke = 1.4f),
        )
    }

    /** A suya stick: three chunks of spiced beef on a skewer. */
    private val suya by lazy {
        // Each chunk is a diamond whose sides run along the skewer.
        fun chunk(x: Float, y: Float) = "M${x - 3.1f},${y}l3.1,-3.1l3.1,3.1l-3.1,3.1z"
        icon(
            "Suya",
            Shape("M3,21L21,3", stroke = 1.6f),
            Shape(chunk(7.4f, 16.6f)),
            Shape(chunk(12f, 12f)),
            Shape(chunk(16.6f, 7.4f)),
        )
    }

    /** A talking drum: two heads laced together, pinched at the waist. */
    private val talkingDrum by lazy {
        icon(
            "Talking drum",
            Shape("M5,2.5h14a1.2,1.2 0,0 1,1.2 1.2v0.9a1.2,1.2 0,0 1,-1.2 1.2h-14a1.2,1.2 0,0 1,-1.2 -1.2v-0.9a1.2,1.2 0,0 1,1.2 -1.2z"),
            Shape("M5,17.8h14a1.2,1.2 0,0 1,1.2 1.2v0.9a1.2,1.2 0,0 1,-1.2 1.2h-14a1.2,1.2 0,0 1,-1.2 -1.2v-0.9a1.2,1.2 0,0 1,1.2 -1.2z"),
            Shape("M6,5.8L10.4,12L6,17.8 M9.3,5.8L11.3,12L9.3,17.8 M14.7,5.8L12.7,12L14.7,17.8 M18,5.8L13.6,12L18,17.8", stroke = 1.3f),
            Shape("M9.8,10.9h4.4v2.2h-4.4z"),
        )
    }

    /** A Lagos danfo: the yellow bus from the side, windows, stripe and wheels. */
    private val danfo by lazy {
        icon(
            "Danfo",
            Shape(
                "M2.8,6h14.6a1,1 0,0 1,0.8 0.4l3.4,4.4a1,1 0,0 1,0.2 0.6v5.1a1,1 0,0 1,-1 1h-18a1,1 0,0 1,-1 -1v-9.5a1,1 0,0 1,1 -1z" +
                    " M3.6,7.8h3.4v3.2h-3.4z M8.2,7.8h3.4v3.2h-3.4z M12.8,7.8h3.4v3.2h-3.4z M17.3,7.8h0.4l2.4,3.2h-2.8z M1.8,13h20v1.3h-20z",
            ),
            Shape("M7,16.2a2.4,2.4 0,1 1,0 4.8a2.4,2.4 0,1 1,0 -4.8z M7,17.7a0.9,0.9 0,1 0,0 1.8a0.9,0.9 0,1 0,0 -1.8z M17,16.2a2.4,2.4 0,1 1,0 4.8a2.4,2.4 0,1 1,0 -4.8z M17,17.7a0.9,0.9 0,1 0,0 1.8a0.9,0.9 0,1 0,0 -1.8z"),
        )
    }

    /** A palm tree: fronds, coconuts and a leaning trunk. */
    private val palmTree by lazy {
        icon(
            "Palm tree",
            Shape("M12.2,8.5C12.8,12.5 12.2,17 10.2,21", stroke = 2.2f),
            Shape("M12,7.5C15,3.6 19.5,3.8 22.5,8.2C19,6.6 15.5,6.8 12,7.5z"),
            Shape("M12,7.5C9,3.6 4.5,3.8 1.5,8.2C5,6.6 8.5,6.8 12,7.5z"),
            Shape("M12,7.5C12.6,3.6 15.6,1.6 19,2C15.8,3.2 13.8,5 12,7.5z"),
            Shape("M12,7.5C11.4,3.6 8.4,1.6 5,2C8.2,3.2 10.2,5 12,7.5z"),
            Shape("M12,7.5C16.2,7.8 19,10.4 19.6,14C17.6,11.2 15.2,9.4 12,7.5z"),
            Shape("M12,7.5C7.8,7.8 5,10.4 4.4,14C6.4,11.2 8.8,9.4 12,7.5z"),
            Shape("M10.9,8.3a1.3,1.3 0,1 1,0 2.6a1.3,1.3 0,1 1,0 -2.6z M13.6,8.6a1.3,1.3 0,1 1,0 2.6a1.3,1.3 0,1 1,0 -2.6z"),
            Shape("M6,21.5h12", stroke = 1.6f),
        )
    }

    /** A mortar and pestle, for pounded yam. */
    private val mortar by lazy {
        icon(
            "Mortar and pestle",
            Shape("M12.4,9.4L19.6,2.4", stroke = 2.6f),
            Shape("M3.5,10h17c0,3.9 -2.4,6.5 -5.2,7.4v1.8h2.2a1,1 0,0 1,1 1v1.3h-13v-1.3a1,1 0,0 1,1 -1h2.2v-1.8c-2.8,-0.9 -5.2,-3.5 -5.2,-7.4z"),
        )
    }
}
