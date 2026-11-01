package com.bydlink.carapp.auto

/**
 * Una automatización tal como la guarda el móvil en `cars/{id}/automations/{ruleId}` (ver
 * protocol/automatizaciones.md): "Cuando [when] · Solo si [only] · Haz [do]". Aquí solo se lee y valida;
 * lo que no se entiende se descarta (una regla mal escrita no puede hacer nada raro).
 */
data class Trigger(val type: String, val p: Map<String, Any?>)
data class Cond(val type: String, val p: Map<String, Any?>)
data class Act(val action: String, val payload: Map<String, Any?>)

data class Rule(
    val id: String,
    val name: String,
    val on: Boolean,
    val trigger: Trigger,
    val only: List<Cond>,
    val acts: List<Act>,
    /** Minutos sin volver a saltar después de hacerlo (mínimo 1). */
    val cooldownMin: Int
) {
    /** Clave de la memoria de esta regla: cambia si se edita el disparador (así no arrastra el estado viejo). */
    val memKey: String get() = id + ":" + trigger.p.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    companion object {
        val TRIGGERS = setOf("acc", "lock", "charge", "soc", "time", "place", "temp", "sentry", "aux12v")
        val CONDS = setOf("time", "soc", "place", "temp", "state")
        const val MAX_ACTS = 5

        /** null si la regla no vale (falta el disparador o no tiene ninguna acción). */
        fun parse(id: String, m: Map<*, *>?): Rule? {
            m ?: return null
            val w = m["when"] as? Map<*, *> ?: return null
            val type = w["type"] as? String ?: return null
            if (type !in TRIGGERS) return null
            val only = list(m["only"]).mapNotNull { c ->
                val cm = c as? Map<*, *> ?: return@mapNotNull null
                val t = cm["type"] as? String ?: return@mapNotNull null
                if (t in CONDS) Cond(t, strMap(cm)) else null
            }
            val acts = list(m["do"]).mapNotNull { a ->
                val am = a as? Map<*, *> ?: return@mapNotNull null
                val action = am["action"] as? String ?: return@mapNotNull null
                Act(action, strMap(am["payload"] as? Map<*, *>))
            }.take(MAX_ACTS)
            if (acts.isEmpty()) return null
            return Rule(
                id = id,
                name = (m["name"] as? String)?.take(80)?.ifBlank { null } ?: "Automatización",
                on = m["on"] as? Boolean ?: true,
                trigger = Trigger(type, strMap(w)),
                only = only,
                acts = acts,
                cooldownMin = ((m["cooldownMin"] as? Number)?.toInt() ?: 5).coerceIn(1, 1440)
            )
        }

        /** Firebase devuelve las listas como List o como Map {"0":…,"1":…} si tienen huecos. */
        private fun list(v: Any?): List<Any?> = when (v) {
            is List<*> -> v.filterNotNull()
            is Map<*, *> -> v.entries.sortedBy { (it.key as? String)?.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { it.value }
            else -> emptyList()
        }
        private fun strMap(m: Map<*, *>?): Map<String, Any?> =
            m?.entries?.filter { it.key is String }?.associate { it.key as String to it.value } ?: emptyMap()
    }
}

// Lectura cómoda de parámetros (en Firebase los números llegan como Long o Double).
internal fun Map<String, Any?>.num(k: String): Double? = (this[k] as? Number)?.toDouble() ?: (this[k] as? String)?.toDoubleOrNull()
internal fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean
internal fun Map<String, Any?>.str(k: String): String? = this[k] as? String
internal fun Map<String, Any?>.days(): Set<Int> =
    ((this["days"] as? List<*>) ?: (this["days"] as? Map<*, *>)?.values?.toList() ?: emptyList<Any?>())
        .mapNotNull { (it as? Number)?.toInt() }.filter { it in 1..7 }.toSet()

/** "HH:MM" → minutos desde medianoche (null si no vale). */
internal fun minutesOf(s: String?): Int? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s?.trim() ?: return null) ?: return null
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}
