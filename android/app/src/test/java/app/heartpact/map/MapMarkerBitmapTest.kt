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

    @Test fun `the feet stand on the centre of the image, where the symbol is anchored`() {
        val density = 3f
        val bitmap = MapMarkerBitmap.render(look(null, "2 min ago"), density)
        assertEquals(density * 160, bitmap.density.toFloat(), 0.5f)
        val tipY = bitmap.height / 2
        // Something is drawn just above the tip (the character), the very top row is clear, and so are the corners.
        val column = (0 until bitmap.width).map { x -> Color.alpha(bitmap.getPixel(x, tipY - (8 * density).toInt())) }
        assertTrue("the character stands just above the tip", column.any { it > 200 })
        assertTrue("nothing at the very top", (0 until bitmap.width).all { x -> Color.alpha(bitmap.getPixel(x, 0)) < 12 })
        assertEquals("corner is clear", 0, Color.alpha(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)))
    }

    @Test fun `without a picture the badge is the side colour, with one it is the picture`() {
        val density = 3f
        val plain = MapMarkerBitmap.render(look(null, null), density)
        val badgeY = plain.height / 2 - (MapMarkerBitmap.BADGE_CENTRE_ABOVE_TIP_DP * density).toInt()
        val badgeX = plain.width / 2 - (MapMarkerBitmap.BADGE_SIDE_DP * density).toInt()
        // Between the picture and the white ring: the ring colour.
        val ringPixel = plain.getPixel(badgeX + ((MapMarkerBitmap.PHOTO_DP / 2 + 1.2f) * density).toInt(), badgeY)
        assertEquals(Color.red(ring), Color.red(ringPixel))
        assertEquals(Color.blue(ring), Color.blue(ringPixel))

        val red = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val withPhoto = MapMarkerBitmap.render(look(red, null), density)
        val centre = withPhoto.getPixel(badgeX, badgeY)
        assertEquals(255, Color.red(centre))
        assertEquals(0, Color.green(centre))
    }

    @Test fun `a longer line never makes the image narrower`() {
        val short = MapMarkerBitmap.render(look(null, null), 2f)
        val long = MapMarkerBitmap.render(look(null, "over a day ago, not sharing"), 2f)
        assertTrue(long.width >= short.width)
        assertEquals("the same height, so the tip stays centred", short.height, long.height)
    }
}
