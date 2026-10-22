package org.opengamepad.macro

import org.json.JSONArray
import org.json.JSONObject

enum class Stick { LEFT, RIGHT }

/** One step of a macro. */
sealed interface Action {
    /** Holds controller buttons (and LT/RT) for [ms]. */
    data class Press(val buttons: Int, val leftTrigger: Boolean, val rightTrigger: Boolean, val ms: Long) : Action

    /** Holds PC keys (modifier bits + HID usages) for [ms]. */
    data class Keys(val modifiers: Int, val keys: List<Int>, val ms: Long) : Action

    /** Moves a stick to x/y (-1..1, up positive) for [ms]. */
    data class Move(val stick: Stick, val x: Float, val y: Float, val ms: Long) : Action

    data class Wait(val ms: Long) : Action
}

/** Buttons that start a macro, read from touch and physical controller together. */
data class Trigger(
    val buttons: Int,
    val leftTrigger: Boolean,
    val rightTrigger: Boolean,
    val holdMs: Long,
    /** Hide the trigger buttons from the game while the combo is held. */
    val consume: Boolean,
) {
    val size: Int get() = Integer.bitCount(buttons) + (if (leftTrigger) 1 else 0) + (if (rightTrigger) 1 else 0)
}

data class Macro(
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    /** Opens this radial menu instead of running [actions]. */
    val radial: String?,
    /** Repeats [actions] while the trigger is held (turbo). */
    val repeat: Boolean,
)

/** [icon]: an emoji (or short text); [color]: ARGB background of the slice. */
data class RadialSlice(
    val label: String,
    val icon: String = "",
    val color: Long = DEFAULT_SLICE_COLOR,
    val actions: List<Action> = emptyList(),
)

const val DEFAULT_SLICE_COLOR = 0xFF3A3F4AL

data class RadialMenu(val id: String, val stick: Stick, val slices: List<RadialSlice>)

data class MacroConfig(
    val macros: List<Macro>,
    val radials: Map<String, RadialMenu>,
    /**
     * A button that starts a multi-button combo is held back this long, so that pressing
     * L3+R3 does not leak L3 to the game when R3 arrives a few ms later. 0 = off.
     */
    val comboWindowMs: Long = DEFAULT_COMBO_WINDOW_MS,
) {

    /** Inverse of [parse], used by the visual editor to save. */
    fun toJson(): String {
        val root = JSONObject()
        root.put("comboWindowMs", comboWindowMs)
        root.put("macros", JSONArray().apply {
            macros.forEach { m ->
                put(JSONObject().apply {
                    put("name", m.name)
                    put("trigger", JSONObject().apply {
                        put("buttons", JSONArray(buttonNames(m.trigger.buttons, m.trigger.leftTrigger, m.trigger.rightTrigger)))
                        if (m.trigger.holdMs > 0) put("holdMs", m.trigger.holdMs)
                        if (!m.trigger.consume) put("consume", false)
                    })
                    if (m.radial != null) put("radial", m.radial) else put("actions", actionsJson(m.actions))
                    if (m.repeat) put("repeat", true)
                })
            }
        })
        root.put("radials", JSONArray().apply {
            radials.values.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("stick", r.stick.name)
                    put("slices", JSONArray().apply {
                        r.slices.forEach { slice ->
                            put(JSONObject().apply {
                                put("label", slice.label)
                                if (slice.icon.isNotEmpty()) put("icon", slice.icon)
                                put("color", "#%06X".format(slice.color and 0xFFFFFF))
                                put("actions", actionsJson(slice.actions))
                            })
                        }
                    })
                })
            }
        })
        return root.toString(2)
    }

    companion object {
        fun buttonNames(mask: Int, lt: Boolean, rt: Boolean): List<String> =
            Names.BUTTONS.filterValues { mask and it != 0 }.keys.toList() +
                listOfNotNull(Names.LT.takeIf { lt }, Names.RT.takeIf { rt })

        fun keyNames(modifiers: Int, keys: List<Int>): List<String> =
            MODIFIER_NAMES.filter { (bit, _) -> modifiers and bit != 0 }.map { it.second } +
                keys.mapNotNull { key -> Names.KEYS.entries.firstOrNull { it.value == key }?.key }

        private val MODIFIER_NAMES = listOf(
            0x01 to "CTRL", 0x02 to "SHIFT", 0x04 to "ALT", 0x08 to "WIN",
            0x10 to "RCTRL", 0x20 to "RSHIFT", 0x40 to "RALT", 0x80 to "RWIN",
        )

        private fun actionsJson(actions: List<Action>) = JSONArray().apply {
            actions.forEach { a ->
                put(JSONObject().apply {
                    when (a) {
                        is Action.Press -> { put("press", JSONArray(buttonNames(a.buttons, a.leftTrigger, a.rightTrigger))); put("ms", a.ms) }
                        is Action.Keys -> { put("keys", JSONArray(keyNames(a.modifiers, a.keys))); put("ms", a.ms) }
                        is Action.Move -> { put("stick", a.stick.name); put("x", a.x.toDouble()); put("y", a.y.toDouble()); put("ms", a.ms) }
                        is Action.Wait -> put("wait", a.ms)
                    }
                })
            }
        }

        const val DEFAULT_COMBO_WINDOW_MS = 50L
        val EMPTY = MacroConfig(emptyList(), emptyMap())

        /** Parses the JSON configuration; throws IllegalArgumentException with a readable message. */
        fun parse(json: String): MacroConfig {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("JSON non valido: ${e.message}")
            }
            val radials = root.optJSONArray("radials").objects().associate { r ->
                val id = r.getString("id")
                id to RadialMenu(
                    id = id,
                    stick = stick(r.optString("stick", "RIGHT")),
                    slices = r.getJSONArray("slices").objects().map { s ->
                        RadialSlice(
                            label = s.optString("label"),
                            icon = s.optString("icon"),
                            color = color(s.optString("color")),
                            actions = actions(s.optJSONArray("actions"), "spicchio ${s.optString("label")}"),
                        )
                    },
                )
            }
            val macros = root.optJSONArray("macros").objects().map { m ->
                val name = m.optString("name", "macro")
                val radial = m.optString("radial").takeIf { it.isNotEmpty() }
                if (radial != null && radial !in radials) throw IllegalArgumentException("$name: ruota \"$radial\" inesistente")
                Macro(
                    name = name,
                    trigger = trigger(m.getJSONObject("trigger"), name),
                    actions = actions(m.optJSONArray("actions"), name),
                    radial = radial,
                    repeat = m.optBoolean("repeat", false),
                )
            }
            return MacroConfig(macros, radials, root.optLong("comboWindowMs", DEFAULT_COMBO_WINDOW_MS).coerceIn(0, 300))
        }

        private fun color(hex: String): Long {
            if (hex.isBlank()) return DEFAULT_SLICE_COLOR
            val digits = hex.removePrefix("#")
            val value = digits.toLongOrNull(16) ?: throw IllegalArgumentException("colore non valido \"$hex\"")
            return if (digits.length <= 6) 0xFF000000L or value else value
        }

        private fun trigger(t: JSONObject, context: String): Trigger {
            val names = t.getJSONArray("buttons").strings()
            if (names.isEmpty()) throw IllegalArgumentException("$context: trigger senza pulsanti")
            val (mask, lt, rt) = buttons(names, context)
            return Trigger(mask, lt, rt, t.optLong("holdMs", 0), t.optBoolean("consume", true))
        }

        private fun actions(array: JSONArray?, context: String): List<Action> = array.objects().map { a ->
            val ms = a.optLong("ms", 60)
            when {
                a.has("press") -> buttons(a.getJSONArray("press").strings(), context)
                    .let { (mask, lt, rt) -> Action.Press(mask, lt, rt, ms) }
                a.has("keys") -> keys(a.getJSONArray("keys").strings(), context)
                    .let { (mods, keys) -> Action.Keys(mods, keys, ms) }
                a.has("stick") -> Action.Move(
                    stick(a.getString("stick")),
                    a.optDouble("x", 0.0).toFloat(), a.optDouble("y", 0.0).toFloat(), ms,
                )
                a.has("wait") -> Action.Wait(a.getLong("wait"))
                else -> throw IllegalArgumentException("$context: azione sconosciuta $a")
            }
        }

        private fun buttons(names: List<String>, context: String): Triple<Int, Boolean, Boolean> {
            var mask = 0
            var lt = false
            var rt = false
            for (raw in names) {
                when (val name = raw.uppercase()) {
                    Names.LT -> lt = true
                    Names.RT -> rt = true
                    else -> mask = mask or (Names.BUTTONS[name]
                        ?: throw IllegalArgumentException("$context: pulsante sconosciuto \"$raw\""))
                }
            }
            return Triple(mask, lt, rt)
        }

        private fun keys(names: List<String>, context: String): Pair<Int, List<Int>> {
            var modifiers = 0
            val keys = mutableListOf<Int>()
            for (raw in names) {
                val name = raw.uppercase()
                Names.MODIFIERS[name]?.let { modifiers = modifiers or it } ?: keys.add(
                    Names.KEYS[name] ?: throw IllegalArgumentException("$context: tasto sconosciuto \"$raw\""),
                )
            }
            if (keys.size > 6) throw IllegalArgumentException("$context: massimo 6 tasti insieme")
            return modifiers to keys
        }

        private fun stick(name: String) = when (name.uppercase()) {
            "LEFT", "L" -> Stick.LEFT
            "RIGHT", "R" -> Stick.RIGHT
            else -> throw IllegalArgumentException("stick sconosciuto \"$name\" (LEFT o RIGHT)")
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { getJSONObject(it) }

        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

        /** Starting configuration with one example of each feature. */
        val EXAMPLE = """
            {
              "macros": [
                { "name": "Ruota rapida", "trigger": { "buttons": ["L3", "R3"] }, "radial": "rapida" },
                {
                  "name": "Screenshot",
                  "trigger": { "buttons": ["BACK", "Y"], "holdMs": 300 },
                  "actions": [ { "keys": ["WIN", "PRINTSCREEN"], "ms": 80 } ]
                },
                {
                  "name": "Turbo A",
                  "trigger": { "buttons": ["LB", "A"] },
                  "repeat": true,
                  "actions": [ { "press": ["A"], "ms": 40 }, { "wait": 40 } ]
                }
              ],
              "radials": [
                {
                  "id": "rapida",
                  "stick": "RIGHT",
                  "slices": [
                    { "label": "Cura", "icon": "❤️", "color": "#C2373D", "actions": [ { "keys": ["1"] } ] },
                    { "label": "Granata", "icon": "💣", "color": "#B7652A", "actions": [ { "keys": ["G"] } ] },
                    { "label": "Mappa", "icon": "🗺️", "color": "#2E7D4F", "actions": [ { "keys": ["M"] } ] },
                    { "label": "Salva", "icon": "💾", "color": "#2F5FA8", "actions": [ { "keys": ["F5"] } ] },
                    { "label": "Ricarica", "icon": "🔄", "color": "#6B4BB8", "actions": [ { "press": ["X"], "ms": 80 } ] },
                    { "label": "Menu", "icon": "⏸️", "color": "#55595F", "actions": [ { "keys": ["ESC"] } ] }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
