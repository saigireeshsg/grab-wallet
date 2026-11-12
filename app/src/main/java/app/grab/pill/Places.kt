package app.grab.pill

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject

data class Place(val id: String, val name: String, val type: String, val lat: Double, val lng: Double, val r: Float = 220f) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("lat", lat).put("lng", lng).put("r", r.toDouble())
    companion object {
        fun from(o: JSONObject) = Place(o.optString("id"), o.optString("name"), o.optString("type"), o.optDouble("lat"), o.optDouble("lng"), o.optDouble("r", 220.0).toFloat())
    }
}

/**
 * Places Grab knows about (metro stations, home, work), each a geofence.
 * Grab learns metro stations by itself: every time you open a metro ticket, the spot you're
 * standing on becomes a station (if it isn't one already). Next time you walk in, the QR pops up.
 * Coordinates never leave the phone. Uses Google Play's low-power geofencing, not constant GPS.
 */
object Places {
    const val METRO = "METRO"; const val HOME = "HOME"; const val WORK = "WORK"
    val LABEL = mapOf(METRO to "Metro station", HOME to "Home", WORK to "Work")

    fun all(c: Context): List<Place> {
        val a = try { JSONArray(Store.get(c, "places") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { Place.from(a.getJSONObject(it)) }
    }

    private fun save(c: Context, list: List<Place>) {
        val a = JSONArray(); list.take(90).forEach { a.put(it.json()) } // Play services allows 100 geofences per app
        Store.put(c, "places", a.toString())
        register(c)
    }

    fun add(c: Context, p: Place) = save(c, all(c).filter { it.id != p.id } + p)
    fun delete(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7, Intent(c, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // geofencing fills in the event, so it must be mutable
    )

    /** (Re)register every place as a geofence. Called on change, on app start and after reboot. */
    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        if (!hasPermission(c) || !hasBackground(c)) return
        val client = LocationServices.getGeofencingClient(c)
        val list = all(c)
        client.removeGeofences(pending(c)).addOnCompleteListener {
            if (list.isEmpty()) return@addOnCompleteListener
            val fences = list.map {
                Geofence.Builder().setRequestId(it.id).setCircularRegion(it.lat, it.lng, it.r)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(30_000)
                    .build()
            }
            val req = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofences(fences).build()
            try { client.addGeofences(req, pending(c)) } catch (e: SecurityException) { }
        }
    }

    @SuppressLint("MissingPermission")
    fun here(c: Context, done: (Location?) -> Unit) {
        if (!hasPermission(c)) { done(null); return }
        try {
            LocationServices.getFusedLocationProviderClient(c)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnCompleteListener { t -> done(if (t.isSuccessful) t.result else null) }
        } catch (e: SecurityException) { done(null) }
    }

    fun near(c: Context, lat: Double, lng: Double, type: String? = null, within: Float = 300f): Place? =
        all(c).filter { type == null || it.type == type }.minByOrNull { dist(lat, lng, it) }?.takeIf { dist(lat, lng, it) < within }

    private fun dist(lat: Double, lng: Double, p: Place): Float {
        val out = FloatArray(1); Location.distanceBetween(lat, lng, p.lat, p.lng, out); return out[0]
    }

    /** Save the spot you're standing on as a place of this type (no-op if one is already within 300 m). */
    fun learnHere(c: Context, type: String, name: String? = null, done: (Place?) -> Unit = {}) {
        here(c) { loc ->
            if (loc == null || loc.accuracy > 150) { done(null); return@here }
            val existing = near(c, loc.latitude, loc.longitude, type)
            if (existing != null) { done(existing); return@here }
            // checking a ticket at home or at the office doesn't make it a station
            if (type == METRO && all(c).any { it.type != METRO && near(c, loc.latitude, loc.longitude, it.type, 400f) != null }) { done(null); return@here }
            val n = name ?: "${LABEL[type] ?: "Place"} ${all(c).count { it.type == type } + 1}"
            val p = Place("p" + System.currentTimeMillis().toString(36), n, type, loc.latitude, loc.longitude)
            add(c, p); Ctx.enter(c, p); done(p)
            android.widget.Toast.makeText(c, "Saved this spot as ${p.name} · your ticket will pop up here next time", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** Geofence fired: remember where we are, and let the assistant react (metro → QR pops up). */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ev = GeofencingEvent.fromIntent(i) ?: return
        if (ev.hasError()) return
        val ids = ev.triggeringGeofences?.map { it.requestId } ?: return
        val places = Places.all(c).filter { it.id in ids }
        Habits.init(c)
        when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> places.firstOrNull()?.let { Ctx.enter(c, it); Assistant.onPlace(c, it) }
            Geofence.GEOFENCE_TRANSITION_EXIT -> places.forEach { Ctx.exit(c, it.id) }
        }
        CardBus.emit()
    }
}
