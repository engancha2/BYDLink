package com.bydlink.carapp.camera

import android.content.Context
import org.json.JSONObject

/** Dónde está una cámara: índice de vista/canal BMM (`enablePreviewCallback(view)`) y trozo dentro del frame. */
data class CamRef(val view: Int, val tile: Int)

/**
 * Perfil de cámara de ESTE coche. Lo propone el test de cámara (§4 del plan) y se puede
 * corregir a mano con el comando `setCamProfile`. En producción no se adivina nada que no esté aquí,
 * salvo la geometría del frame, que se deduce del tamaño real de los datos.
 */
data class CamProfile(
    /** auto | avm | normal (AVMCamera o NormalCamera). */
    val api: String = "auto",
    /** Id BMM del dispositivo; -1 = descubrirlo con BmmCameraInfo (tags pano_h, pano_l, …). */
    val cameraId: Int = -1,
    /** setPreviewSize antes de arrancar; 0 = no llamarlo (lo recomendado: el dispositivo es compartido). */
    val previewW: Int = 0,
    val previewH: Int = 0,
    /** stopPreview()+startPreview() tras enablePreviewCallback (algunos firmwares lo necesitan). */
    val restart: Boolean = false,
    /** Enganchar un ImageReader en el índice 0 antes de startPreview (otros firmwares lo necesitan). */
    val surface: Boolean = false,
    /** Disposición de los trozos en el frame, "COLUMNASxFILAS": auto | 4x1 (tira de 4) | 2x2 | 2x1 | 1x1 | 1x4. */
    val grid: String = "auto",
    /** Los datos son NV12 (U antes que V) aunque el formato diga 21. Se ve en la miniatura: gente azul = NV12. */
    val nv12: Boolean = false,
    /** Nombre de cámara -> (vista, trozo). Por defecto el orden del Atto 3: Frontal, Trasera, Izquierda, Derecha. */
    val cams: Map<String, CamRef> = DEFAULT_CAMS,
    /** default | test | manual. Un perfil manual no lo pisa el test automático. */
    val source: String = "default"
) {
    /** true si las cámaras van por canales distintos (hay que cambiar de vista para cambiar de cámara). */
    val channels: Boolean get() = cams.values.map { it.view }.distinct().size > 1

    fun toJson(): JSONObject = JSONObject().apply {
        put("api", api); put("cameraId", cameraId); put("previewW", previewW); put("previewH", previewH)
        put("restart", restart); put("surface", surface); put("grid", grid); put("nv12", nv12); put("source", source)
        put("cams", JSONObject().apply { cams.forEach { (k, v) -> put(k, JSONObject().put("view", v.view).put("tile", v.tile)) } })
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "api" to api, "cameraId" to cameraId, "previewW" to previewW, "previewH" to previewH,
        "restart" to restart, "surface" to surface, "grid" to grid, "nv12" to nv12, "source" to source,
        "mode" to if (channels) "channels" else "strip",
        "cams" to cams.mapValues { mapOf("view" to it.value.view, "tile" to it.value.tile) }
    )

    /** Aplica los campos presentes en [p] (payload de `setCamProfile`). */
    fun merged(p: Map<String, Any?>): CamProfile {
        fun i(k: String, d: Int) = (p[k] as? Number)?.toInt() ?: d
        fun b(k: String, d: Boolean) = p[k] as? Boolean ?: d
        fun s(k: String, d: String) = p[k] as? String ?: d
        val newCams = (p["cams"] as? Map<*, *>)?.let { m ->
            m.mapNotNull { (k, v) ->
                val name = k as? String ?: return@mapNotNull null
                when (v) {
                    is Map<*, *> -> name to CamRef((v["view"] as? Number)?.toInt() ?: 0, (v["tile"] as? Number)?.toInt() ?: 0)
                    is List<*> -> name to CamRef((v.getOrNull(0) as? Number)?.toInt() ?: 0, (v.getOrNull(1) as? Number)?.toInt() ?: 0)
                    is Number -> name to CamRef(0, v.toInt())   // solo el trozo de la tira
                    else -> null
                }
            }.toMap().takeIf { it.isNotEmpty() }
        }
        return copy(
            api = s("api", api).takeIf { it in setOf("auto", "avm", "normal") } ?: api,
            cameraId = i("cameraId", cameraId), previewW = i("previewW", previewW), previewH = i("previewH", previewH),
            restart = b("restart", restart), surface = b("surface", surface),
            grid = s("grid", grid).takeIf { it == "auto" || Regex("[1-4]x[1-4]").matches(it) } ?: grid,
            nv12 = b("nv12", nv12), cams = newCams ?: cams, source = "manual"
        )
    }

    companion object {
        val NAMES = listOf("front", "back", "left", "right")
        val DEFAULT_CAMS: Map<String, CamRef> = linkedMapOf(
            "front" to CamRef(0, 0), "back" to CamRef(0, 1), "left" to CamRef(0, 2), "right" to CamRef(0, 3)
        )
        val ES = mapOf("front" to "Frontal", "back" to "Trasera", "left" to "Izquierda", "right" to "Derecha", "mosaic" to "4 en 1")
        private const val PREFS = "bydlink_cam"

        fun fromJson(o: JSONObject): CamProfile {
            val c = o.optJSONObject("cams")
            val cams = if (c == null) DEFAULT_CAMS else linkedMapOf<String, CamRef>().apply {
                c.keys().forEach { k -> c.optJSONObject(k)?.let { put(k, CamRef(it.optInt("view"), it.optInt("tile"))) } }
            }.ifEmpty { DEFAULT_CAMS }
            return CamProfile(
                api = o.optString("api", "auto"), cameraId = o.optInt("cameraId", -1),
                previewW = o.optInt("previewW"), previewH = o.optInt("previewH"),
                restart = o.optBoolean("restart"), surface = o.optBoolean("surface"),
                grid = o.optString("grid", "auto"), nv12 = o.optBoolean("nv12"), cams = cams,
                source = o.optString("source", "default")
            )
        }

        fun load(ctx: Context): CamProfile = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("profile", null)
                ?.let { fromJson(JSONObject(it)) } ?: CamProfile()
        } catch (e: Throwable) { CamProfile() }

        fun save(ctx: Context, p: CamProfile) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("profile", p.toJson().toString()).apply()
        }

        fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
