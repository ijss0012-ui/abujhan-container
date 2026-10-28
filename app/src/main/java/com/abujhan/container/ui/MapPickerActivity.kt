package com.abujhan.container.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.abujhan.container.R
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapEventsReceiver

/**
 * اختيار موقع من الخريطة: اضغط على نقطة لتحديدها ثم «تأكيد».
 * يعيد خط العرض والطول عبر نتيجة Activity.
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var selected: GeoPoint? = null
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_map_picker)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val hasStart = intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LNG)
        val start = GeoPoint(
            intent.getDoubleExtra(EXTRA_LAT, DEFAULT_LAT),
            intent.getDoubleExtra(EXTRA_LNG, DEFAULT_LNG),
        )
        map.controller.setZoom(14.0)
        map.controller.setCenter(start)
        if (hasStart) setSelected(start)

        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    setSelected(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
        )

        findViewById<MaterialButton>(R.id.btnConfirmPoint).setOnClickListener {
            val point = selected ?: return@setOnClickListener
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_LAT, point.latitude)
                    .putExtra(EXTRA_LNG, point.longitude),
            )
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    private fun setSelected(point: GeoPoint) {
        selected = point
        marker?.let { map.overlays.remove(it) }
        marker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(marker)
        map.invalidate()
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        private const val DEFAULT_LAT = 24.7136
        private const val DEFAULT_LNG = 46.6753
    }
}
