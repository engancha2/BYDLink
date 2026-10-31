package com.bydlink.carapp.net

import com.bydlink.carapp.model.CarState
import com.bydlink.carapp.model.Command
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener

/**
 * Puente con Firebase Realtime Database para la app del coche:
 *  - publica el estado en /cars/{carId}/status
 *  - escucha comandos en /cars/{carId}/commands y responde en /responses
 */
class CarClient(private val carId: String) {

    private val db = FirebaseDatabase.getInstance()
    private val root: DatabaseReference = db.getReference("cars").child(carId)

    fun connect(onReady: () -> Unit, onError: (Exception) -> Unit) {
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                root.child("meta").setValue(
                    mapOf("name" to "Mi BYD", "model" to "BYD", "online" to true,
                          "lastSeen" to ServerValue.TIMESTAMP, "appVersion" to "1.0")
                )
                root.child("meta/online").onDisconnect().setValue(false)
                onReady()
            }
            .addOnFailureListener(onError)
    }

    fun publishState(s: CarState) {
        root.child("status").setValue(s)
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
