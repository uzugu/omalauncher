package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool

/** Bounds all cross-app frame data before it reaches OWL's drawing code. */
object VpetFrame {
    fun decode(width: Int, height: Int, rows: List<String>, ready: Boolean, frozen: Boolean): Value {
        require(width in 1..64 && height in 1..64) { "Invalid V-pet display size" }
        require(rows.isEmpty() || (rows.size == height && rows.all { it.length == width && it.all { c -> c in ".pid" } })) { "Invalid V-pet pixels" }
        return Obj(mapOf(
            "available" to bool(true),
            "ready" to bool(ready && rows.isNotEmpty()),
            "frozen" to bool(frozen),
            "status" to Str(if (frozen) "sleeping" else if (ready && rows.isNotEmpty()) "live" else "Open V-pet and load a ROM"),
            "width" to Num(width.toDouble()),
            "height" to Num(height.toDouble()),
            "rows" to Lst(rows.map(::Str)),
        ))
    }

    fun unavailable(status: String): Value = Obj(mapOf(
        "available" to bool(false), "ready" to bool(false), "frozen" to bool(false),
        "status" to Str(status), "width" to Num(32.0), "height" to Num(24.0), "rows" to Lst(emptyList()),
    ))
}
