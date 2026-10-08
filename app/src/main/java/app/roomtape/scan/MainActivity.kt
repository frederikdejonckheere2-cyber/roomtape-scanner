package app.roomtape.scan

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Home screen: the rooms scanned so far, and the ways to get them into Roomtape. */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var btnScan: Button
    private lateinit var exportBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.rgb(23, 34, 58)) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            }
        fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            if (primary) {
                setBackgroundColor(Color.rgb(242, 183, 5))
                setTextColor(Color.rgb(43, 34, 0))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
        }

        col.addView(text("Measure your home", 24f, bold = true))
        col.addView(
            text(
                "Walk from room to room. In each room you aim at the floor corners and tap, then aim at the top of a wall for the ceiling height. " +
                    "Scan all rooms in one walk without closing the app: then Roomtape also knows where each room is.",
                15f, color = Color.rgb(90, 101, 120)
            )
        )
        btnScan = button("Scan rooms", primary = true) { startActivity(Intent(this, ScanActivity::class.java)) }
        col.addView(btnScan)
        summary = text("", 15f, bold = true)
        col.addView(summary)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        exportBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        exportBox.addView(text("Send to Roomtape", 18f, bold = true))
        exportBox.addView(
            text(
                "Save the scan file, then open Roomtape in the Claude app, go to Scan and tap Import a scan. " +
                    "Or copy the scan and paste it there.",
                15f, color = Color.rgb(90, 101, 120)
            )
        )
        exportBox.addView(button("Save scan file") { saveFile() })
        exportBox.addView(button("Copy scan for Roomtape") { copyScan() })
        exportBox.addView(button("Start a new scan") { confirmClear() })
        col.addView(exportBox)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        ScanStore.load(this)
        render()
    }

    private fun render() {
        val d = resources.displayMetrics.density
        list.removeAllViews()
        val rooms = ScanStore.rooms
        val total = rooms.sumOf { Geometry.area(it.corners).toDouble() }
        summary.text = if (rooms.isEmpty()) "No rooms scanned yet."
        else String.format(Locale.US, "%d room%s · %.1f m²", rooms.size, if (rooms.size == 1) "" else "s", total)
        btnScan.text = if (rooms.isEmpty()) "Scan rooms" else "Scan more rooms"
        exportBox.visibility = if (rooms.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        for (level in rooms.map { it.level }.distinct().sortedDescending()) {
            list.addView(TextView(this).apply {
                text = ScanStore.floorName(level).uppercase(Locale.ROOT)
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(Color.rgb(90, 101, 120))
                setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            })
            for (r in rooms.filter { it.level == level }) {
                val s = Geometry.size(r.corners)
                list.addView(TextView(this).apply {
                    text = String.format(Locale.US, "%s   %.2f × %.2f m · %.1f m² · ceiling %.2f m", r.name, s.width, s.depth, s.area, r.height)
                    textSize = 15f
                    setTextColor(Color.rgb(23, 34, 58))
                    setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                })
            }
        }
        if (rooms.map { it.session }.distinct().size > 1) {
            list.addView(TextView(this).apply {
                text = "These rooms were scanned in more than one walk. Rooms from a later walk are placed beside the others in Roomtape; drag them into place on the floor plan."
                textSize = 13f
                setTextColor(Color.rgb(180, 84, 10))
                setPadding(0, (10 * d).toInt(), 0, 0)
            })
        }
    }

    private fun saveFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "roomtape-scan.json")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, SAVE_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAVE_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(ScanStore.exportJson().toByteArray()) }
            Toast.makeText(this, "Saved. Now import it in Roomtape.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save the file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyScan() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Roomtape scan", ScanStore.exportJson()))
        Toast.makeText(this, "Copied. In Roomtape, tap Import a scan and paste it.", Toast.LENGTH_LONG).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Start a new scan?")
            .setMessage("This removes the ${ScanStore.rooms.size} rooms on this phone. Rooms you already imported into Roomtape stay there.")
            .setPositiveButton("Remove and start over") { _, _ -> ScanStore.clear(this); render() }
            .setNegativeButton("Keep them", null)
            .show()
    }

    companion object {
        private const val SAVE_REQUEST = 7
    }
}
