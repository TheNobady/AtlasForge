package com.example.atlasforge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import okhttp3.*
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvInstruction: TextView
    private lateinit var tvDistance: TextView
    private lateinit var etDestination: EditText
    private lateinit var btnSearch: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var myLocation: GeoPoint? = null
    private var myMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routeOverlay: Polyline? = null

    private var hopper: GraphHopper? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient()

    // ── Paths ──────────────────────────────────────────────────
    private val pbfPath get() = File(
        Environment.getExternalStorageDirectory(), "atlasforge/map.osm.pbf"
    ).absolutePath
    private val graphPath get() = File(filesDir, "graph-cache").absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        mapView       = findViewById(R.id.mapView)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvDistance    = findViewById(R.id.tvDistance)
        etDestination = findViewById(R.id.etDestination)
        btnSearch     = findViewById(R.id.btnSearch)
        progressBar   = findViewById(R.id.progressBar)

        setupMap()
        setupLocation()
        setupSearch()
        initGraphHopper()

        findViewById<FloatingActionButton>(R.id.fabMyLocation).setOnClickListener {
            myLocation?.let { mapView.controller.animateTo(it) }
        }
    }

    // ── GraphHopper init (background) ──────────────────────────
    private fun initGraphHopper() {
        setLoading(true)
        tvInstruction.text = "⏳ Loading offline maps..."

        executor.execute {
            try {
                val gh = GraphHopper()
                gh.osmFile = pbfPath
                gh.graphHopperLocation = graphPath
                gh.setProfiles(Profile("car"))
                gh.importOrLoad()
                hopper = gh

                runOnUiThread {
                    setLoading(false)
                    tvInstruction.text = "✅ Offline maps ready! Search or long-press to navigate."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    tvInstruction.text = "❌ Map load failed: ${e.message}"
                }
            }
        }
    }

    // ── Map setup ──────────────────────────────────────────────
    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.overlays.add(RotationGestureOverlay(mapView))
        mapView.controller.setZoom(15.0)

        // Long press → set destination
        mapView.overlays.add(object : Overlay() {
            override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
                val dest = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                placeDestinationAndRoute(dest)
                return true
            }
        })
    }

    // ── Search bar ─────────────────────────────────────────────
    private fun setupSearch() {
        val doSearch = {
            val query = etDestination.text.toString().trim()
            if (query.isNotEmpty()) geocodeAndRoute(query)
            hideKeyboard()
        }

        btnSearch.setOnClickListener { doSearch() }
        etDestination.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                doSearch(); true
            } else false
        }
    }

    // ── Geocode via Nominatim → route ──────────────────────────
    private fun geocodeAndRoute(query: String) {
        setLoading(true)
        tvInstruction.text = "🔍 Searching for \"$query\"..."

        val url = "https://nominatim.openstreetmap.org/search" +
                "?q=${query.replace(" ", "+")}&format=json&limit=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", packageName)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    setLoading(false)
                    tvInstruction.text = "❌ Search failed. Check internet."
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val results = JSONArray(body)
                if (results.length() == 0) {
                    runOnUiThread {
                        setLoading(false)
                        tvInstruction.text = "❌ Location not found."
                    }
                    return
                }
                val place = results.getJSONObject(0)
                val lat = place.getDouble("lat")
                val lon = place.getDouble("lon")
                val name = place.getString("display_name").split(",").take(2).joinToString(", ")

                runOnUiThread {
                    setLoading(false)
                    val dest = GeoPoint(lat, lon)
                    etDestination.setText(name)
                    placeDestinationAndRoute(dest)
                }
            }
        })
    }

    // ── Place pin + route ──────────────────────────────────────
    private fun placeDestinationAndRoute(dest: GeoPoint) {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = Marker(mapView).apply {
            position = dest
            title = "Destination"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }
        mapView.invalidate()

        val origin = myLocation
        if (origin == null) {
            tvInstruction.text = "⏳ Waiting for GPS..."
            return
        }

        if (hopper == null) {
            tvInstruction.text = "⏳ Still loading offline maps..."
            return
        }

        routeOffline(origin, dest)
    }

    // ── GraphHopper offline routing ────────────────────────────
    private fun routeOffline(from: GeoPoint, to: GeoPoint) {
        setLoading(true)
        tvInstruction.text = "🧭 Calculating route..."

        executor.execute {
            try {
                val req = GHRequest(
                    from.latitude, from.longitude,
                    to.latitude, to.longitude
                ).setProfile("car")

                val rsp = hopper!!.route(req)

                if (rsp.hasErrors()) {
                    runOnUiThread {
                        setLoading(false)
                        tvInstruction.text = "❌ No route found."
                    }
                    return@execute
                }

                val path = rsp.best
                val points = ArrayList<GeoPoint>()
                for (i in 0 until path.points.size()) {
                    points.add(GeoPoint(path.points.getLat(i), path.points.getLon(i)))
                }

                val distKm = "%.1f km".format(path.distance / 1000)
                val mins = (path.time / 60000).toInt()
                val instruction = path.instructions.firstOrNull()?.sign?.let {
                    when (it) {
                        0 -> "Continue straight"
                        -2 -> "Turn left"
                        2 -> "Turn right"
                        -3 -> "Sharp left"
                        3 -> "Sharp right"
                        4 -> "You have arrived"
                        else -> "Follow the route"
                    }
                } ?: "Follow the route"

                runOnUiThread {
                    setLoading(false)
                    drawRoute(points)
                    tvInstruction.text = "🧭 $instruction"
                    tvDistance.text = "$distKm · ~$mins min · Offline ✅"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    tvInstruction.text = "❌ Routing error: ${e.message}"
                }
            }
        }
    }

    // ── Draw route ─────────────────────────────────────────────
    private fun drawRoute(points: List<GeoPoint>) {
        routeOverlay?.let { mapView.overlays.remove(it) }
        routeOverlay = Polyline().apply {
            setPoints(points)
            color = Color.parseColor("#2196F3")
            width = 10f
            mapView.overlays.add(this)
        }
        mapView.invalidate()
        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 150)
    }

    // ── Location ───────────────────────────────────────────────
    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    myLocation = GeoPoint(it.latitude, it.longitude)
                    updateMyMarker(myLocation!!)
                }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    myLocation = GeoPoint(it.latitude, it.longitude)
                    mapView.controller.animateTo(myLocation)
                }
            }
        }
    }

    private fun updateMyMarker(point: GeoPoint) {
        if (myMarker == null) {
            myMarker = Marker(mapView).apply {
                title = "You"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
        }
        myMarker!!.position = point
        mapView.invalidate()
    }

    // ── Helpers ────────────────────────────────────────────────
    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSearch.isEnabled = !loading
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etDestination.windowToken, 0)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED)
            startLocationUpdates()
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        executor.shutdown()
        hopper?.close()
    }
}