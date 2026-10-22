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
}
