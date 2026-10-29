package org.opengamepad.amiibo

import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialMode
import org.opengamepad.macro.RadialSlice
import org.opengamepad.macro.Stick

/**
 * Builds the amiibo wheel: "Telefono" (dumps stored in the app, one folder per group),
 * "Switch" (virtual amiibo already on the console's SD card, following its folders) and
 * "Rimuovi". Wheels never get more than [MAX_SLICES] slices: the rest goes into "Altri…".
 */
object AmiiboMenu {
    const val ID = "@amiibo"
    const val KEY_CLEAR = "clear"
    const val PHONE_PREFIX = "phone:"
    const val CONSOLE_PREFIX = "console:"
    const val MAX_SLICES = 8

    private const val PHONE_COLOR = 0xFF2F4C7AL
    private const val CONSOLE_COLOR = 0xFF8A2B2BL
    private const val FOLDER_COLOR = 0xFF3A3F4AL
    private const val CLEAR_COLOR = 0xFF2A2D33L

    /** An amiibo stored on the phone; [key] identifies it in the library. */
    data class PhoneItem(val name: String, val group: String, val key: String)

    fun build(phone: List<PhoneItem>, console: List<String>): RadialMenu {
        val clear = RadialSlice("Rimuovi", "⏏", CLEAR_COLOR, key = KEY_CLEAR)
        val sources = buildList {
            if (phone.isNotEmpty()) add(Triple("Telefono", "📱", phoneTree(phone)) to "phone")
            if (console.isNotEmpty()) add(Triple("Switch", "🎮", consoleTree(console, "")) to "console")
        }
        // With a single source its content goes straight on the first wheel; "Rimuovi" always
        // stays there, in the first slice.
        val content = sources.singleOrNull()?.first?.third
            ?: sources.map { (source, id) -> folder(source.first, source.second, source.third, id) }
        return menu(ID, listOf(clear) + paginate(content, ID, MAX_SLICES - 1))
    }

    private fun phoneTree(items: List<PhoneItem>): List<RadialSlice> {
        val groups = items.groupBy { it.group }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        fun slices(list: List<PhoneItem>) = list.sortedBy { it.name.lowercase() }
            .map { RadialSlice(it.name, initial(it.name), PHONE_COLOR, key = PHONE_PREFIX + it.key) }
        // Ungrouped dumps sit next to the group folders.
        val loose = slices(groups.remove("").orEmpty())
        val folders = groups.map { (group, list) -> folder(group, "📁", slices(list), "phone/$group") }
        return folders + loose
    }

    /** Console paths look like "Zelda/Daruk": every segment but the last is a folder. */
    private fun consoleTree(paths: List<String>, prefix: String): List<RadialSlice> {
        val (nested, leaves) = paths.partition { '/' in it }
        val folders = nested.groupBy { it.substringBefore('/') }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (name, inside) ->
                folder(name, "📁", consoleTree(inside.map { it.substringAfter('/') }, "$prefix$name/"), "console/$prefix$name")
            }
        val amiibo = leaves.sortedBy { it.lowercase() }
            .map { RadialSlice(it, initial(it), CONSOLE_COLOR, key = CONSOLE_PREFIX + prefix + it) }
        return folders + amiibo
    }

    private fun folder(label: String, icon: String, slices: List<RadialSlice>, id: String) =
        RadialSlice(label, icon, FOLDER_COLOR, key = "folder:$id", folder = menu("$ID/$id", paginate(slices, "$ID/$id")))

    /** Keeps at most [room] slices: the first [room] - 1 plus "Altri…" holding the rest. */
    private fun paginate(slices: List<RadialSlice>, id: String, room: Int = MAX_SLICES): List<RadialSlice> {
        if (slices.size <= room) return slices
        val rest = slices.drop(room - 1)
        return slices.take(room - 1) +
            RadialSlice("Altri…", "➕", FOLDER_COLOR, key = "more", folder = menu("$id+", paginate(rest, "$id+")))
    }

    private fun menu(id: String, slices: List<RadialSlice>) = RadialMenu(id, Stick.RIGHT, slices, RadialMode.STICKY)

    private fun initial(name: String) = name.trim().firstOrNull()?.uppercase() ?: "?"
}
