package com.bydlink.carapp.car

import android.content.Context

/**
 * Acceso por REFLEXIÓN al SDK `android.hardware.bydauto.*`.
 *
 * Esto es lo que hace que la app sea model-agnostic (como OpenBYD/SentryEV/BYDMate): no
 * dependemos de firmas exactas en compilación; buscamos las clases y métodos en tiempo de
 * ejecución y envolvemos TODO en try/catch, probando varios nombres de método cuando varían
 * entre modelos. Si un método no existe en este coche, se devuelve null y se usa un valor
 * por defecto.
 */
object BydReflect {

    /** Obtiene el singleton de un dispositivo BYD por su nombre de clase. */
    fun device(ctx: Context, className: String): Any? {
        return try {
            val cls = Class.forName(className)
            try { cls.getMethod("getInstance", Context::class.java).invoke(null, ctx) }
            catch (e: Throwable) { cls.getMethod("getInstance").invoke(null) }
        } catch (e: Throwable) { null }
    }

    fun callInt(obj: Any?, vararg methods: String): Int? = (callAny(obj, methods.toList()) as? Number)?.toInt()
    fun callIntArg(obj: Any?, method: String, vararg args: Any?): Int? = (call(obj, method, *args) as? Number)?.toInt()
    fun callFloat(obj: Any?, vararg methods: String): Float? = (callAny(obj, methods.toList()) as? Number)?.toFloat()
    fun callDouble(obj: Any?, vararg methods: String): Double? = (callAny(obj, methods.toList()) as? Number)?.toDouble()
    fun callBool(obj: Any?, vararg methods: String): Boolean? = callAny(obj, methods.toList()) as? Boolean
    fun callString(obj: Any?, vararg methods: String): String? = callAny(obj, methods.toList()) as? String

    /** Llama a un método por nombre y nº de argumentos (ignora tipos exactos). Primer método
     *  con ese nombre y aridad; para métodos sobrecargados por tipos usa [callTyped]. */
    fun call(obj: Any?, method: String, vararg args: Any?): Any? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.size == args.size }
                ?: return null
            m.isAccessible = true
            if (args.isEmpty()) m.invoke(obj) else m.invoke(obj, *args)
        } catch (e: Throwable) { null }
    }

    /** Primer método (sin args) que exista de una lista de candidatos. */
    fun callAny(obj: Any?, methods: List<String>): Any? {
        for (name in methods) {
            val r = call(obj, name)
            if (r != null) return r
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    //  Acceso genérico por FEATURE ID (AbsBYDAutoDevice.get(int[],Class) / set(int[],EventValue))
    //  Es la vía que usan BYDMate/DiPlus para leer/escribir señales sin getter con nombre.
    // ---------------------------------------------------------------------------------------

    /** Lee un feature id como entero vía `get(int[]{fid}, Integer.TYPE)` → BYDAutoEventValue.intValue.
     *  Devuelve null si el método no existe o el valor es centinela N/A. */
    fun featureInt(obj: Any?, fid: Int): Int? =
        san(featureValue(obj, fid, java.lang.Integer.TYPE, "intValue") as? Number?)?.let { it.toInt() }

    /** Lee un feature id como double vía `get(int[]{fid}, Double.TYPE)` → BYDAutoEventValue.doubleValue. */
    fun featureDouble(obj: Any?, fid: Int): Double? =
        sanD((featureValue(obj, fid, java.lang.Double.TYPE, "doubleValue") as? Number?)?.toDouble())

    private fun featureValue(obj: Any?, fid: Int, cls: Class<*>, field: String): Any? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 2 && it.parameterTypes[0] == IntArray::class.java
            }
            if (m != null) {
                m.isAccessible = true
                val ev = m.invoke(obj, intArrayOf(fid), cls) ?: return null
                return ev.javaClass.getField(field).get(ev)
            }
            // Fallback: get(int deviceType, int fid)
            val dev = deviceType(obj) ?: return null
            val m2 = obj.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return null
            m2.isAccessible = true
            m2.invoke(obj, dev, fid)
        } catch (e: Throwable) { null }
    }

    /** Escribe un feature id vía `set(int[]{fid}, BYDAutoEventValue)` (fallback `set(dev,fid,val)`).
     *  Devuelve el código del SDK (0 = éxito), o null si no se pudo invocar. OJO: el SDK devuelve
     *  0 aun cuando ignora la orden → hay que leer de vuelta el estado. */
    fun setFeature(obj: Any?, fid: Int, value: Int): Int? {
        if (obj == null) return null
        return try {
            val evCls = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
            val ev = evCls.getConstructor().newInstance()
            evCls.getField("intValue").setInt(ev, value)
            val m = obj.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterTypes.size == 2 && it.parameterTypes[0] == IntArray::class.java
            }
            if (m != null) { m.isAccessible = true; return (m.invoke(obj, intArrayOf(fid), ev) as? Number)?.toInt() ?: 0 }
            val dev = deviceType(obj) ?: return null
            val m2 = obj.javaClass.methods.firstOrNull { it.name == "set" && it.parameterTypes.size == 3 } ?: return null
            m2.isAccessible = true
            (m2.invoke(obj, dev, fid, value) as? Number)?.toInt() ?: 0
        } catch (e: Throwable) { null }
    }

    private fun deviceType(obj: Any?): Int? =
        (call(obj, "getDevicetype") as? Number)?.toInt() ?: (call(obj, "getType") as? Number)?.toInt()

    /** Resuelve un feature id por su símbolo en `BYDAutoFeatureIds` (y clases anidadas) del
     *  propio coche; si no existe, usa [fallback]. Hace la app model-agnostic por símbolo. */
    fun fid(symbolDotField: String, fallback: Int): Int {
        return try {
            val cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
            if (symbolDotField.contains(".")) {
                val parts = symbolDotField.split(".")
                for (inner in cls.declaredClasses) if (inner.simpleName == parts[0]) return inner.getField(parts[1]).getInt(null)
                fallback
            } else cls.getField(symbolDotField).getInt(null)
        } catch (e: Throwable) { fallback }
    }

    // --- Saneado de centinelas (N/A) ---------------------------------------------------------
    /** Centinelas enteros de "no disponible": 65535, 1048575 (20 bits), -10011/-10013 (dir/tx),
     *  y los COMMAND_RESULT_* grandes. */
    fun san(v: Number?): Int? {
        if (v == null) return null
        val i = v.toInt()
        return if (i == 65535 || i == 1048575 || i == -10011 || i == -10013 || i <= -2147482640) null else i
    }
    fun san(v: Int?): Int? = san(v as Number?)

    /** Centinelas en double: -1.0 (sin init), 65535.0, el "SDK ausente" y valores enormes. */
    fun sanD(v: Double?): Double? {
        if (v == null || v.isNaN() || v.isInfinite()) return null
        if (v == -1.0 || v == 65535.0 || v == 104857.5) return null
        if (v <= -2.147482e9 || v >= 1.0e9) return null
        return v
    }

    /** ¿Está presente el SDK BYD en este coche? */
    fun sdkPresent(): Boolean = try {
        Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"); true
    } catch (e: Throwable) { false }
}
