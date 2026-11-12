package app.grab.pill

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads the phone's calendar (already synced from Google Calendar). Nothing is copied out. */
object CalendarHelper {
    data class Event(val title: String, val start: Long, val link: String?, val timeLabel: String)

    private val LINK = Regex("""https://(meet\.google\.com/[a-z\-]+|[\w.]*zoom\.us/j/\S+|teams\.microsoft\.com/\S+)""")

    fun next(c: Context): Event? {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 10 * 60_000)
            ContentUris.appendId(it, now + 12 * 3_600_000)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        c.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(4) == 1) continue // skip all-day
                val title = cur.getString(0) ?: "Event"
                val begin = cur.getLong(1)
                val text = (cur.getString(2) ?: "") + " " + (cur.getString(3) ?: "")
                val link = LINK.find(text)?.value
                val label = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(begin))
                return Event(title, begin, link, label)
            }
        }
        return null
    }
}
