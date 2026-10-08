package app.roomtape.scan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One scanned room. Corners are floor points (x, z) in metres in the AR world of [session]. */
data class Room(
    val name: String,
    val level: Int,
    val corners: List<FloatArray>,
    val height: Float,
    val floorY: Float,
    val session: String,
)

/** Size of a room seen square to its longest wall. */
data class RoomSize(val width: Float, val depth: Float, val area: Float)

object Geometry {
    fun area(c: List<FloatArray>): Float {
        var s = 0f
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            s += a[0] * b[1] - b[0] * a[1]
        }
        return abs(s) / 2f
    }

    fun size(c: List<FloatArray>): RoomSize {
        if (c.size < 2) return RoomSize(0f, 0f, 0f)
        var best = 0f
        var angle = 0.0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            val len = hypot((b[0] - a[0]).toDouble(), (b[1] - a[1]).toDouble()).toFloat()
            if (len > best) {
                best = len
                angle = atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())
            }
        }
        val ca = cos(-angle).toFloat()
        val sa = sin(-angle).toFloat()
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (p in c) {
            val x = p[0] * ca - p[1] * sa
            val z = p[0] * sa + p[1] * ca
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        return RoomSize(maxX - minX, maxZ - minZ, area(c))
    }

    fun dist(a: FloatArray, b: FloatArray): Float =
        hypot((b[0] - a[0]).toDouble(), (b[1] - a[1]).toDouble()).toFloat()
}

object ScanStore {
    val rooms = ArrayList<Room>()
    private const val FILE = "scan.json"

    val floorNames = listOf("Basement", "Ground floor", "First floor", "Second floor", "Third floor")
    fun floorName(level: Int): String = when (level) {
        -1 -> "Basement"
        0 -> "Ground floor"
        1 -> "First floor"
        2 -> "Second floor"
        3 -> "Third floor"
        else -> "Floor $level"
    }

    fun load(ctx: Context) {
        rooms.clear()
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        try {
            val arr = JSONObject(f.readText()).getJSONArray("rooms")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cs = o.getJSONArray("corners")
                val corners = ArrayList<FloatArray>()
                for (j in 0 until cs.length()) {
                    val p = cs.getJSONArray(j)
                    corners.add(floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
                }
                rooms.add(
                    Room(
                        o.getString("name"), o.getInt("level"), corners,
                        o.getDouble("height").toFloat(), o.getDouble("floorY").toFloat(),
                        o.getString("session")
                    )
                )
            }
        } catch (e: Exception) {
            rooms.clear()
        }
    }

    fun save(ctx: Context) {
        File(ctx.filesDir, FILE).writeText(exportJson())
    }

    fun add(ctx: Context, r: Room) {
        rooms.add(r)
        save(ctx)
    }

    fun clear(ctx: Context) {
        rooms.clear()
        save(ctx)
    }

    /** The file format the Roomtape web app imports. */
    fun exportJson(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val arr = JSONArray()
        for (r in rooms) {
            val cs = JSONArray()
            for (p in r.corners) cs.put(JSONArray().put(round(p[0])).put(round(p[1])))
            arr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("level", r.level)
                    .put("height", round(r.height))
                    .put("floorY", round(r.floorY))
                    .put("session", r.session)
                    .put("corners", cs)
            )
        }
        return JSONObject()
            .put("format", "roomtape-scan")
            .put("version", 1)
            .put("app", "Roomtape Scanner for Android")
            .put("createdAt", iso)
            .put("rooms", arr)
            .toString(2)
    }

    private fun round(v: Float): Double = Math.round(v * 1000.0) / 1000.0
}
