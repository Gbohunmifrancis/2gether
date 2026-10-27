package app.heartpact.ui.graphics

import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.heartpact.R
import app.rive.Fit
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFile
import app.rive.RiveFileSource
import app.rive.RivePointerInputMode
import app.rive.ViewModelSource
import app.rive.rememberArtboardResult
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import app.rive.rememberViewModelInstanceResult

/**
 * The 3D icons (design/rive-icons): each is an artboard in res/raw/heartpact_icons.riv, which floats at rest and pops
 * when its view model's `active` turns true, and a still of it at rest in res/drawable-nodpi.
 */
enum class Icon3D(val artboard: String, @DrawableRes val still: Int) {
    Today("today", R.drawable.icon3d_today),
    Pacts("pacts", R.drawable.icon3d_pacts),
    Food("food", R.drawable.icon3d_food),
    Us("us", R.drawable.icon3d_us),
    Album("album", R.drawable.icon3d_album),
    Cycle("cycle", R.drawable.icon3d_cycle),
    Plus("plus", R.drawable.icon3d_plus),
    Games("games", R.drawable.icon3d_games),
    Dares("dares", R.drawable.icon3d_dares),
    Location("location", R.drawable.icon3d_location),
    Question("question", R.drawable.icon3d_question),
    Bell("bell", R.drawable.icon3d_bell),
    Pregnancy("pregnancy", R.drawable.icon3d_pregnancy),
}

/** The app's Rive file, loaded once; null while it loads and wherever Rive can't run. */
val LocalRiveFile = staticCompositionLocalOf<RiveFile?> { null }

/**
 * Loads heartpact_icons.riv on one Rive worker for everything under it. Skipped on the JVM (Robolectric has no Rive
 * renderer), in previews, and with animations switched off, so those all get the stills; a phone whose renderer
 * fails to start gets them too.
 */
@Composable
fun ProvideRive(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val usable = !LocalInspectionMode.current && !LocalStillLogo.current && remember { riveCanRun(context) }
    if (!usable) {
        content()
        return
    }
    val worker = rememberRiveWorkerOrNull()
    val file = worker?.let { rememberRiveFile(RiveFileSource.RawRes.from(R.raw.heartpact_icons), it) }
    CompositionLocalProvider(LocalRiveFile provides (file as? Result.Success)?.value, content = content)
}

private fun riveCanRun(context: android.content.Context): Boolean =
    Build.FINGERPRINT != "robolectric" &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** An icon at rest, as a picture: rows, unselected tabs, and anywhere the animation can't run. */
@Composable
fun Icon3DStill(icon: Icon3D, size: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(painterResource(icon.still), contentDescription = null, modifier = modifier.size(size), alpha = alpha)
}

/**
 * A tab's icon. The selected tab plays in Rive (it pops, then bobs); the rest are stills, slightly faded, so only one
 * icon in the bar ever animates. The still stays under the animation until Rive has drawn its first frame.
 */
@Composable
fun TabIcon3D(icon: Icon3D, selected: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val file = LocalRiveFile.current
    if (!selected || file == null) {
        Icon3DStill(icon, size, modifier, alpha = if (selected) 1f else 0.72f)
        return
    }
    Box(modifier.size(size)) {
        var drawn by remember { mutableStateOf(false) }
        if (!drawn) Icon3DStill(icon, size)
        val artboard = rememberArtboardResult(file, icon.artboard)
        val instance = rememberViewModelInstanceResult(file, ViewModelSource.Named("Icon").defaultInstance())
        if (artboard is Result.Success && instance is Result.Success) {
            LaunchedEffect(instance.value) { instance.value.setBoolean("active", true) }
            Rive(
                file,
                Modifier.size(size),
                artboard = artboard.value,
                viewModelInstance = instance.value,
                fit = Fit.Contain(),
                // The tab underneath takes the tap.
                pointerInputMode = RivePointerInputMode.PassThrough,
                onBitmapAvailable = { drawn = true },
            )
        }
    }
}
