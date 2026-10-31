package com.bydlink.carapp.camera

/** Imagen NV21 ya recortada y escalada (memoria propia, se puede usar fuera del callback). */
class Rendered(val nv21: ByteArray, val w: Int, val h: Int)

/**
 * Traduce "front/back/left/right/mosaic" a un trozo del frame según el perfil, y lo recorta+escala.
 * Cambiar de cámara NO reabre nada: solo cambia el recorte (en modo canales, pide otra vista al hub).
 */
class CamViews(private val hub: CameraHub) {

    /** Vistas disponibles para el móvil (mosaico solo si las 4 van en la misma vista/tira). */
    fun names(): List<String> {
        val p = hub.profile
        val g = hub.geom
        val cams = p.cams.filter { (_, r) -> g == null || p.channels || g.validTile(r.tile) }.keys.toList()
        return if (!p.channels && cams.size >= 2 && (g == null || g.tiles >= 2)) cams + "mosaic" else cams
    }

    /** Normaliza el nombre pedido por el móvil; lanza CamError si no existe en este coche. */
    fun check(name: String?): String {
        val n = when (name?.lowercase()) {
            null, "" -> hub.profile.cams.keys.firstOrNull() ?: "front"
            "rear", "trasera" -> "back"
            "frontal" -> "front"
            "izquierda" -> "left"
            "derecha" -> "right"
            "all", "4en1", "quad" -> "mosaic"
            else -> name.lowercase()
        }
        val p = hub.profile
        if (n == "mosaic") {
            if (p.channels) throw CamError("bad_camera", "En este coche las cámaras van por canales separados: el «4 en 1» no está disponible.")
            return n
        }
        if (!p.cams.containsKey(n)) throw CamError("bad_camera", "Este coche no tiene la cámara «$name». Disponibles: ${names().joinToString()}.")
        return n
    }

    /** En modo canales, pide al hub la vista de esta cámara (no hace nada en modo tira). */
    fun want(name: String) {
        val p = hub.profile
        if (!p.channels) return
        p.cams[name]?.let { hub.wantedView = it.view }
    }

    /** Recorta+escala la cámara [name] del frame a un ancho [maxW]. null si el frame no la trae. */
    fun render(f: CameraHub.Frame, name: String, maxW: Int): Rendered? {
        val p = hub.profile
        val g = f.g
        if (name == "mosaic") {
            if (p.channels) return null
            val tiles = CamProfile.NAMES.map { n -> p.cams[n]?.takeIf { it.view == f.view }?.tile ?: -1 }
            val w = maxW and 3.inv()
            val h = (Yuv.fit(g.tileW, g.tileH, w / 2).second * 2) and 3.inv()
            return Rendered(Yuv.mosaic(f.data, g, tiles, w, h), w, h)
        }
        val r = p.cams[name] ?: return null
        if (r.view != f.view) return null
        val (w, h) = Yuv.fit(g.tileW, g.tileH, maxW)
        return Rendered(Yuv.tile(f.data, g, r.tile, w, h), w, h)
    }
}
