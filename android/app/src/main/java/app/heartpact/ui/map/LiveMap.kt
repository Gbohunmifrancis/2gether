package app.heartpact.ui.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heartpact.ui.components.Face
import app.heartpact.ui.components.LocalAvatars
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * The MapLibre map in Compose. The view lives as long as this composable and follows the host's lifecycle through
 * [MapLifecycleDriver] (create, start, resume, pause, stop, destroy, low memory). [onFailed] is called if the base map
 * cannot be loaded (no network and nothing cached), so the caller can show the drawn map instead.
 */
@Composable
fun LiveMap(controller: MapController, people: List<MapPerson>, night: Boolean, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val failed by rememberUpdatedState(onFailed)
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        // A texture, not a surface: it composes and animates with the rest of the screen without flashing.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    }

    DisposableEffect(mapView) {
        val driver = MapLifecycleDriver(object : MapHost {
            override fun onCreate() = mapView.onCreate(null)
            override fun onStart() = mapView.onStart()
            override fun onResume() = mapView.onResume()
            override fun onPause() = mapView.onPause()
            override fun onStop() = mapView.onStop()
            override fun onDestroy() = mapView.onDestroy()
        })
        val observer = LifecycleEventObserver { _, event -> driver.onEvent(event) }
        val memory = object : ComponentCallbacks2 {
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) mapView.onLowMemory() }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        context.registerComponentCallbacks(memory)
        controller.attach(mapView, density) { failed() }
        // Added while the screen is already resumed, this replays create, start and resume in order.
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            controller.detach()
            driver.dispose()
        }
    }

    // Glide, pulse and sway, one step per frame, only while the screen is on show.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) androidx.compose.runtime.withFrameNanos { controller.tick() }
        }
    }
    SideEffect { controller.update(people, night) }

    AndroidView(
        factory = { mapView },
        modifier = modifier.onSizeChanged { if (it.width > 0 && it.height > 0) controller.onViewSized() },
    )
}

/** A face's picture as a software bitmap for the marker, or null while there is none. */
@Composable
fun rememberFaceBitmap(face: Face): Bitmap? {
    val repo = LocalAvatars.current
    val changes = repo?.changes?.collectAsState()
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, face.userId, face.version, changes?.value) {
        value = repo?.load(face.userId, face.version)
    }
    return remember(image) {
        image?.asAndroidBitmap()?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }
}
