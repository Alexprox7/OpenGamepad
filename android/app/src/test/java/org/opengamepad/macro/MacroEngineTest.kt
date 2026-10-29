package org.opengamepad.macro

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState

@OptIn(ExperimentalCoroutinesApi::class)
class MacroEngineTest {

    private fun TestScope.engine(config: MacroConfig = MacroConfig.parse(MacroConfig.EXAMPLE)) =
        MacroEngine(backgroundScope, clock = { testScheduler.currentTime }).also { it.config = config }

    private fun pressed(vararg buttons: Int, rightX: Short = 0, rightY: Short = 0) =
        ControllerState(buttons = buttons.fold(0) { acc, b -> acc or b }, rightX = rightX, rightY = rightY)

    private fun MacroEngine.has(button: Int) = output.value.buttons and button != 0

    @Test
    fun `L3 then R3 within the combo window never leaks L3 and opens the wheel`() = runTest {
        val engine = engine()
        engine.onInput(pressed(Buttons.LEFT_THUMB)); runCurrent()
        assertTrue(!engine.has(Buttons.LEFT_THUMB))

        advanceTimeBy(30)
        engine.onInput(pressed(Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB)); runCurrent()
        assertNotNull(engine.radial.value)
        advanceTimeBy(200); runCurrent()
        assertTrue(!engine.has(Buttons.LEFT_THUMB))
        assertTrue(!engine.has(Buttons.RIGHT_THUMB))
    }

    @Test
    fun `L3 alone reaches the game once the window has passed`() = runTest {
        val engine = engine()
        engine.onInput(pressed(Buttons.LEFT_THUMB)); runCurrent()
        advanceTimeBy(49); runCurrent()
        assertTrue(!engine.has(Buttons.LEFT_THUMB))
        advanceTimeBy(2); runCurrent()
        assertTrue(engine.has(Buttons.LEFT_THUMB))
    }

    @Test
    fun `buttons outside combos are never delayed`() = runTest {
        val engine = engine()
        engine.onInput(pressed(Buttons.B)); runCurrent()
        assertTrue(engine.has(Buttons.B))
    }

    @Test
    fun `wheel slice chosen with the stick runs its keys on release`() = runTest {
        val engine = engine()
        engine.onInput(pressed(Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB)); runCurrent()
        // Right stick up: first slice ("Cura" -> key 1); the stick itself is not sent to the game
        engine.onInput(pressed(Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB, rightY = 32767)); runCurrent()
        assertEquals(0, engine.radial.value?.highlighted)
        assertEquals(0, engine.output.value.rightY.toInt())

        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        assertNull(engine.radial.value)
        assertEquals(listOf(Names.KEYS.getValue("1")), engine.keyboard.value.keys)
        advanceTimeBy(100); runCurrent()
        assertTrue(engine.keyboard.value.keys.isEmpty())
    }

    @Test
    fun `releasing the wheel without moving the stick cancels`() = runTest {
        val engine = engine()
        engine.onInput(pressed(Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB)); runCurrent()
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        assertTrue(engine.keyboard.value.keys.isEmpty())
        assertEquals(0, engine.output.value.buttons)
    }

    @Test
    fun `turbo repeats while held and stops on release`() = runTest {
        val engine = engine()
        engine.onInput(pressed(Buttons.LEFT_SHOULDER, Buttons.A)); runCurrent()
        var presses = 0
        var wasDown = false
        repeat(40) {
            advanceTimeBy(10); runCurrent()
            val down = engine.has(Buttons.A)
            if (down && !wasDown) presses++
            wasDown = down
            assertTrue(!engine.has(Buttons.LEFT_SHOULDER)) // consumed
        }
        assertTrue("turbo pressed A $presses times in 400 ms", presses in 4..6)
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        advanceTimeBy(200); runCurrent()
        assertTrue(!engine.has(Buttons.A))
    }

    @Test
    fun `configuration survives a JSON round trip`() {
        val config = MacroConfig.parse(MacroConfig.EXAMPLE)
        assertEquals(config, MacroConfig.parse(config.toJson()))
    }

    // Wheels with two slices: 0 = stick up, 1 = stick down.
    private val nested = MacroConfig.parse(
        """
        {
          "macros": [
            { "name": "Hold", "trigger": { "buttons": ["L3", "R3"] }, "radial": "main" },
            { "name": "Sticky", "trigger": { "buttons": ["BACK", "START"] }, "radial": "sticky" },
            { "name": "Amiibo", "trigger": { "buttons": ["LB", "RB"] }, "radial": "@amiibo" }
          ],
          "radials": [
            { "id": "main", "slices": [
              { "label": "Uno", "actions": [ { "keys": ["1"] } ] },
              { "label": "Armi", "submenu": "armi" }
            ] },
            { "id": "armi", "slices": [
              { "label": "Spada", "key": "sword", "actions": [ { "keys": ["S"] } ] },
              { "label": "Amiibo", "submenu": "@amiibo" }
            ] },
            { "id": "sticky", "mode": "STICKY", "slices": [
              { "label": "Uno", "actions": [ { "keys": ["1"] } ] },
              { "label": "Armi", "submenu": "armi" }
            ] }
          ]
        }
        """,
    )

    private val up: Short = 32767
    private val down: Short = -32767

    private fun TestScope.openSticky(engine: MacroEngine) {
        engine.onInput(pressed(Buttons.BACK, Buttons.START)); runCurrent()
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
    }

    @Test
    fun `sticky wheel stays open and A confirms without reaching the game`() = runTest {
        val engine = engine(nested)
        openSticky(engine)
        assertEquals(true, engine.radial.value?.sticky)

        engine.onInput(pressed(rightY = up)); runCurrent()
        assertEquals(0, engine.radial.value?.highlighted)
        assertEquals(0, engine.output.value.rightY.toInt()) // driving stick is hidden
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        assertEquals(0, engine.radial.value?.highlighted) // kept after recentring

        engine.onInput(pressed(Buttons.A)); runCurrent()
        assertNull(engine.radial.value)
        assertEquals(listOf(Names.KEYS.getValue("1")), engine.keyboard.value.keys)
        advanceTimeBy(200); runCurrent()
        assertTrue(!engine.has(Buttons.A)) // still held, still hidden
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        engine.onInput(pressed(Buttons.A)); runCurrent()
        assertTrue(engine.has(Buttons.A)) // a new press reaches the game
    }

    @Test
    fun `sticky wheel closes with B, which is consumed`() = runTest {
        val engine = engine(nested)
        openSticky(engine)
        engine.onInput(pressed(Buttons.B)); runCurrent()
        assertNull(engine.radial.value)
        assertTrue(!engine.has(Buttons.B))
        assertTrue(engine.keyboard.value.keys.isEmpty())
    }

    @Test
    fun `combo again confirms the sticky choice, or closes with nothing highlighted`() = runTest {
        val engine = engine(nested)
        openSticky(engine)
        engine.onInput(pressed(rightY = up)); runCurrent()
        engine.onInput(pressed(Buttons.BACK, Buttons.START)); runCurrent()
        assertNull(engine.radial.value)
        assertEquals(listOf(Names.KEYS.getValue("1")), engine.keyboard.value.keys)
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        advanceTimeBy(200); runCurrent()

        openSticky(engine)
        assertNotNull(engine.radial.value)
        engine.onInput(pressed(Buttons.BACK, Buttons.START)); runCurrent()
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        assertNull(engine.radial.value)
        assertTrue(engine.keyboard.value.keys.isEmpty())
    }

    @Test
    fun `folders open with A and B goes back one level`() = runTest {
        val engine = engine(nested)
        openSticky(engine)
        engine.onInput(pressed(rightY = down)); runCurrent()
        engine.onInput(pressed(Buttons.A, rightY = down)); runCurrent()
        val view = engine.radial.value!!
        assertEquals("armi", view.menu.id)
        assertEquals(listOf("sticky", "Armi"), view.trail)
        assertNull(view.highlighted)
        // The stick still points at the folder: ignored until it moves.
        engine.onInput(pressed(Buttons.A, rightY = down)); runCurrent()
        assertNull(engine.radial.value?.highlighted)

        engine.onInput(pressed(Buttons.B)); runCurrent()
        assertEquals("sticky", engine.radial.value?.menu?.id)
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        engine.onInput(pressed(Buttons.B)); runCurrent()
        assertNull(engine.radial.value)
        assertEquals(0, engine.output.value.buttons)
    }

    @Test
    fun `hold wheel opens a folder after resting on it`() = runTest {
        val engine = engine(nested)
        val combo = pressed(Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB)
        engine.onInput(combo); runCurrent()
        engine.onInput(combo.copy(rightY = down)); runCurrent()
        advanceTimeBy(MacroEngine.FOLDER_DWELL_MS - 10); runCurrent()
        assertEquals("main", engine.radial.value?.menu?.id)
        advanceTimeBy(20); runCurrent()
        assertEquals("armi", engine.radial.value?.menu?.id)

        engine.onInput(combo.copy(rightY = up)); runCurrent()
        assertEquals(0, engine.radial.value?.highlighted)
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        assertNull(engine.radial.value)
        assertEquals(listOf(Names.KEYS.getValue("S")), engine.keyboard.value.keys)
    }

    @Test
    fun `releasing a hold wheel on a folder cancels`() = runTest {
        val engine = engine(nested)
        val combo = pressed(Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB)
        engine.onInput(combo); runCurrent()
        engine.onInput(combo.copy(rightY = down)); runCurrent()
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        assertNull(engine.radial.value)
        advanceTimeBy(1000); runCurrent()
        assertNull(engine.radial.value)
        assertTrue(engine.keyboard.value.keys.isEmpty())
    }

    @Test
    fun `dynamic menu reports the pick path instead of running actions`() = runTest {
        val engine = engine(nested)
        val picks = mutableListOf<RadialPick>()
        engine.registerDynamicRadial("@amiibo", provider = {
            RadialMenu(
                "@amiibo", Stick.RIGHT, mode = RadialMode.STICKY,
                slices = listOf(
                    RadialSlice("Zelda", "📁", key = "zelda", folder = RadialMenu(
                        "zelda", Stick.RIGHT, listOf(RadialSlice("Link", key = "link.bin"), RadialSlice("Zelda", key = "zelda.bin")),
                    )),
                    RadialSlice("Mario", key = "mario.bin"),
                ),
            )
        }, onPick = { picks += it })

        // Opened by its combo; tapping on the touchscreen.
        engine.onInput(pressed(Buttons.LEFT_SHOULDER, Buttons.RIGHT_SHOULDER)); runCurrent()
        engine.onInput(ControllerState.NEUTRAL); runCurrent()
        engine.tapSlice(0)
        assertEquals(listOf("amiibo", "Zelda"), engine.radial.value?.trail)
        engine.tapSlice(1)
        assertNull(engine.radial.value)
        assertEquals(listOf("zelda", "zelda.bin"), picks.single().keys)
        assertEquals("@amiibo", picks.single().menuId)

        // Reached through a folder of a saved wheel: the path starts at the dynamic menu.
        assertTrue(engine.openRadial("armi"))
        engine.tapSlice(1)
        engine.tapSlice(1)
        assertEquals(listOf("mario.bin"), picks.last().keys)
        assertTrue(engine.keyboard.value.keys.isEmpty())
    }

    @Test
    fun `a hold wheel opened from code ends with releaseRadial`() = runTest {
        val engine = engine(nested)
        assertTrue(engine.openRadial("main"))
        assertTrue(!engine.openRadial("missing"))
        engine.onInput(pressed(rightY = up)); runCurrent()
        engine.releaseRadial(); runCurrent()
        assertNull(engine.radial.value)
        assertEquals(listOf(Names.KEYS.getValue("1")), engine.keyboard.value.keys)
    }

    @Test
    fun `new radial fields survive a JSON round trip`() {
        assertEquals(nested, MacroConfig.parse(nested.toJson()))
        assertEquals(RadialMode.STICKY, nested.radials.getValue("sticky").mode)
        assertEquals("sword", nested.radials.getValue("armi").slices[0].key)
    }

    @Test
    fun `configs saved before submenus still load unchanged`() {
        // Written by version 1.1.0, without mode, submenu or key.
        val old = """
            {"comboWindowMs":50,"macros":[{"name":"Ruota rapida","trigger":{"buttons":["L3","R3"]},"radial":"rapida"}],
             "radials":[{"id":"rapida","stick":"RIGHT","slices":[
               {"label":"Cura","icon":"❤️","color":"#C2373D","actions":[{"keys":["1"],"ms":60}]}]}]}
        """
        val config = MacroConfig.parse(old)
        val menu = config.radials.getValue("rapida")
        assertEquals(RadialMode.HOLD, menu.mode)
        assertNull(menu.slices[0].submenu)
        assertEquals("", menu.slices[0].key)
        val json = config.toJson()
        assertTrue("mode" !in json && "submenu" !in json && "key\"" !in json)
        assertEquals(config, MacroConfig.parse(json))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a folder pointing at a missing wheel is rejected`() {
        MacroConfig.parse("""{"radials":[{"id":"a","slices":[{"label":"x","submenu":"nope"}]}]}""")
    }
}
