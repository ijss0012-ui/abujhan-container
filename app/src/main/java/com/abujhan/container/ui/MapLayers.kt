package com.abujhan.container.ui

import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/** طبقات الخريطة: شوارع، قمر صناعي، تضاريس. كلها بدون مفتاح API. */
object MapLayers {

    private const val SATELLITE_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"

    val STREET = TileSourceFactory.MAPNIK

    /** صور الأقمار الصناعية من Esri (ترتيب الرابط z/y/x). */
    val SATELLITE = object : XYTileSource(
        "EsriWorldImagery", 0, 19, 256, ".jpg",
        arrayOf(SATELLITE_URL),
        "Esri, Maxar, Earthstar Geographics",
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "$SATELLITE_URL$z/$y/$x"
        }
    }

    val TERRAIN = XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf("https://tile.opentopomap.org/"),
        "© OpenTopoMap",
    )
}
