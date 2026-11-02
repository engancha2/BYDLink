package com.bydlink.carapp.ui

import android.content.Context
import android.util.Log
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Idiomas de la pantalla del coche. El texto de la app está escrito en español; cada idioma es un diccionario
 * español → idioma en assets/i18n/<lang>.json. Las etiquetas (TrText), los botones y los avisos se traducen solos,
 * también los textos con datos ("Cargando · 1 h para llenar") mediante plantillas con {0}, {1}…
 * Idioma: el elegido en Ajustes; si no, el del sistema si está entre los nuestros; si no, inglés.
 */
internal object I18n {
    val LANGS = linkedMapOf(
        "en" to "English", "es" to "Español", "pt" to "Português", "de" to "Deutsch", "fr" to "Français", "it" to "Italiano",
        "nl" to "Nederlands", "ro" to "Română", "tr" to "Türkçe", "ar" to "العربية", "zh" to "中文", "ja" to "日本語", "ko" to "한국어",
    )
    @Volatile var lang = "en"; private set
    private var dict: Map<String, String> = emptyMap()
    private var pats: List<Triple<Regex, String, List<Int>>> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 600
    }
    private val ws = Regex("\\s+")
    private val ph = Regex("\\{(\\d+)\\}")
    private val numLast = Regex("^(.+?)(\\s*)([+\\-]?\\d[\\d.,:]*\\s*(?:%|°C?|km|kWh|kW|V|h|min|bar|€)?)$")

    val rtl: Boolean get() = lang == "ar"

    /** Formato de fechas y números del idioma (árabe con cifras occidentales, como el resto de la pantalla). */
    val locale: Locale get() = when (lang) {
        "en" -> Locale.UK; "es" -> Locale("es", "ES"); "pt" -> Locale("pt", "BR"); "de" -> Locale.GERMANY
        "fr" -> Locale.FRANCE; "it" -> Locale.ITALY; "nl" -> Locale("nl", "NL"); "ro" -> Locale("ro", "RO")
        "tr" -> Locale("tr", "TR"); "ar" -> Locale.forLanguageTag("ar-u-nu-latn"); "zh" -> Locale.SIMPLIFIED_CHINESE
        "ja" -> Locale.JAPAN; "ko" -> Locale.KOREA; else -> Locale.UK
    }

    /** Patrón de fecha en el orden de cada idioma ("EEEdMMM" → "EEE d MMM", "M月d日EEE"…). */
    fun pattern(skeleton: String): String =
        try { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) } catch (e: Throwable) { skeleton }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        lang = p.getString("lang", null)?.takeIf { it in LANGS } ?: Locale.getDefault().language.takeIf { it in LANGS } ?: "en"
        synchronized(cache) { cache.clear() }
        if (lang == "es") { dict = emptyMap(); pats = emptyList(); return }
        try {
            val o = JSONObject(ctx.assets.open("i18n/$lang.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val d = HashMap<String, String>(o.length() * 2)
            val ps = ArrayList<Triple<Regex, String, List<Int>>>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next(); val v = o.optString(k)
                if (v.isEmpty()) continue
                if (ph.containsMatchIn(k)) {
                    val order = ArrayList<Int>(); val sb = StringBuilder("^"); var last = 0
                    for (m in ph.findAll(k)) {
                        sb.append(Regex.escape(k.substring(last, m.range.first))).append("(.+?)")
                        order += m.groupValues[1].toInt(); last = m.range.last + 1
                    }
                    sb.append(Regex.escape(k.substring(last))).append('$')
                    ps += Triple(Regex(sb.toString()), v, order)
                } else d[k] = v
            }
            dict = d; pats = ps.sortedByDescending { it.first.pattern.length }
        } catch (e: Throwable) {
            Log.w("BYDLink", "idioma $lang: ${e.message}"); dict = emptyMap(); pats = emptyList()
        }
    }

    fun set(ctx: Context, code: String) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("lang", code).apply()
        load(ctx)
    }

    private fun up1(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase(locale) + s.substring(1)
    private fun low1(s: String) =   // "Viajes" → "viajes", pero "BYDLink" o "USB" se quedan igual
        if (s.length > 1 && s[0].isUpperCase() && s[1].isLowerCase()) s.substring(0, 1).lowercase(locale) + s.substring(1) else s

    /** La palabra tal cual o con otra mayúscula inicial ("viajes" con la clave "Viajes"). */
    private fun word(k: String): String? {
        dict[k]?.let { return it }
        if (k.isEmpty()) return null
        val up = up1(k)
        if (up != k) dict[up]?.let { return if (lang == "de") it else low1(it) }
        val low = k.substring(0, 1).lowercase(Locale.ROOT) + k.substring(1)
        if (low != k) dict[low]?.let { return up1(it) }
        return null
    }

    private const val UNIT = "(?:%|°C?|km/h|km|kWh/100 km|kWh|kW|V|h|min|s|bar|€/kWh|€|MB|GB|KB)(?!\\p{L})"
    private val numUnit = Regex("^([+\\-]?\\d[\\d.,:]*\\s*(?:$UNIT)?)(\\s*)(\\p{L}.*)$")
    private val numBare = Regex("^([+\\-]?\\d[\\d.,:]*)(\\s*)(\\p{L}.*)$")
    private val edge = Regex("^([·•●✓⚠⬇↑↓›‹→\\-–—«“\"'(\\[\\s]*)(.*?)([\\s:.;,…!?»”\"')\\]›]*)$")
    private val seps = Regex("( [·→] )")
    private val nameStart = Regex("^[\\p{Lu}«“\"'(]")
    private val word2 = Regex("\\p{L}{2,}")
    private val units = setOf("km", "min", "kwh", "kw", "bar", "mb", "gb", "kb")
    /** Un resto sin traducir vale si es un dato: un nombre propio, algo entre comillas o solo cifras y unidades. */
    private fun dataLike(s: String) = nameStart.containsMatchIn(s) ||
        (s.any { it.isDigit() } && word2.findAll(s).none { it.value.lowercase(Locale.ROOT) !in units })

    /** Un trozo. loose: además prueba "trozo conocido + resto" ("Actualizado" + "hace 5 min"). null si no hay. */
    private fun one(k: String, loose: Boolean, d: Int = 0): String? {
        if (d > 8 || k.isEmpty()) return null
        word(k)?.let { return it }
        for ((re, target, order) in pats) {   // plantillas; lo que llena cada hueco también se traduce ("Neón Rosa")
            val m = re.matchEntire(k) ?: continue
            val got = HashMap<Int, String>()
            order.forEachIndexed { i, n -> val g = m.groupValues[i + 1]; got[n] = if (g.any { it.isLetter() }) one(g, true, d + 1) ?: g else g }
            return target.replace(ph) { g -> got[g.groupValues[1].toInt()] ?: "" }
        }
        // número (con su unidad) + texto ("1 ventana abierta", "0,00 € en cargas", "331 km de autonomía") o texto + número
        for (re in arrayOf(numUnit, numBare)) {
            val m = re.matchEntire(k) ?: continue
            val t = m.groupValues[3]
            val v = word(t) ?: one(t, loose, d + 1) ?: continue
            return m.groupValues[1] + m.groupValues[2] + if (lang == "de" || t[0].isUpperCase()) v else low1(v)
        }
        numLast.matchEntire(k)?.let { m -> word(m.groupValues[1].trim())?.let { return it + m.groupValues[2] + m.groupValues[3] } }
        if (loose) {   // trozo conocido + resto: "Dormido hace 5 min", "Navegar con Waze"
            val w = k.split(' ')
            for (i in w.size - 1 downTo 1) {
                val pre = w.subList(0, i).joinToString(" ")
                var tail = ""
                var r = word(pre)
                if (r == null && (pre.endsWith(":") || pre.endsWith(","))) { r = word(pre.dropLast(1)); tail = pre.takeLast(1) }
                if (r == null) continue
                val rest = w.subList(i, w.size).joinToString(" ")
                val rr = one(rest, true, d + 1)
                if (rr != null || dataLike(rest)) return "$r$tail ${rr ?: rest}"
            }
        }
        // signos en los bordes: "· 21 GB libres", "Límite de carga:", "«Texto.»", "✓ Última vez…"
        edge.matchEntire(k)?.let { m ->
            val (a, core, b) = m.destructured
            if ((a.isNotEmpty() || b.isNotEmpty()) && core.isNotEmpty()) {
                one(core, loose, d + 1)?.let { return a + it + b }
                if (b.isNotEmpty() && b[0] in ".…!?") one(core + b[0], loose, d + 1)?.let { return a + it + b.substring(1) }
            }
        }
        return null
    }

    /** Traduce un texto en español; si no hay traducción, lo deja tal cual. */
    fun tr(s: String): String {
        if (dict.isEmpty() && pats.isEmpty()) return s
        val k = s.replace(ws, " ").trim()
        if (k.isEmpty() || k.none { it.isLetter() }) return s
        synchronized(cache) { cache[k] }?.let { return it }
        var out = one(k, false)
        if (out == null && seps.containsMatchIn(k)) {   // "Este mes · noviembre", "Casa → Trabajo": trozo a trozo
            var hit = false
            val sb = StringBuilder(); var last = 0
            for (m in seps.findAll(k)) {
                val p = k.substring(last, m.range.first)
                sb.append(one(p, true)?.also { hit = true } ?: p).append(m.value); last = m.range.last + 1
            }
            val p = k.substring(last); sb.append(one(p, true)?.also { hit = true } ?: p)
            if (hit) out = sb.toString()
        }
        if (out == null) out = one(k, true)
        val r = out ?: s
        synchronized(cache) { cache[k] = r }
        return r
    }
}

/** TextView que traduce sola cualquier texto que le pongan (etiquetas, botones y chips de toda la app). */
internal class TrText(ctx: Context) : TextView(ctx) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(if (text is String) I18n.tr(text) else text, type)
    }
}
