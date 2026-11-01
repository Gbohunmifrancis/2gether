package app.heartpact.ui.pair

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.theme.Shapes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR code's modules, row by row; made on the phone, nothing is fetched. Pure, so it is tested. */
object QrMatrix {
    fun of(text: String): Array<BooleanArray> {
        // Level H keeps the code readable with the small heart over its middle.
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8")
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
    }
}

/**
 * The invite link as a QR code, for a partner in the same room to scan with Heartpact. Always dark on white with a
 * quiet border, at night too, since scanners read that best; the heart in the middle is covered by the error correction.
 */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val matrix = remember(text) { QrMatrix.of(text) }
    val ink = Color(0xFF161A3D)
    Box(
        modifier.size(size).clip(Shapes.Tile).background(Color.White).padding(size / 14)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size - size / 7)) {
            val n = matrix.size
            val cell = this.size.width / n
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix[y][x] || inFinder(x, y, n)) continue
                drawRoundRect(ink, Offset(x * cell + cell * 0.06f, y * cell + cell * 0.06f), Size(cell * 0.88f, cell * 0.88f), CornerRadius(cell * 0.3f))
            }
            // The three finder squares, drawn rounded so the code looks like ours.
            for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val o = Offset(fx * cell, fy * cell)
                drawRoundRect(ink, o, Size(cell * 7, cell * 7), CornerRadius(cell * 1.8f))
                drawRoundRect(Color.White, o + Offset(cell, cell), Size(cell * 5, cell * 5), CornerRadius(cell * 1.2f))
                drawRoundRect(ink, o + Offset(cell * 2, cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell * 0.9f))
            }
        }
        Box(Modifier.size(size * 0.24f).clip(Shapes.Tile).background(Color.White), contentAlignment = Alignment.Center) {
            HeartSticker(HalfState.Stuck, HalfState.Stuck, size * 0.19f)
        }
    }
}

private fun inFinder(x: Int, y: Int, n: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
