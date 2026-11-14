package org.opengamepad.amiibo

import org.opengamepad.str
import org.opengamepad.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialPick
import org.opengamepad.protocol.Message
import org.opengamepad.protocol.Wire

/**
 * Amiibo picker for the Switch sysmodule: keeps the console's list of virtual amiibo up to
 * date, builds the wheel and turns picks into requests. Used from the main thread.
 *
 * @param send writes a request on the control channel (type, payload).
 */
class AmiiboController(
    private val library: AmiiboLibrary,
    private val send: (Int, ByteArray) -> Unit,
) {
    /** Short messages for the user ("Amiibo attivo: Daruk"). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** True while connected to an OpenGamepad Switch: the wheel only makes sense there. */
    var available = false
        set(value) {
            field = value
            if (value) refresh() else {
                consolePaths = emptyList()
                pending.clear()
            }
        }

    private var consolePaths: List<String> = emptyList()
    private val pending = mutableListOf<String>()
    var emuiibo = true
        private set

    /** Asks the console for its virtual amiibo again (they can change on the SD card). */
    fun refresh() {
        if (!available) return
        pending.clear()
        send(Wire.TYPE_AMIIBO_LIST_REQUEST, AmiiboProtocol.listRequest(0))
    }

    /** Provider for the dynamic radial menu; null hides it (not connected to a Switch). */
    fun menu(): RadialMenu? {
        if (!available) {
            _messages.tryEmit(str(R.string.amiibo_need_wifi))
            return null
        }
        if (!emuiibo) _messages.tryEmit(str(R.string.amiibo_no_emuiibo_short))
        val phone = library.entries.value.map { AmiiboMenu.PhoneItem(it.name, it.group, it.key) }
        // The list may have changed since the last look: refresh for next time.
        refresh()
        return AmiiboMenu.build(phone, consolePaths)
    }

    fun onPick(pick: RadialPick) {
        val key = pick.slice.key
        when {
            key == AmiiboMenu.KEY_CLEAR -> send(Wire.TYPE_AMIIBO_CLEAR, ByteArray(0))
            key.startsWith(AmiiboMenu.CONSOLE_PREFIX) ->
                send(Wire.TYPE_AMIIBO_ACTIVATE, AmiiboProtocol.activateConsole(key.removePrefix(AmiiboMenu.CONSOLE_PREFIX)))
            key.startsWith(AmiiboMenu.PHONE_PREFIX) -> {
                val entry = library.find(key.removePrefix(AmiiboMenu.PHONE_PREFIX)) ?: return
                send(Wire.TYPE_AMIIBO_ACTIVATE, AmiiboProtocol.activatePhone(entry.identity, entry.name, entry.group))
            }
        }
    }

    fun onHostMessage(message: Message) {
        when (message.type) {
            Wire.TYPE_AMIIBO_LIST -> {
                val page = AmiiboProtocol.parseList(message.payload) ?: return
                emuiibo = page.emuiibo
                if (page.offset == 0) pending.clear()
                pending += page.paths
                val next = page.offset + page.paths.size
                if (next < page.total && page.paths.isNotEmpty()) {
                    send(Wire.TYPE_AMIIBO_LIST_REQUEST, AmiiboProtocol.listRequest(next))
                } else {
                    consolePaths = pending.toList()
                    pending.clear()
                }
            }
            Wire.TYPE_AMIIBO_STATUS -> {
                val status = AmiiboProtocol.parseStatus(message.payload) ?: return
                _messages.tryEmit(
                    when (status.result) {
                        AmiiboProtocol.Result.ACTIVE -> str(R.string.amiibo_active, status.text)
                        AmiiboProtocol.Result.RESTART_GAME ->
                            str(R.string.amiibo_active_restart, status.text)
                        AmiiboProtocol.Result.CLEARED -> str(R.string.amiibo_removed)
                        AmiiboProtocol.Result.NO_EMUIIBO -> str(R.string.amiibo_no_emuiibo)
                        AmiiboProtocol.Result.NOT_FOUND -> str(R.string.amiibo_not_found, status.text)
                        AmiiboProtocol.Result.ERROR -> str(R.string.amiibo_error, status.rc, status.text)
                    },
                )
                // A new folder may exist now.
                if (status.result == AmiiboProtocol.Result.ACTIVE || status.result == AmiiboProtocol.Result.RESTART_GAME) refresh()
            }
        }
    }
}
