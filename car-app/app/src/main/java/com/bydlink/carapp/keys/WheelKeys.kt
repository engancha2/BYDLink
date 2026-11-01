package com.bydlink.carapp.keys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.bydlink.carapp.BuildConfigCompat
import com.bydlink.carapp.camera.VideoStore
import com.bydlink.carapp.car.CarShell
import com.bydlink.carapp.service.StateHub
import com.google.firebase.database.ServerValue
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Botones del volante (ver protocol/volante.md). Tres fuentes, como hacen los lanzadores para BYD:
 *  - KeyEvents de Android por un servicio de ACCESIBILIDAD con filtro de teclas ([WheelKeyService]): llegan antes que a
 *    las apps y se pueden quedar (consumir). Solo se queda los botones que el usuario ha asignado; los demás pasan tal cual.
 *  - Eventos del SDK BYD (INSTRUMENT_INPUT_KEYCODE_STATUS y el botón de voz): solo se oyen, no se pueden quitar.
 *  - `getevent` por el ADB del coche, solo en "Aprender botón", para ver lo que manda el hardware.
 * Pulsación corta, larga (≥ 0,6 s) o doble (dos en 0,35 s) → una acción de BYDLink. Si un botón asignado se pulsa de
 * una forma que no tiene acción (p. ej. solo hay acción larga y se pulsa corto), se le devuelve al coche su función normal.
 */
object WheelKeys {
    const val SHORT = "short"; const val LONG = "long"; const val DOUBLE = "double"
    private const val TAG = "BYDLink"
    private const val LONG_MS = 600L
    private const val DOUBLE_MS = 350L
    private const val LEARN_MIN = 5
    private const val MAX_LEARNED = 40
    private const val MAX_LOG = 30

    // Feature ids de los botones en el framework del Surf (BYDAutoFeatureIds; AutoVideo escucha el primero).
    const val FID_INPUT_KEYCODE = 0x4a801010     // Instrument: INSTRUMENT_INPUT_KEYCODE_STATUS
    const val FID_VOICE_KEY = 0x4a80101e         // Audio: AUDIO_VOICE_INPUT_KEY_STATUS
    const val FID_CUSTOM_PLAY = -0x66fffcf7      // Audio: AUDIO_CUSTOM_KEY_MEDIA_PLAY_PAUSE_NOTIFY (0x99000309)

    /** Botones que nunca se tocan (sin ellos no se puede salir de una app ni apagar la pantalla). */
    private val BLOCKED = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_APP_SWITCH)
    private val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_HEADSETHOOK)

    /** Lo que se puede asignar (id → texto). Más `app:<paquete>` para abrir una app. */
    val ACTIONS = linkedMapOf(
        "page:inicio" to "Abrir BYDLink", "page:camara" to "Abrir la cámara", "page:clima" to "Abrir el clima",
        "page:energia" to "Abrir energía", "page:mapa" to "Abrir el mapa", "page:musica" to "Abrir la música",
        "page:coche" to "Abrir el coche",
        "clip" to "Guardar vídeo (lo último + 1 min)", "photo" to "Foto de la cámara", "sentry" to "Centinela sí / no",
        "climate" to "Aire sí / no",
        "media:next" to "Siguiente canción", "media:prev" to "Canción anterior", "media:playpause" to "Play / pausa"
    )

    class Binding(val short: String?, val long: String?, val double: String?) {
        fun isEmpty() = short == null && long == null && double == null
        fun get(press: String) = when (press) { LONG -> long; DOUBLE -> double; else -> short }
        fun with(press: String, a: String?) = when (press) { LONG -> Binding(short, a, double); DOUBLE -> Binding(short, long, a); else -> Binding(a, long, double) }
        fun toMap() = mapOf(SHORT to short, LONG to long, DOUBLE to double).filterValues { it != null }
    }

    /** Un evento visto (para "Aprender botón"). */
    class Ev(val ts: Long, val src: String, val id: String, val name: String, val info: String) {
        fun toMap() = mapOf("ts" to ts, "src" to src, "id" to id, "name" to name, "info" to info)
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "volante") }
    private val shellExec = Executors.newSingleThreadExecutor { r -> Thread(r, "volante-adb") }
    @Volatile private var started = false

    @Volatile var enabled = false; private set
    @Volatile var bindings: Map<String, Binding> = emptyMap(); private set
    @Volatile var learnUntil = 0L; private set
    val learning get() = System.currentTimeMillis() < learnUntil
    @Volatile private var service: WheelKeyService? = null
    val serviceOn get() = service != null
    @Volatile var sdkState = "no"; private set
    @Volatile var adbState = ""; private set
    @Volatile private var devices: List<String> = emptyList()

    private val learned = LinkedHashMap<String, MutableMap<String, Any?>>()
    private val log = ArrayDeque<Ev>()
    /** La página "Aprender botón" del lanzador se apunta aquí (se llama en el hilo principal). */
    @Volatile var onEvent: ((Ev) -> Unit)? = null

    // ═════════════════════════ arranque y configuración ═════════════════════════
    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        startedAt = System.currentTimeMillis()
        load()
        // Cada minuto: el servicio de accesibilidad sigue activo (BYD lo quita al apagar el coche, porque fuerza el cierre
        // de las apps) y el oyente del SDK puesto. Y avisar a la nube de cómo está.
        exec.scheduleWithFixedDelay({ try { tick() } catch (e: Throwable) { Log.e(TAG, "volante: fallo", e) } }, 3, 60, TimeUnit.SECONDS)
        // A los 12 s, si está puesto pero no se ha enlazado, se fuerza (no esperar al minuto con el coche recién encendido).
        exec.schedule({ try { ensureService() } catch (e: Throwable) {} }, 12, TimeUnit.SECONDS)
    }

    private fun tick() {
        ensureService()
        if (enabled || learning) registerSdk()
        publish()
    }

    private fun prefs() = app.getSharedPreferences("keys", Context.MODE_PRIVATE)

    private fun load() {
        val p = prefs()
        enabled = p.getBoolean("enabled", false)
        bindings = try {
            val o = JSONObject(p.getString("bindings", "{}") ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val b = o.getJSONObject(k)
                Binding(b.optString(SHORT).ifBlank { null }, b.optString(LONG).ifBlank { null }, b.optString(DOUBLE).ifBlank { null })
            }.filterValues { !it.isEmpty() }
        } catch (e: Throwable) { emptyMap() }
        try {
            val o = JSONObject(p.getString("learned", "{}") ?: "{}")
            synchronized(learned) { o.keys().forEach { k -> learned[k] = jsonToMap(o.getJSONObject(k)) } }
        } catch (e: Throwable) {}
    }

    private fun save() {
        val b = JSONObject(); bindings.forEach { (k, v) -> b.put(k, JSONObject(v.toMap())) }
        val l = JSONObject(); synchronized(learned) { learned.forEach { (k, v) -> l.put(k, JSONObject(v)) } }
        prefs().edit().putBoolean("enabled", enabled).putString("bindings", b.toString()).putString("learned", l.toString()).apply()
    }

    private fun jsonToMap(o: JSONObject): MutableMap<String, Any?> = o.keys().asSequence().associateWith { o.opt(it) }.toMutableMap()

    fun setEnabled(on: Boolean) { enabled = on; save(); exec.execute { tick() } }

    fun setBinding(key: String, press: String, action: String?) {
        require(press in setOf(SHORT, LONG, DOUBLE)) { "Tipo de pulsación desconocido." }
        val a = action?.takeIf { it.isNotBlank() && it != "none" }
        if (a != null) require(a in ACTIONS || a.startsWith("app:")) { "Acción desconocida." }
        code(key)?.let { require(it !in BLOCKED) { "Ese botón no se puede cambiar (lo necesita el coche)." } }
        val nb = (bindings[key] ?: Binding(null, null, null)).with(press, a)
        bindings = if (nb.isEmpty()) bindings - key else bindings + (key to nb)
        // Asignar el primer botón enciende la función: es lo que espera quien lo configura.
        if (a != null && !enabled) enabled = true
        save(); exec.execute { tick() }
    }

    fun clearBindings() { bindings = emptyMap(); save(); exec.execute { tick() } }

    fun learn(on: Boolean, minutes: Int = LEARN_MIN) {
        learnUntil = if (on) System.currentTimeMillis() + minutes.coerceIn(1, 30) * 60_000L else 0L
        exec.execute { tick() }
        if (on) startGetevent()
        if (on) exec.schedule({ if (!learning) tick() }, minutes.coerceIn(1, 30) * 60L + 2, TimeUnit.SECONDS)
    }

    private fun code(key: String) = key.removePrefix("k").takeIf { key.startsWith("k") }?.toIntOrNull()

    // ═════════════════════════ servicio de accesibilidad ═════════════════════════
    internal fun attach(s: WheelKeyService) { service = s; Log.i(TAG, "volante: servicio de accesibilidad conectado"); exec.execute { publish() } }
    internal fun detach(s: WheelKeyService) { if (service === s) service = null; exec.execute { publish() } }

    /**
     * Pone (o quita) nuestro servicio en la lista de accesibilidad de Android sin tocar los demás (p. ej. la voz de BYD).
     * Al forzar el cierre de la app (BYD lo hace con todas al quitar el contacto) Android la saca de la lista y deja la
     * accesibilidad en 0: aquí se vuelve a poner, y se repasa a los 2 s porque el sistema a veces lo pisa al reaccionar.
     */
    private fun ensureService(again: Boolean = true) {
        val want = enabled || learning
        val comp = ComponentName(app, WheelKeyService::class.java)
        val cr = app.contentResolver
        val cur = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').filter { it.isNotBlank() }
        val has = cur.any { ComponentName.unflattenFromString(it) == comp }
        val on = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val others = cur.filter { ComponentName.unflattenFromString(it) != comp }
        if (want && (!has || !on)) {
            Log.i(TAG, "volante: activo el servicio de accesibilidad")
            writeSecure((others + comp.flattenToString()).joinToString(":"), true)
            if (again) exec.schedule({ ensureService(again = false) }, 2, TimeUnit.SECONDS)
        } else if (want && service == null && again && System.currentTimeMillis() - startedAt > 10_000 && System.currentTimeMillis() - lastRebind > 60_000) {
            // Puesto pero sin enlazar: desde Android 11 el sistema lo da por "colgado" tras forzar el cierre y no lo vuelve
            // a enlazar hasta que se quita de la lista. Se quita y, al momento, se vuelve a poner.
            lastRebind = System.currentTimeMillis()
            Log.i(TAG, "volante: el servicio no se enlaza; lo quito y lo vuelvo a poner")
            writeSecure(others.joinToString(":"), null)
            exec.schedule({ writeSecure((others + comp.flattenToString()).joinToString(":"), true) }, 1500, TimeUnit.MILLISECONDS)
        } else if (!want && has) {
            writeSecure(others.joinToString(":"), null)
        }
    }
    private var lastRebind = 0L
    private var startedAt = 0L

    /** Con WRITE_SECURE_SETTINGS (lo da KeepAlive por ADB) lo escribe la app; si no, por el ADB del coche. */
    private fun writeSecure(list: String, enable: Boolean?) {
        try {
            if (enable == true) Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
            return
        } catch (e: SecurityException) { Log.w(TAG, "volante: sin WRITE_SECURE_SETTINGS, pruebo por ADB") }
        shell { sh ->
            if (enable == true) sh.shell("settings put secure accessibility_enabled 1")
            sh.shell("settings put secure enabled_accessibility_services '${list.replace("'", "")}'")
        }
    }

    private fun shell(f: (CarShell) -> Unit) = shellExec.execute {
        val sh = CarShell(app.filesDir)
        try {
            if (sh.connect(BuildConfigCompat.adbHost(app)) && sh.works()) { adbState = "ok"; f(sh) } else adbState = "sin ADB"
        } catch (e: Throwable) { Log.e(TAG, "volante: ADB", e) } finally { sh.close() }
    }

    // ═════════════════════════ pulsaciones (hilo principal) ═════════════════════════
    private class KeyState {
        var downAt = 0L; var consumed = false; var longFired = false
        var longTask: Runnable? = null; var pendingTap: Runnable? = null
    }
    private val states = HashMap<String, KeyState>()
    private val downAt = HashMap<Int, Long>()
    /** Lo que reinyectamos nosotros (botón sin acción para esa pulsación) tiene que pasar sin volver a entrar aquí. */
    private val skip = HashMap<Int, Pair<Int, Long>>()

    /** Llamado por [WheelKeyService.onKeyEvent]. true = el botón se lo queda BYDLink. */
    internal fun onKey(ctx: Context, e: KeyEvent): Boolean {
        val code = e.keyCode
        val id = "k$code"
        observe(e)
        // Lo reinyectado por `input` viene del teclado virtual (-1): una pulsación real nunca se salta.
        if (e.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) skip[code]?.let { (n, until) ->
            if (SystemClock.uptimeMillis() < until && n > 0) { if (n > 1) skip[code] = (n - 1) to until else skip.remove(code); return false }
            skip.remove(code)
        }
        if (!enabled || code in BLOCKED) return false
        val b = bindings[id]?.takeIf { !it.isEmpty() } ?: return false
        val st = states.getOrPut(id) { KeyState() }
        return when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return st.consumed           // las repeticiones van con su pulsación
                st.consumed = true; st.downAt = e.eventTime; st.longFired = false
                st.longTask?.let { main.removeCallbacks(it) }
                st.longTask = b.long?.let { a -> Runnable { st.longFired = true; st.longTask = null; fire(ctx, id, LONG, a) }.also { main.postDelayed(it, LONG_MS) } }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (!st.consumed) return false
                st.consumed = false
                st.longTask?.let { main.removeCallbacks(it) }; st.longTask = null
                if (st.longFired) return true
                val dbl = b.double
                if (dbl != null) {
                    val p = st.pendingTap
                    if (p != null) { main.removeCallbacks(p); st.pendingTap = null; fire(ctx, id, DOUBLE, dbl) }
                    else st.pendingTap = Runnable { st.pendingTap = null; tap(ctx, id, code, b) }.also { main.postDelayed(it, DOUBLE_MS) }
                } else tap(ctx, id, code, b)
                true
            }
            else -> st.consumed
        }
    }

    private fun tap(ctx: Context, id: String, code: Int, b: Binding) {
        val a = b.short
        if (a != null) fire(ctx, id, SHORT, a) else passThrough(ctx, code)
    }

    /** El botón hace lo de siempre (esta pulsación no tiene acción de BYDLink). */
    private fun passThrough(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            KeyEvent.KEYCODE_VOLUME_DOWN -> am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            KeyEvent.KEYCODE_VOLUME_MUTE -> am.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            in MEDIA_KEYS -> media(am, code)
            else -> {
                skip[code] = 2 to SystemClock.uptimeMillis() + 4000
                shell { it.shell("input keyevent $code") }
            }
        }
    }

    private fun media(am: AudioManager, code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
    }

    /** Lo que llega del SDK: un toque por cada valor distinto de 0 (no se sabe cuándo se suelta, así que no hay "larga"). */
    internal fun onSdk(src: String, fid: Int, value: Int) {
        main.post {
            val id = (if (src == "sdk-voz") "voz" else "sdk") + value
            val name = if (src == "sdk-voz") "Botón de voz ($value)" else if (fid == FID_CUSTOM_PLAY) "Botón personalizable ($value)" else "Botón del coche $value"
            record(Ev(System.currentTimeMillis(), src, id, name, if (value == 0) "suelto" else "pulsado · ${"0x%08x".format(fid)}"),
                bindable = value != 0, code = value, extra = mapOf("fid" to fid))
            if (value == 0 || !enabled) return@post
            val b = bindings[id]?.takeIf { !it.isEmpty() } ?: return@post
            val st = states.getOrPut(id) { KeyState() }
            val dbl = b.double
            if (dbl != null) {
                val p = st.pendingTap
                if (p != null) { main.removeCallbacks(p); st.pendingTap = null; fire(app, id, DOUBLE, dbl) }
                else st.pendingTap = Runnable { st.pendingTap = null; b.short?.let { fire(app, id, SHORT, it) } }.also { main.postDelayed(it, DOUBLE_MS) }
            } else b.short?.let { fire(app, id, SHORT, it) }
        }
    }

    // ═════════════════════════ aprender ═════════════════════════
    private fun observe(e: KeyEvent) {
        val code = e.keyCode
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) downAt[code] = e.eventTime
            KeyEvent.ACTION_UP -> {
                val held = downAt.remove(code)?.let { e.eventTime - it }
                val dev = e.device?.name ?: "dispositivo ${e.deviceId}"
                record(Ev(System.currentTimeMillis(), "android", "k$code", keyName(code),
                    (if (held != null) "${held} ms" else "suelto") + " · scan ${e.scanCode} · $dev"),
                    bindable = code !in BLOCKED, code = code, extra = mapOf("scan" to e.scanCode, "device" to dev, "held" to held))
            }
        }
    }

    private fun record(ev: Ev, bindable: Boolean, code: Int?, extra: Map<String, Any?> = emptyMap()) {
        synchronized(log) { log.addFirst(ev); while (log.size > MAX_LOG) log.removeLast() }
        if (bindable) synchronized(learned) {
            val m = learned.getOrPut(ev.id) { mutableMapOf("id" to ev.id, "src" to ev.src, "name" to ev.name, "code" to code, "first" to ev.ts, "count" to 0) }
            m["count"] = ((m["count"] as? Number)?.toInt() ?: 0) + 1
            m["last"] = ev.ts
            extra.forEach { (k, v) -> if (v != null) m[k] = v }
            if (learned.size > MAX_LEARNED) learned.keys.firstOrNull { it !in bindings }?.let { learned.remove(it) }
        }
        val cb = onEvent
        if (cb != null) main.post { cb(ev) }
        publishSoon()
    }

    /** Lo que manda el hardware (como `getevent`), por el ADB del coche, a tandas de 12 s mientras se aprende. */
    private fun startGetevent() = shellExec.execute {
        val sh = CarShell(app.filesDir)
        try {
            if (!sh.connect(BuildConfigCompat.adbHost(app)) || !sh.works()) { adbState = "sin ADB"; return@execute }
            adbState = "ok"
            devices = (sh.shell("cat /proc/bus/input/devices") ?: "").lines()
                .filter { it.startsWith("N: Name=") }.map { it.removePrefix("N: Name=").trim('"') }
            val re = Regex("""^(/dev/input/event\d+):\s+EV_KEY\s+(\S+)\s+(DOWN|UP)""")
            while (learning) {
                val out = sh.shell("timeout 12 getevent -lq 2>/dev/null; true") ?: break
                out.lines().forEach { line ->
                    val m = re.find(line.trim()) ?: return@forEach
                    val (devPath, key, what) = m.destructured
                    if (what == "DOWN") record(Ev(System.currentTimeMillis(), "getevent", "ev:$key", key, "bajo nivel · $devPath"),
                        bindable = false, code = null)
                }
            }
        } catch (e: Throwable) { Log.e(TAG, "volante: getevent", e) } finally { sh.close() }
    }

    private fun registerSdk() {
        if (sdkState == "ok" || !BuildConfigCompat.USE_BYD_SDK) return
        sdkState = try { SdkKeyListeners.register(app) { src, fid, v -> onSdk(src, fid, v) } } catch (e: Throwable) { "error: ${e.javaClass.simpleName}" }
        Log.i(TAG, "volante: SDK → $sdkState")
    }

    // ═════════════════════════ acciones ═════════════════════════
    private fun fire(ctx: Context, id: String, press: String, action: String) {
        Log.i(TAG, "volante: $id $press → $action")
        record(Ev(System.currentTimeMillis(), "accion", id, label(action), when (press) { LONG -> "larga"; DOUBLE -> "doble"; else -> "corta" }), bindable = false, code = null)
        run(ctx, action)
    }

    fun label(action: String): String = ACTIONS[action] ?: if (action.startsWith("app:")) "Abrir ${appName(action.removePrefix("app:"))}" else action

    /** Ejecuta una acción (también la usa "Probar" desde el lanzador o el móvil). [ctx] = el servicio si lo hay: Android 10
     *  deja abrir pantallas desde segundo plano a una app con un servicio de accesibilidad activo. */
    fun run(ctx: Context = service ?: app, action: String) {
        toast("Volante: ${label(action)}")
        when {
            action.startsWith("page:") -> try {
                ctx.startActivity(Intent(ctx, com.bydlink.carapp.ui.HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("tab", action.removePrefix("page:")))
            } catch (e: Throwable) { toast("No se pudo abrir BYDLink") }
            action.startsWith("app:") -> try {
                val i = ctx.packageManager.getLaunchIntentForPackage(action.removePrefix("app:")) ?: return toast("Esa app ya no está")
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Throwable) { toast("No se pudo abrir la app") }
            action.startsWith("media:") -> media(ctx.getSystemService(AudioManager::class.java), when (action) {
                "media:next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "media:prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE })
            action == "photo" -> camera("takePhoto", emptyMap()) { "Foto hecha: la ves en el móvil" }
            action == "clip" -> exec.execute {
                // El último tramo de la dashcam ya cerrado pasa a "Grabaciones" (no se borra) y se graba 1 minuto más.
                val last = try {
                    VideoStore.list(app, 20).firstOrNull { it.kind == VideoStore.DASHCAM && System.currentTimeMillis() - it.file.lastModified() > 15_000 }
                        ?.let { VideoStore.keep(app, it.id) }
                } catch (e: Throwable) { null }
                camera("recordVideo", mapOf("seconds" to 60)) { if (last != null) "Guardado el último tramo y grabando 1 minuto" else "Grabando 1 minuto" }
            }
            action == "sentry" -> {
                val r = StateHub.run ?: return toast("BYDLink aún está arrancando")
                r("toggleSentry", emptyMap()) { ok, d -> toast(if (!ok) (d["error"] as? String ?: "No se pudo") else if (d["on"] == true) "Centinela activado" else "Centinela apagado") }
            }
            action == "climate" -> {
                val cmd = StateHub.commander ?: return toast("BYDLink aún está arrancando")
                exec.execute {
                    val on = StateHub.state?.climate?.on != true
                    val msg = try { cmd("setClimate", mapOf("on" to on)); if (on) "Aire encendido" else "Aire apagado" } catch (e: Throwable) { e.message ?: "No se pudo" }
                    toast(msg)
                }
            }
            else -> toast("Acción desconocida")
        }
    }

    private fun camera(action: String, p: Map<String, Any?>, ok: () -> String) {
        val cam = StateHub.camera ?: return toast("La cámara aún no está lista")
        cam.dispatch(action, p) { good, d -> toast(if (good) ok() else (d["error"] as? String ?: "No se pudo")) }
    }

    private fun toast(m: String) { main.post { try { Toast.makeText(app, m, Toast.LENGTH_SHORT).show() } catch (e: Throwable) {} } }

    private fun appName(pkg: String) = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Throwable) { pkg }

    /** Apps que se pueden abrir (para elegir en el móvil). */
    fun apps(): List<Map<String, String>> = try {
        val pm = app.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { mapOf("pkg" to it.activityInfo.packageName, "label" to it.loadLabel(pm).toString()) }
            .filter { it["pkg"] != app.packageName }.distinctBy { it["pkg"] }.sortedBy { it["label"]?.lowercase() }.take(80)
    } catch (e: Throwable) { emptyList() }

    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> "Subir volumen"; KeyEvent.KEYCODE_VOLUME_DOWN -> "Bajar volumen"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "Silencio"; KeyEvent.KEYCODE_MUTE -> "Silenciar micro"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "Siguiente"; KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Anterior"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play / pausa"; KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"; KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pausa"
        KeyEvent.KEYCODE_MEDIA_STOP -> "Parar"; KeyEvent.KEYCODE_HEADSETHOOK -> "Botón de llamada"
        KeyEvent.KEYCODE_CALL -> "Llamar"; KeyEvent.KEYCODE_ENDCALL -> "Colgar"
        KeyEvent.KEYCODE_VOICE_ASSIST -> "Voz"; KeyEvent.KEYCODE_ASSIST -> "Asistente"; KeyEvent.KEYCODE_SEARCH -> "Buscar"
        KeyEvent.KEYCODE_MENU -> "Menú"; KeyEvent.KEYCODE_HOME -> "Inicio (casita)"; KeyEvent.KEYCODE_BACK -> "Atrás"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Avanzar"; KeyEvent.KEYCODE_MEDIA_REWIND -> "Retroceder"
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> "Pista de audio"; KeyEvent.KEYCODE_DPAD_UP -> "Arriba"; KeyEvent.KEYCODE_DPAD_DOWN -> "Abajo"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Izquierda"; KeyEvent.KEYCODE_DPAD_RIGHT -> "Derecha"; KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
        KeyEvent.KEYCODE_ENTER -> "Intro"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").let { if (it.all(Char::isDigit)) "Botón $it" else "Botón ${it.lowercase().replace('_', ' ')}" }
    }

    // ═════════════════════════ nube ═════════════════════════
    @Volatile private var publishPending = false
    private fun publishSoon() {
        if (publishPending) return
        publishPending = true
        exec.schedule({ publishPending = false; save(); publish() }, 2, TimeUnit.SECONDS)
    }

    fun status(): Map<String, Any?> = mapOf(
        "enabled" to enabled, "service" to serviceOn, "sdk" to sdkState, "learning" to learning,
        "learnUntil" to learnUntil.takeIf { learning }, "adb" to adbState.ifBlank { null }, "devices" to devices.takeIf { it.isNotEmpty() })

    fun config(): Map<String, Any?> = mapOf("enabled" to enabled, "bindings" to bindings.mapValues { it.value.toMap() })
    fun learnedList(): List<Map<String, Any?>> = synchronized(learned) { learned.values.map { HashMap(it) } }
    fun logList(): List<Map<String, Any?>> = synchronized(log) { log.map { it.toMap() } }

    private var appsSent = false
    private var lastSent = ""
    private var lastSentAt = 0L

    /** A la nube solo si algo ha cambiado (o cada 30 min, para que el móvil vea que sigue vivo). */
    private fun publish() {
        if (!::app.isInitialized) return
        try {
            val m = HashMap<String, Any?>()
            m["config"] = config()
            m["learned"] = synchronized(learned) { learned.mapKeys { it.key.replace(Regex("[.#$\\[\\]/]"), "_") }.mapValues { HashMap(it.value) } }
            m["log"] = logList()
            val st = status()
            val sig = (m.toString() + st).hashCode().toString()
            val now = System.currentTimeMillis()
            if (sig == lastSent && appsSent && now - lastSentAt < 30 * 60_000L) return
            m["status"] = st + ("ts" to ServerValue.TIMESTAMP)
            if (!appsSent) { m["apps"] = apps(); appsSent = true }
            com.bydlink.carapp.App.db.getReference("cars/${BuildConfigCompat.carId(app)}/keys").updateChildren(m)
            lastSent = sig; lastSentAt = now
        } catch (e: Throwable) { Log.w(TAG, "volante: no se pudo publicar (${e.message})") }
    }

    // ═════════════════════════ comandos del móvil ═════════════════════════
    val COMMANDS = setOf("setKeyConfig", "keyLearn", "getKeyStatus", "keyRunAction")

    fun dispatch(action: String, p: Map<String, Any?>, respond: (Boolean, Map<String, Any?>) -> Unit): Boolean {
        if (action !in COMMANDS) return false
        if (!started) { respond(false, mapOf("error" to "BYDLink aún está arrancando")); return true }
        try {
            when (action) {
                "setKeyConfig" -> {
                    (p["enabled"] as? Boolean)?.let { setEnabled(it) }
                    if (p["clear"] == true) clearBindings()
                    val key = p["key"] as? String
                    if (key != null) setBinding(key, p["press"] as? String ?: SHORT, p["action"] as? String)
                }
                "keyLearn" -> learn(p["on"] as? Boolean ?: true, (p["minutes"] as? Number)?.toInt() ?: LEARN_MIN)
                "keyRunAction" -> run(action = p["action"] as? String ?: throw IllegalArgumentException("Falta la acción."))
            }
            respond(true, mapOf("config" to config(), "status" to status()))
        } catch (e: Throwable) {
            respond(false, mapOf("error" to (e.message ?: "No se pudo")))
        }
        return true
    }
}
