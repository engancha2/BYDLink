package com.bydlink.carapp.net

import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.model.Command
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener

/**
 * Puente con Firebase Realtime Database para la app del coche:
 *  - publica el estado en /cars/{carId}/status
 *  - escucha comandos en /cars/{carId}/commands y responde en /responses
 */
class CarClient(private val carId: String, private val appVersion: String) {

    private val db = com.bydlink.carapp.App.db
    private val root: DatabaseReference = db.getReference("cars").child(carId)

    fun connect(onReady: () -> Unit, onError: (Exception) -> Unit) {
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                // updateChildren: no borra meta/update, meta/power ni meta/acc (los publican otras partes).
                root.child("meta").updateChildren(
                    mapOf("name" to "Mi BYD", "model" to "BYD", "online" to true,
                          "lastSeen" to ServerValue.TIMESTAMP, "appVersion" to appVersion)
                )
                root.child("meta/online").onDisconnect().setValue(false)
                watchPresence()
                onReady()
            }
            .addOnFailureListener(onError)
    }

    /** Tras cada reconexión (cambio WiFi ↔ 4G al quitar/poner el contacto) volver a marcarse en línea. */
    private fun watchPresence() {
        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.getValue(Boolean::class.java) != true) return
                root.child("meta/online").onDisconnect().setValue(false)
                root.child("meta").updateChildren(mapOf("online" to true, "lastSeen" to ServerValue.TIMESTAMP))
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
        })
    }

    fun publishState(s: CarState) {
        root.child("status").setValue(s)
    }

    /** Con el SDK del coche dormido: "sigo aquí" (hora y posición) sin borrar el último estado conocido. */
    fun publishHeartbeat(loc: com.bydlink.carapp.model.Location) {
        val m = mutableMapOf<String, Any?>("ts" to System.currentTimeMillis(), "sdkAsleep" to true)
        if (loc.lat != 0.0 || loc.lng != 0.0) m["location"] = loc
        root.child("status").updateChildren(m)
        root.child("meta/lastSeen").setValue(ServerValue.TIMESTAMP)
    }

    fun publishConfig(config: Map<String, Any?>) = root.child("config").setValue(config)
    fun publishSeats(driver: Int, passenger: Int) =
        root.child("seats").setValue(mapOf("driverStage" to driver, "passengerStage" to passenger))

    /** Empuja un evento de centinela para que el móvil lo reciba (y FCM lo notifique). */
    fun pushSentryEvent(camera: String, videoId: String) {
        root.child("sentryEvents").push().setValue(
            mapOf("ts" to ServerValue.TIMESTAMP, "camera" to camera, "videoId" to videoId, "thumbUrl" to null)
        )
    }

    fun onCommand(handle: (Command) -> Unit) {
        root.child("commands").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                val action = snap.child("action").getValue(String::class.java) ?: return
                @Suppress("UNCHECKED_CAST")
                val payload = (snap.child("payload").value as? Map<String, Any?>) ?: emptyMap()
                handle(Command(snap.key ?: "", action, payload))
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
        })
    }

    fun respond(cmdId: String, ok: Boolean, data: Map<String, Any?> = emptyMap()) {
        root.child("responses").child(cmdId)
            .setValue(mapOf("ok" to ok, "data" to data, "ts" to ServerValue.TIMESTAMP))
        root.child("commands").child(cmdId).removeValue()
    }

    fun signalingRef(session: String): DatabaseReference = root.child("signaling").child(session)
}
