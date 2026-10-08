package app.roomtape.scan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.Locale
import java.util.UUID
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Walk through the home with one AR session: for each room, aim at every floor corner and tap,
 * then aim at the top of a wall for the ceiling height. Because the session keeps running while
 * you walk to the next room, every room lands in the same coordinate system, so their positions
 * relative to each other are measured too.
 */
class ScanActivity : Activity(), GLSurfaceView.Renderer {

    private enum class Mode { IDLE, CORNERS, HEIGHT }

    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var hint: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnMain: Button
    private lateinit var btnNext: Button

    private var session: Session? = null
    private var installRequested = false
    private var textureSet = false
    private val bg = BackgroundRenderer()
    private var viewW = 1
    private var viewH = 1

    private val lock = Any()
    @Volatile private var mode = Mode.IDLE
    private var roomName = ""
    private var roomLevel = 0
    private val corners = ArrayList<FloatArray>()   // x, z on the floor
    private var floorY = Float.NaN                  // locked once the first corner is placed

    @Volatile private var floorEstimate = Float.NaN
    @Volatile private var aim: FloatArray? = null   // world x, y, z under the cross
    @Volatile private var tracking = false

    private val sessionId = UUID.randomUUID().toString().substring(0, 8)
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanStore.load(this)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ScanActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = OverlayView(this)
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.argb(190, 23, 34, 58))
            setPadding(pad, pad + (24 * d).toInt(), pad, pad)
        }
        fun button(label: String) = Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
        }
        btnUndo = button("Undo")
        btnMain = button("Add corner").apply {
            setBackgroundColor(Color.rgb(242, 183, 5))
            setTextColor(Color.rgb(43, 34, 0))
        }
        btnNext = button("Room done")
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad / 2, pad, pad / 2, pad + (16 * d).toInt())
            setBackgroundColor(Color.argb(190, 23, 34, 58))
            addView(btnUndo); addView(btnMain); addView(btnNext)
        }
        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        btnMain.setOnClickListener { onMain() }
        btnUndo.setOnClickListener { onUndo() }
        btnNext.setOnClickListener { onNext() }

        askRoom()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (session == null) {
            try {
                val status = ArCoreApk.getInstance().requestInstall(this, !installRequested)
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                val s = Session(this)
                val cfg = Config(s)
                cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                cfg.focusMode = Config.FocusMode.AUTO
                cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) cfg.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(cfg)
                session = s
            } catch (e: UnavailableUserDeclinedInstallationException) {
                fail("Roomtape Scanner needs Google Play Services for AR to measure. Install it from the Play Store and try again.")
                return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                fail("This phone does not support Google's AR measuring (ARCore), so it cannot scan rooms.")
                return
            } catch (e: Exception) {
                fail("The camera could not start for measuring: ${e.message}")
                return
            }
        }
        try {
            session?.resume()
        } catch (e: Exception) {
            fail("The camera is in use by another app. Close it and try again.")
            return
        }
        glView.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            fail("Roomtape Scanner needs the camera to measure rooms.")
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    // ---------------------------------------------------------------- rooms

    private fun askRoom() {
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val names = listOf("Living room", "Kitchen", "Bedroom", "Bathroom", "Hall", "Toilet", "Office",
            "Dining room", "Laundry", "Garage", "Attic", "Kids room")
        val nameField = AutoCompleteTextView(this).apply {
            hint = "Room name, e.g. Living room"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            threshold = 1
            setAdapter(ArrayAdapter(this@ScanActivity, android.R.layout.simple_dropdown_item_1line, names))
        }
        val floorLabel = TextView(this).apply { text = "Floor"; setPadding(0, pad, 0, 0) }
        val floorSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@ScanActivity, android.R.layout.simple_spinner_dropdown_item, ScanStore.floorNames)
            val last = ScanStore.rooms.lastOrNull()?.level ?: 0
            setSelection((last + 1).coerceIn(0, ScanStore.floorNames.size - 1))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameField); addView(floorLabel); addView(floorSpin)
        }
        AlertDialog.Builder(this)
            .setTitle(if (ScanStore.rooms.isEmpty()) "Which room do you start with?" else "Which room is next?")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Start measuring") { _, _ ->
                synchronized(lock) {
                    roomName = nameField.text.toString().trim().ifEmpty { "Room ${ScanStore.rooms.size + 1}" }
                    roomLevel = floorSpin.selectedItemPosition - 1
                    corners.clear()
                    floorY = Float.NaN
                }
                mode = Mode.CORNERS
            }
            .setNegativeButton("Stop scanning") { _, _ -> finish() }
            .show()
    }

    private fun onMain() {
        when (mode) {
            Mode.CORNERS -> addCorner()
            Mode.HEIGHT -> setHeight()
            Mode.IDLE -> {}
        }
    }

    private fun addCorner() {
        val a = aim
        if (a == null) {
            toast("Point the cross at the floor first.")
            return
        }
        var closed = false
        var levelNote: String? = null
        synchronized(lock) {
            if (corners.isEmpty()) {
                floorY = a[1]
                // Guess the floor from the height difference with rooms already scanned in this walk.
                val ref = ScanStore.rooms.lastOrNull { it.session == sessionId }
                if (ref != null) {
                    val dy = floorY - ref.floorY
                    if (abs(dy) > 1.8f) {
                        val lv = ref.level + (dy / 2.9f).roundToInt()
                        if (lv != roomLevel) {
                            roomLevel = lv
                            levelNote = "Measured as ${ScanStore.floorName(lv)}."
                        }
                    }
                }
            }
            val p = floatArrayOf(a[0], a[2])
            if (corners.size >= 3 && Geometry.dist(p, corners[0]) < 0.25f) closed = true
            else corners.add(p)
        }
        levelNote?.let { toast(it) }
        if (closed) onNext()
    }

    private fun onUndo() {
        synchronized(lock) {
            when (mode) {
                Mode.CORNERS -> if (corners.isNotEmpty()) corners.removeAt(corners.size - 1)
                Mode.HEIGHT -> mode = Mode.CORNERS
                Mode.IDLE -> {}
            }
            if (corners.isEmpty()) floorY = Float.NaN
        }
    }

    private fun onNext() {
        when (mode) {
            Mode.CORNERS -> {
                if (synchronized(lock) { corners.size } < 3) {
                    toast("Add at least 3 corners.")
                    return
                }
                mode = Mode.HEIGHT
            }
            Mode.HEIGHT -> saveRoom(2.5f)   // "Skip" uses a standard ceiling height
            Mode.IDLE -> {}
        }
    }

    private fun setHeight() {
        val a = aim
        if (a == null) {
            toast("Point the cross at the top of a wall of this room.")
            return
        }
        val h = a[1] - synchronized(lock) { floorY }
        if (h < 1.8f || h > 6f) {
            toast(String.format(Locale.US, "That gives %.2f m. Aim at the line where the wall meets the ceiling.", h))
            return
        }
        saveRoom(h)
    }

    private fun saveRoom(h: Float) {
        val room = synchronized(lock) {
            Room(roomName, roomLevel, corners.map { it.copyOf() }, h, floorY, sessionId)
        }
        mode = Mode.IDLE
        ScanStore.add(this, room)
        val s = Geometry.size(room.corners)
        AlertDialog.Builder(this)
            .setTitle("${room.name} is saved")
            .setMessage(
                String.format(
                    Locale.US, "%.2f × %.2f m · %.1f m² · ceiling %.2f m · %s\n\nIs there another room to scan?",
                    s.width, s.depth, s.area, h, ScanStore.floorName(room.level)
                )
            )
            .setCancelable(false)
            .setPositiveButton("Yes, next room") { _, _ -> askRoom() }
            .setNegativeButton("No, I'm done") { _, _ -> finish() }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun refreshUi() {
        val n = synchronized(lock) { corners.size }
        val m = mode
        hint.text = when {
            m == Mode.IDLE -> "Roomtape Scanner"
            !tracking -> "Move the phone slowly and look around the room."
            m == Mode.CORNERS && floorEstimate.isNaN() && n == 0 ->
                "$roomName: point the camera at the floor and move it slowly until the cross turns yellow."
            m == Mode.CORNERS && n < 3 ->
                "$roomName: aim the cross at a corner of the floor and tap Add corner. Walk around the room corner by corner. ($n placed)"
            m == Mode.CORNERS ->
                "$roomName: keep adding corners, or tap Room done when every corner is in. ($n placed)"
            else ->
                "$roomName: aim the cross at the line where a wall meets the ceiling, then tap Set height. Skip uses 2.50 m."
        }
        btnMain.text = if (m == Mode.HEIGHT) "Set height" else "Add corner"
        btnNext.text = if (m == Mode.HEIGHT) "Skip" else "Room done"
        btnUndo.text = if (m == Mode.HEIGHT) "Back" else "Undo"
        btnMain.isEnabled = m != Mode.IDLE
        btnNext.isEnabled = m == Mode.HEIGHT || (m == Mode.CORNERS && n >= 3)
        btnUndo.isEnabled = m == Mode.HEIGHT || n > 0
    }

    // ---------------------------------------------------------------- rendering

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.create()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        session?.setDisplayGeometry(Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureSet) {
            s.setCameraTextureName(bg.textureId)
            s.setDisplayGeometry(Surface.ROTATION_0, viewW, viewH)
            textureSet = true
        }
        val frame = try { s.update() } catch (e: Exception) { return }
        bg.draw(frame)

        val cam = frame.camera
        tracking = cam.trackingState == TrackingState.TRACKING
        if (!tracking) {
            aim = null
            overlay.set(emptyList(), emptyList(), false)
            return
        }

        // The floor: the lowest large upward-facing plane well below the phone.
        val camY = cam.pose.ty()
        var best = Float.NaN
        for (p in s.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING) continue
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING || p.subsumedBy != null) continue
            if (p.extentX * p.extentZ < 0.2f) continue
            val y = p.centerPose.ty()
            if (camY - y < 0.6f) continue
            if (best.isNaN() || y < best) best = y
        }
        floorEstimate = best

        val curMode = mode
        val pts: List<FloatArray>
        val fy: Float
        synchronized(lock) {
            pts = corners.map { it.copyOf() }
            fy = if (!floorY.isNaN()) floorY else best
        }

        // Ray through the centre of the screen (the cross).
        val o = floatArrayOf(cam.pose.tx(), cam.pose.ty(), cam.pose.tz())
        val z = cam.pose.zAxis
        val dir = floatArrayOf(-z[0], -z[1], -z[2])

        aim = when {
            fy.isNaN() -> null
            curMode == Mode.HEIGHT -> wallHit(o, dir, pts, fy)
            curMode == Mode.CORNERS -> floorHit(o, dir, fy)
            else -> null
        }

        // Project everything to the screen for the overlay.
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        cam.getViewMatrix(view, 0)
        cam.getProjectionMatrix(proj, 0, 0.05f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        fun screen(x: Float, y: Float, zz: Float): PointF? {
            val r = FloatArray(4)
            Matrix.multiplyMV(r, 0, vp, 0, floatArrayOf(x, y, zz, 1f), 0)
            if (r[3] <= 0.01f) return null
            return PointF((r[0] / r[3] + 1f) / 2f * viewW, (1f - r[1] / r[3]) / 2f * viewH)
        }

        val segs = ArrayList<OverlayView.Seg>()
        val dots = ArrayList<PointF>()
        fun seg(a: FloatArray, ay: Float, b: FloatArray, by: Float, label: String?, kind: OverlayView.Kind) {
            val pa = screen(a[0], ay, a[1]) ?: return
            val pb = screen(b[0], by, b[1]) ?: return
            segs.add(OverlayView.Seg(pa, pb, label, kind))
        }
        fun len(a: FloatArray, b: FloatArray) = String.format(Locale.US, "%.2f m", Geometry.dist(a, b))

        // Rooms already scanned in this walk, as thin outlines.
        for (r in ScanStore.rooms) {
            if (r.session != sessionId || abs(r.floorY - (if (fy.isNaN()) camY - 1.4f else fy)) > 1.5f) continue
            for (i in r.corners.indices) seg(r.corners[i], r.floorY, r.corners[(i + 1) % r.corners.size], r.floorY, null, OverlayView.Kind.SAVED)
        }
        if (!fy.isNaN()) {
            for (i in 0 until pts.size - 1) seg(pts[i], fy, pts[i + 1], fy, len(pts[i], pts[i + 1]), OverlayView.Kind.CURRENT)
            val a = aim
            if (curMode == Mode.CORNERS && a != null && pts.isNotEmpty()) {
                val ap = floatArrayOf(a[0], a[2])
                seg(pts.last(), fy, ap, fy, len(pts.last(), ap), OverlayView.Kind.PREVIEW)
                if (pts.size >= 2) seg(ap, fy, pts.first(), fy, null, OverlayView.Kind.PREVIEW)
            }
            if (curMode == Mode.HEIGHT && pts.size >= 3) {
                seg(pts.last(), fy, pts.first(), fy, len(pts.last(), pts.first()), OverlayView.Kind.CURRENT)
                if (a != null) {
                    val base = floatArrayOf(a[0], a[2])
                    seg(base, fy, base, a[1], String.format(Locale.US, "%.2f m", a[1] - fy), OverlayView.Kind.HEIGHT)
                }
            }
            for (p in pts) screen(p[0], fy, p[1])?.let { dots.add(it) }
        }
        overlay.set(segs, dots, aim != null)
    }

    /** Where the centre ray meets the floor plane. */
    private fun floorHit(o: FloatArray, d: FloatArray, fy: Float): FloatArray? {
        if (d[1] > -0.05f) return null
        val t = (fy - o[1]) / d[1]
        if (t <= 0f || t > 15f) return null
        return floatArrayOf(o[0] + t * d[0], fy, o[2] + t * d[2])
    }

    /** Where the centre ray meets one of this room's walls (vertical planes through the measured corners). */
    private fun wallHit(o: FloatArray, d: FloatArray, pts: List<FloatArray>, fy: Float): FloatArray? {
        if (pts.size < 3) return null
        var bestT = Float.MAX_VALUE
        var hit: FloatArray? = null
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val ex = b[0] - a[0]
            val ez = b[1] - a[1]
            val l = sqrt(ex * ex + ez * ez)
            if (l < 0.1f) continue
            val nx = -ez / l
            val nz = ex / l
            val denom = nx * d[0] + nz * d[2]
            if (abs(denom) < 1e-4f) continue
            val t = (nx * (a[0] - o[0]) + nz * (a[1] - o[2])) / denom
            if (t <= 0.2f || t >= bestT) continue
            val px = o[0] + t * d[0]
            val pz = o[2] + t * d[2]
            val s = ((px - a[0]) * ex + (pz - a[1]) * ez) / (l * l)
            if (s < -0.05f || s > 1.05f) continue
            val py = o[1] + t * d[1]
            if (py < fy) continue
            bestT = t
            hit = floatArrayOf(px, py, pz)
        }
        return hit
    }
}
