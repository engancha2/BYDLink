package com.bydlink.carapp.car

import android.content.Context

/**
 * Acceso por REFLEXIÓN al SDK `android.hardware.bydauto.*`.
 *
 * Esto es lo que hace que la app sea model-agnostic (como OpenBYD/SentryEV): no dependemos
 * de firmas exactas en compilación; buscamos las clases y métodos en tiempo de ejecución y
 * envolvemos TODO en try/catch, probando varios nombres de método cuando varían entre modelos.
 * Si un método no existe en este coche, se devuelve null y se usa un valor por defecto.
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
    fun callBool(obj: Any?, vararg methods: String): Boolean? = callAny(obj, methods.toList()) as? Boolean
    fun callString(obj: Any?, vararg methods: String): String? = callAny(obj, methods.toList()) as? String

    /** Llama a un método por nombre y nº de argumentos (ignora tipos exactos). */
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

    /** ¿Está presente el SDK BYD en este coche? */
    fun sdkPresent(): Boolean = try {
        Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"); true
    } catch (e: Throwable) { false }
}
