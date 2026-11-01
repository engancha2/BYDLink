package com.bydlink.carapp.auto

import android.content.Context
import android.util.Log
import com.bydlink.carapp.service.StateHub
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Automatizaciones "si pasa esto, haz esto", evaluadas EN EL COCHE (funcionan con el móvil apagado).
 * Las reglas las escribe el móvil en `cars/{id}/automations`; se guardan también aquí para seguir funcionando
 * sin cobertura. Cada 5 s se mira el estado (StateHub) y lo que salta se hace con [run], la misma ruta que las
 * órdenes del móvil (mismas comprobaciones de seguridad), más [Policy] por encima. Lo hecho va a
 * `cars/{id}/autoLog` (las 100 últimas) y `cars/{id}/autoState/{ruleId}`. También manda los avisos al móvil
 * (los de las reglas y los del centinela) con [Push]. Contrato en protocol/automatizaciones.md.
 */
class Automations(
    ctx: Context,
    carId: String,
    private val run: (String, Map<String, Any?>, (Boolean, Map<String, Any?>) -> Unit) -> Unit
) {
    private companion object {
        const val TAG = "BYDLink"
        const val KEEP_LOG = 100
        const val ACT_TIMEOUT_S = 30L
        val CAMS = mapOf("back" to "trasera", "front" to "delantera", "left" to "izquierda", "right" to "derecha")
    }

    val push = Push(ctx, carId)
    private val root = com.bydlink.carapp.App.db.getReference("cars/$carId")
    private val prefs = ctx.applicationContext.getSharedPreferences("auto", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auto") }
    private val actExec = Executors.newSingleThreadExecutor { r -> Thread(r, "auto-do") }
    private val engine = Engine()
    private val events = ConcurrentLinkedQueue<Event>()
    @Volatile private var rules: List<Rule> = emptyList()
    private var prev: Facts? = null
    private lateinit var mem: Mem

    fun start() {
        exec.execute {
            mem = try { Mem.fromMap(toMap(JSONObject(prefs.getString("mem", "{}") ?: "{}"))) } catch (e: Throwable) { Mem() }
            setRules(try { toMap(JSONObject(prefs.getString("rules", "{}") ?: "{}")) } catch (e: Throwable) { emptyMap() }, save = false)
        }
        root.child("automations").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val raw = (s.value as? Map<*, *>) ?: emptyMap<Any, Any>()
                exec.execute { setRules(raw, save = true) }
            }
            override fun onCancelled(e: DatabaseError) { Log.w(TAG, "automatizaciones: sin acceso (${e.message})") }
        })
        Events.listen { type, data ->
            if (type != Events.SENTRY) return@listen
            events.add(Event(type, data))
            exec.execute { tick() }
            val cam = CAMS[data["camera"] as? String] ?: "trasera"
            push.notify("Centinela", "Movimiento cerca del coche (cámara $cam) · " + hhmm(System.currentTimeMillis()),
                "sentry", tag = "sentry", url = "./#sentry")
        }
        push.start()
        exec.scheduleWithFixedDelay({ tick() }, 15, 5, TimeUnit.SECONDS)
    }

    /** Órdenes propias: probar una regla a mano y mandar un aviso de prueba. false si no es nuestra. */
    fun dispatch(action: String, p: Map<String, Any?>, respond: (Boolean, Map<String, Any?>) -> Unit): Boolean {
        when (action) {
            "runAutomation" -> {
                val id = p["id"] as? String
                val r = rules.firstOrNull { it.id == id }
                if (r == null) { respond(false, mapOf("error" to "El coche aún no tiene esa automatización (¿sin cobertura?).")); return true }
                actExec.execute {
                    val res = perform(r, "Probada a mano desde el móvil", manual = true)
                    respond(true, mapOf("ok" to res.first, "results" to res.second))
                }
            }
            "pushTest" -> exec.execute {
                val res = push.send("BYDLink", "Así te llegarán los avisos del coche.", "test", tag = "test", url = "./#settings",
                    only = p["sub"] as? String)
                respond((res["sent"] as? Int ?: 0) > 0, res)
            }
            else -> return false
        }
        return true
    }

    private fun setRules(raw: Map<*, *>, save: Boolean) {
        rules = raw.entries.mapNotNull { (k, v) -> (k as? String)?.let { Rule.parse(it, v as? Map<*, *>) } }
        mem.retain(rules)
        if (save) try {
            @Suppress("UNCHECKED_CAST")
            prefs.edit().putString("rules", JSONObject(raw as Map<String, Any?>).toString()).apply()
        } catch (e: Throwable) { Log.w(TAG, "automatizaciones: no se pudieron guardar", e) }
        Log.i(TAG, "automatizaciones: ${rules.size} (${rules.count { it.on }} activas)")
    }

    private fun tick() {
        try {
            if (!::mem.isInitialized) return
            val f = Facts.from(StateHub.state, StateHub.accOn, StateHub.sdkOk, System.currentTimeMillis())
            val evs = generateSequence { events.poll() }.toList()
            val fires = engine.evaluate(rules, prev, f, evs, mem)
            prev = f
            if (mem.dirty) { mem.dirty = false; prefs.edit().putString("mem", JSONObject(mem.toMap()).toString()).apply() }
            fires.forEach { fr -> Log.i(TAG, "automatización «${fr.rule.name}»: ${fr.why}"); actExec.execute { perform(fr.rule, fr.why) } }
        } catch (e: Throwable) { Log.e(TAG, "automatizaciones: fallo en la vuelta", e) }
    }

    /** Hace las acciones de la regla, una tras otra, y lo apunta en el registro. Devuelve (todo bien, resultados). */
    private fun perform(r: Rule, why: String, manual: Boolean = false): Pair<Boolean, List<Map<String, Any?>>> {
        val results = ArrayList<Map<String, Any?>>()
        for (a in r.acts) {
            val f = Facts.from(StateHub.state, StateHub.accOn, StateHub.sdkOk, System.currentTimeMillis())
            val label = Policy.label(a)
            val blocked = Policy.check(a, f)
            if (blocked != null) { results += mapOf("action" to a.action, "label" to label, "ok" to false, "msg" to blocked); continue }
            if (a.action == "notify") {
                val text = (a.payload["text"] as? String)?.trim()?.ifBlank { null } ?: why
                val res = push.send(r.name, text, if (manual) "test" else "autos", tag = "auto-${r.id}", url = "./#settings")
                val n = res["sent"] as? Int ?: 0
                results += mapOf("action" to a.action, "label" to label, "ok" to (n > 0),
                    "msg" to if (n > 0) "Aviso enviado a $n " + (if (n == 1) "móvil" else "móviles") else res["error"])
                continue
            }
            val latch = CountDownLatch(1)
            var ok = false; var data: Map<String, Any?> = emptyMap()
            try { run(a.action, a.payload) { o, d -> ok = o; data = d; latch.countDown() } }
            catch (e: Throwable) { data = mapOf("error" to (e.message ?: "error")); latch.countDown() }
            val inTime = latch.await(ACT_TIMEOUT_S, TimeUnit.SECONDS)
            results += mapOf("action" to a.action, "label" to label, "ok" to (inTime && ok),
                "msg" to if (!inTime) "El coche no ha respondido a tiempo." else data["error"] as? String)
        }
        val allOk = results.all { it["ok"] == true }
        log(r, why, allOk, results, manual)
        return allOk to results
    }

    private fun log(r: Rule, why: String, ok: Boolean, results: List<Map<String, Any?>>, manual: Boolean) {
        try {
            val ref = root.child("autoLog").push()
            val key = ref.key ?: return
            ref.setValue(mapOf("ts" to ServerValue.TIMESTAMP, "rule" to r.id, "name" to r.name, "why" to why,
                "ok" to ok, "manual" to manual, "results" to results))
            root.child("autoState").child(r.id).setValue(mapOf("last" to ServerValue.TIMESTAMP, "ok" to ok, "why" to why))
            // Las keys en preferencias para borrar lo que pase de 100 sin tener que leer el registro.
            val arr = try { JSONArray(prefs.getString("logKeys", "[]")) } catch (e: Throwable) { JSONArray() }
            val keys = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            keys += key
            while (keys.size > KEEP_LOG) root.child("autoLog").child(keys.removeAt(0)).removeValue()
            prefs.edit().putString("logKeys", JSONArray(keys).toString()).apply()
        } catch (e: Throwable) { Log.w(TAG, "automatizaciones: no se pudo apuntar en el registro", e) }
    }

    private fun hhmm(ts: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "ES")).format(java.util.Date(ts))

    /** JSON guardado → mapas y listas (como los da Firebase). */
    private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> plain(o.opt(k)) }
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}
