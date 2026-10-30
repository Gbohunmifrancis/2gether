package app.heartpact.map

import android.graphics.Bitmap
import android.graphics.Color
import app.heartpact.ui.map.MapMarkerBitmap
import app.heartpact.ui.map.MapPalette
import app.heartpact.ui.map.MarkerLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MapMarkerBitmapTest {
    private val ring = 0xFF7480D4.toInt()
    private val day = MapPalette.Day

    private fun look(photo: Bitmap?, age: String?) =
        MarkerLook("Theo", age, ring, photo, day.pillBackground, day.pillText, day.pillMuted)

    @Test fun `the tip of the tail sits at the centre of the image, where the symbol is anchored`() {
        val density = 3f
        val bitmap = MapMarkerBitmap.render(look(null, "2 min ago"), density)
        assertEquals(density * 160, bitmap.density.toFloat(), 0.5f)
        val cx = bitmap.width / 2
        val tipY = bitmap.height / 2
        // Just above the tip is the tail; far below the head's top is empty; the corners are clear.
        assertTrue("tail near its tip", Color.alpha(bitmap.getPixel(cx, tipY - (2 * density).toInt())) > 200)
        // Only the faintest edge of the soft shadow may reach this high.
        assertTrue("nothing above the head", Color.alpha(bitmap.getPixel(cx, (2 * density).toInt())) < 12)
        assertEquals("corner is clear", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals("corner is clear", 0, Color.alpha(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)))
    }

    @Test fun `without a picture the head is the side colour, with one it is the picture`() {
        val density = 3f
        val plain = MapMarkerBitmap.render(look(null, null), density)
        val headCentreY = plain.height / 2 - (MapMarkerBitmap.HEAD_CENTRE_ABOVE_TIP_DP * density).toInt()
        // Between the picture and the colour ring: the ring colour.
        val ringPixel = plain.getPixel(plain.width / 2 + ((MapMarkerBitmap.PHOTO_DP / 2 + 1.5f) * density).toInt(), headCentreY)
        assertEquals(Color.red(ring), Color.red(ringPixel))
        assertEquals(Color.blue(ring), Color.blue(ringPixel))

        val red = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val withPhoto = MapMarkerBitmap.render(look(red, null), density)
        val centre = withPhoto.getPixel(withPhoto.width / 2, headCentreY)
        assertEquals(255, Color.red(centre))
        assertEquals(0, Color.green(centre))
    }

    @Test fun `the pill makes the image wider for a longer line`() {
        val short = MapMarkerBitmap.render(look(null, null), 2f)
        val long = MapMarkerBitmap.render(look(null, "over a day ago"), 2f)
        assertTrue(long.width > short.width)
        assertEquals("the same height, so the tip stays centred", short.height, long.height)
    }
}
