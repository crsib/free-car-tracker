package me.crsib.freecartracker.ui.telemetry

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.crsib.freecartracker.telemetry.data.entity.GpsPoint
import me.crsib.freecartracker.ui.containerViewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Renders a trip's GPS track on an OpenStreetMap (osmdroid) map: a polyline with start/end
 * markers, auto-fitted to the route. [interactive] = false makes a static preview that
 * swallows touches (so it can live inside a scrolling screen).
 *
 * The [MapView] is created once and its resume/pause are bound to the Android lifecycle so its
 * tile-download/draw threads stop when the app is backgrounded. Overlays are rebuilt in the
 * `update` block so the route appears even when [points] arrive after the first composition.
 */
@SuppressLint("ClickableViewAccessibility") // non-interactive preview deliberately swallows touches
@Composable
fun RouteMap(points: List<GpsPoint>, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            if (!interactive) {
                setOnTouchListener { _, _ -> true } // preview: don't steal scroll gestures
            }
        }
    }

    // osmdroid's online tiles never retry a download that failed while offline, and a static
    // preview has no pan/zoom to trigger one — so a map opened offline stays blank forever. Drop
    // the failed/empty tiles and redraw when connectivity returns to force a fresh fetch.
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mapView.post {
                    mapView.tileProvider.clearTileCache()
                    mapView.invalidate()
                }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            val geo = points.map { GeoPoint(it.latitude, it.longitude) }
            if (geo.isNotEmpty()) {
                map.overlays.add(
                    Polyline().apply {
                        setPoints(geo)
                        outlinePaint.color = Color.parseColor("#1565C0")
                        outlinePaint.strokeWidth = 10f
                    },
                )
                map.overlays.add(marker(map, geo.first(), "Start"))
                if (geo.size > 1) map.overlays.add(marker(map, geo.last(), "End"))

                if (geo.size == 1) {
                    map.controller.setZoom(16.0)
                    map.controller.setCenter(geo.first())
                } else {
                    // Fit the whole route once the view has been measured.
                    map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 64) }
                }
            }
            map.invalidate()
        },
    )
}

private fun marker(map: MapView, at: GeoPoint, label: String): Marker =
    Marker(map).apply {
        position = at
        title = label
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteMapScreen(tripId: Long, onBack: () -> Unit) {
    val vm = containerViewModel(key = "trip-$tripId") { c ->
        TripDetailViewModel(c.telemetryRepository, tripId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val points = state.data?.gpsPoints.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Route") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("No GPS track for this trip.")
            }
        } else {
            RouteMap(points = points, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}
