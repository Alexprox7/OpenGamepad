package org.opengamepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opengamepad.amiibo.AmiiboController
import org.opengamepad.amiibo.AmiiboLibrary
import org.opengamepad.amiibo.AmiiboMenu
import org.opengamepad.bt.BluetoothGamepad
import org.opengamepad.bt.BluetoothKeepAliveService
import org.opengamepad.bt.GenericGamepadProfile
import org.opengamepad.bt.SwitchProProfile
import org.opengamepad.input.GamepadInput
import org.opengamepad.macro.MacroConfig
import org.opengamepad.macro.MacroEngine
import org.opengamepad.input.PhysicalGamepad
import org.opengamepad.motion.MotionController
import org.opengamepad.motion.MotionSettings
import org.opengamepad.net.ConnectTarget
import org.opengamepad.net.ConnectionStatus
import org.opengamepad.net.WifiConnection
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.Feedback
import org.opengamepad.screen.SaverState
import org.opengamepad.screen.ScreenSaver
import org.opengamepad.screen.ShizukuScreen
import org.opengamepad.ui.BluetoothPanel
import org.opengamepad.ui.ConnectScreen
import org.opengamepad.ui.StatusKind
import org.opengamepad.ui.ControllerScreen
import org.opengamepad.ui.MacroEditorScreen
import org.opengamepad.ui.editor.MacroHomeScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.opengamepad.protocol.ControllerState

class MainActivity : ComponentActivity() {
    private val input = GamepadInput()
    private val physical = PhysicalGamepad(input)
    private lateinit var prefs: Prefs
    private lateinit var connection: WifiConnection
    private lateinit var shizuku: ShizukuScreen
    private lateinit var saver: ScreenSaver
    private lateinit var bluetooth: BluetoothGamepad
    private lateinit var macros: MacroEngine
    private lateinit var motion: MotionController
    private lateinit var amiiboLibrary: AmiiboLibrary
    private lateinit var amiibo: AmiiboController

    private var showPad by mutableStateOf(false)
    private var showMacros by mutableStateOf(false)
    private var showJson by mutableStateOf(false)
    private var foundHosts by mutableStateOf(emptyList<org.opengamepad.net.FoundHost>())
    private var searching by mutableStateOf(false)
    private var macroConfig by mutableStateOf(MacroConfig.EMPTY)
    private var saverMode by mutableStateOf(org.opengamepad.screen.SaverMode.BLACK)
    private var shizukuState by mutableStateOf(ShizukuScreen.Availability.NOT_RUNNING)
    private var playerLed by mutableStateOf(0)
    private var switchPlayer by mutableStateOf(0)
    private var bluetoothProfileId by mutableStateOf(GenericGamepadProfile.ID)
    private var switchJobs: List<Job> = emptyList()

    private val amiiboFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { reportImport(amiiboLibrary.importFiles(contentResolver, uris)) }
    }
    private val amiiboFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) lifecycleScope.launch { reportImport(amiiboLibrary.importTree(contentResolver, tree)) }
    }

    private fun reportImport(result: AmiiboLibrary.ImportResult) {
        toast(
            "Amiibo importati: ${result.added}" +
                if (result.skipped > 0) " · ${result.skipped} file ignorati (non sono dump amiibo)" else "",
        )
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::connectTo)
    }

    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Notifications are optional: only the Bluetooth permissions are required.
            if (hasBluetoothPermission()) enableBluetooth()
            else toast("Senza il permesso Bluetooth la modalità senza companion non è disponibile")
        }

    /**
     * Switch pairing: the console gives up if the "just works" request is not confirmed
     * within a few seconds, so in Switch mode it is confirmed at once through Shizuku.
     */
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (bluetoothProfileId != SwitchProProfile.ID) return
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val starting = intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST || bondState == BluetoothDevice.BOND_BONDING
            val name = runCatching { device.name }.getOrNull().orEmpty()
            if (!(name.contains("Switch", true) || name.contains("Nintendo", true))) return
            if (bondState == BluetoothDevice.BOND_BONDED) {
                // A real controller opens the HID channels itself right after pairing;
                // the console closes the link if nothing arrives.
                android.util.Log.i("MainActivity", "paired with ${device.address}, connecting HID")
                bluetooth.connect(device)
                return
            }
            if (!starting) return
            if (shizukuState != ShizukuScreen.Availability.READY) {
                toast("Conferma subito \"Accoppia\": con Shizuku attivo lo farebbe l'app da sola")
                return
            }
            lifecycleScope.launch {
                // The request may not be ready yet when bonding starts: retry briefly.
                repeat(10) {
                    if (shizuku.confirmPairing(device.address)) {
                        // Android announces BOND_BONDED seconds later, after its own service
                        // discovery; the console drops the link long before. Open the HID
                        // channels right away, as a real controller would.
                        repeat(5) { attempt ->
                            delay(if (attempt == 0) 60L else 250L)
                            if (bluetooth.state.value is BluetoothGamepad.State.Connected) return@launch
                            android.util.Log.i("MainActivity", "confirmed ${device.address}, connecting HID (#$attempt)")
                            bluetooth.connect(device)
                        }
                        return@launch
                    }
                    delay(150)
                }
            }
        }
    }

    private val discoverable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        connection = WifiConnection(prefs.deviceId)
        shizuku = ShizukuScreen(this)
        saver = ScreenSaver(this, lifecycleScope, shizuku, ::toast)
        saverMode = prefs.saverMode
        bluetooth = (application as OpenGamepadApp).bluetooth
        useBluetoothProfile(prefs.bluetoothProfile)
        macros = MacroEngine(lifecycleScope)
        applyMacros(
            runCatching { MacroConfig.parse(prefs.macroJson) }.getOrElse {
                toast("Configurazione macro non valida: ${it.message}")
                MacroConfig.EMPTY
            },
            save = false,
        )

        motion = MotionController(this, input, MotionSettings.parse(prefs.motionJson), { prefs.motionJson = it.toJson() }, switchAddress())
        // Gyro passthrough: type 17 to the Switch sysmodule, IMU fields of the Bluetooth Pro Controller.
        motion.onSample = { sample ->
            sample?.let(connection::sendMotion)
            bluetooth.sendMotion(sample)
        }

        // Amiibo wheel for the Switch sysmodule: a dynamic radial menu, mappable to any combo.
        amiiboLibrary = AmiiboLibrary(filesDir)
        amiibo = AmiiboController(amiiboLibrary, connection::sendControl)
        macros.registerDynamicRadial(AmiiboMenu.ID, amiibo::menu, amiibo::onPick)
        lifecycleScope.launch { amiiboLibrary.load() }
        lifecycleScope.launch { connection.hostMessages.collect(amiibo::onHostMessage) }
        lifecycleScope.launch { amiibo.messages.collect(::toast) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // touch + controller -> macro engine -> PC
        // While editing macros the controller is used for recording combos: nothing reaches the PC.
        lifecycleScope.launch {
            input.state.collect { macros.onInput(if (showMacros) ControllerState.NEUTRAL else it) }
        }
        lifecycleScope.launch {
            macros.output.collect { state ->
                connection.send(state)
                bluetooth.send(state)
                motion.onPadState(state)
            }
        }
        lifecycleScope.launch {
            macros.keyboard.collect { keys ->
                connection.sendKeys(keys)
                bluetooth.sendKeys(keys.modifiers, keys.keys)
            }
        }
        lifecycleScope.launch {
            bluetooth.state.collect { state ->
                if (state is BluetoothGamepad.State.Connected) showPad = true
                if (state is BluetoothGamepad.State.Failed) toast("Bluetooth: ${state.reason}")
            }
        }
        lifecycleScope.launch { connection.feedback.collect(::onFeedback) }
        lifecycleScope.launch {
            connection.status.collect { status ->
                if (status is ConnectionStatus.Connected) wifiLock.acquire() else if (wifiLock.isHeld) wifiLock.release()
                if (status is ConnectionStatus.Connected) showPad = true
                amiibo.available = status is ConnectionStatus.Connected &&
                    status.pcOs.startsWith("Switch") && "OpenGamepad" in status.pcOs
                if (status is ConnectionStatus.Failed) toast("Connessione persa: ${status.reason}")
            }
        }
        watchWakeCombo()
        ContextCompat.registerReceiver(
            this,
            pairingReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (prefs.bluetoothEnabled && hasBluetoothPermission()) enableBluetooth()
        if (BuildConfig.DEBUG) applyTestExtras()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = org.opengamepad.ui.Accent,
                    onPrimary = Color.Black,
                    secondaryContainer = Color(0xFF262A33),
                    onSecondaryContainer = Color.White,
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceContainerHigh = Color(0xFF15171B),
                    outline = Color(0xFF30343D),
                ),
            ) {
                Screens()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Screens() {
        run {
            val status by connection.status.collectAsState()
            val saverState by saver.state.collectAsState()
            val btState by bluetooth.state.collectAsState()
            val statusText = (btState as? BluetoothGamepad.State.Connected)?.let {
                if (bluetoothProfileId == SwitchProProfile.ID) {
                    "Switch: ${it.name}" + if (switchPlayer > 0) " · giocatore $switchPlayer" else ""
                } else {
                    "Bluetooth: ${it.name}"
                }
            } ?: describe(status)
            val radial by macros.radial.collectAsState()
            val motionSettings by motion.settings.collectAsState()
            if (showMacros && showJson) {
                MacroEditorScreen(
                    initial = macroConfig.toJson(),
                    onSave = { json ->
                        try {
                            applyMacros(MacroConfig.parse(json), save = true)
                            null
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Configurazione non valida"
                        }
                    },
                    onClose = { showJson = false },
                )
            } else if (showMacros) {
                MacroHomeScreen(
                    config = macroConfig,
                    rawInput = input.state,
                    onConfig = { applyMacros(it, save = true) },
                    onAdvanced = { showJson = true },
                    dynamicRadials = macros.dynamicRadialIds,
                    onClose = { showMacros = false },
                )
            } else if (showPad) {
                ControllerScreen(
                    input = input,
                    status = statusText,
                    blackout = saverState != SaverState.OFF,
                    radial = radial,
                    nintendoLayout = (bluetoothProfileId == SwitchProProfile.ID &&
                        btState is BluetoothGamepad.State.Connected) ||
                        (status as? ConnectionStatus.Connected)?.pcOs?.startsWith("Switch") == true,
                    onScreenSaver = { saver.enter(saverMode) },
                    onDisconnect = {
                        saver.exit()
                        connection.disconnect()
                        bluetooth.disconnectHost()
                        input.resetTouch()
                        showPad = false
                    },
                    onRadialTap = macros::tapSlice,
                    onRadialBack = macros::radialBack,
                    steering = motionSettings.steering && motion.hasTilt,
                    onSteering = if (motion.hasTilt) ({ motion.update { it.copy(steering = !it.steering) } }) else null,
                    onRecenter = motion::recenter,
                    onAmiibo = if ((status as? ConnectionStatus.Connected)?.pcOs?.startsWith("Switch") == true) {
                        { macros.openRadial(AmiiboMenu.ID) }
                    } else null,
                )
            } else {
                ConnectScreen(
                    status = statusText,
                    statusKind = when {
                        btState is BluetoothGamepad.State.Connected || status is ConnectionStatus.Connected -> StatusKind.CONNECTED
                        status is ConnectionStatus.Failed -> StatusKind.ERROR
                        status is ConnectionStatus.Connecting -> StatusKind.BUSY
                        else -> StatusKind.IDLE
                    },
                    initialTarget = prefs.lastTarget,
                    saverMode = saverMode,
                    shizuku = shizukuState,
                    onScan = ::scan,
                    found = foundHosts,
                    searching = searching,
                    onSearch = ::searchLan,
                    onFound = { host ->
                        prefs.lastTarget = "${host.host}:${host.port}"
                        connection.connect(host.target())
                    },
                    onUsb = { connection.connect(ConnectTarget.usb()) },
                    onConnect = ::connectTo,
                    onTryLayout = { showPad = true },
                    onMacros = { showMacros = true },
                    onSaverMode = { saverMode = it; prefs.saverMode = it },
                    onShizukuPermission = {
                        shizuku.requestPermission { refreshShizuku() }
                    },
                    bluetooth = bluetoothPanel(btState),
                    motion = { org.opengamepad.ui.MotionCard(motion) },
                    amiibo = {
                        org.opengamepad.ui.AmiiboCard(
                            amiiboLibrary,
                            onImportFiles = { amiiboFiles.launch(arrayOf("application/octet-stream", "*/*")) },
                            onImportFolder = { amiiboFolder.launch(null) },
                            onDelete = { entry -> lifecycleScope.launch { amiiboLibrary.delete(entry) } },
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        hideSystemBars()
        motion.resume()
    }

    override fun onPause() {
        // Safety: never leave the screen dark when the app is not in front.
        saver.exit()
        input.resetTouch()
        motion.pause()
        super.onPause()
    }

    /**
     * Keeps Wi-Fi out of power saving while connected: otherwise a state can wait for the next
     * beacon (tens to hundreds of ms) and input feels laggy or stuck.
     */
    private val wifiLock by lazy {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, "OpenGamepad:link")
            .apply { setReferenceCounted(false) }
    }

    override fun onDestroy() {
        if (wifiLock.isHeld) wifiLock.release()
        unregisterReceiver(pairingReceiver)
        connection.release()
        // With Bluetooth mode on, the registration stays alive in the foreground service.
        if (!prefs.bluetoothEnabled) bluetooth.stop()
        shizuku.release()
        motion.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (showPad && handleVolume(event)) return true
        if (physical.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        physical.onMotion(event) || super.dispatchGenericMotionEvent(event)

    /** Volume down: enter the screen saver. Volume up: leave it. */
    private fun handleVolume(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_DOWN && key != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (key == KeyEvent.KEYCODE_VOLUME_DOWN) saver.enter(saverMode) else saver.exit()
        }
        return true
    }

    /** Holding Back+Start on the physical controller for 1.5 s toggles the screen saver. */
    private fun watchWakeCombo() {
        var timer: Job? = null
        val combo = Buttons.BACK or Buttons.START
        lifecycleScope.launch {
            input.physicalState.collect { state ->
                val held = state.buttons and combo == combo
                if (held && timer == null) {
                    timer = launch {
                        delay(1500)
                        saver.toggle(saverMode)
                    }
                } else if (!held) {
                    timer?.cancel()
                    timer = null
                }
            }
        }
    }

    private fun applyMacros(config: MacroConfig, save: Boolean) {
        macroConfig = config
        macros.config = config
        if (save) prefs.macroJson = config.toJson()
    }

    private fun bluetoothPanel(state: BluetoothGamepad.State): BluetoothPanel {
        val active = state !is BluetoothGamepad.State.Off &&
            state !is BluetoothGamepad.State.Unsupported && state !is BluetoothGamepad.State.Failed
        val status = when (state) {
            BluetoothGamepad.State.Off -> ""
            BluetoothGamepad.State.Unsupported -> "Non supportato su questo telefono"
            BluetoothGamepad.State.Registering -> "Attivazione…"
            BluetoothGamepad.State.Ready -> if (bluetoothProfileId == SwitchProProfile.ID) {
                "Sulla Switch apri Controller → Cambia impugnatura/ordine, poi tocca Rendi visibile: " +
                    "il telefono appare come \"Pro Controller\""
            } else {
                "Pronto: abbina il telefono dal PC/TV, oppure tocca un dispositivo già abbinato"
            }
            is BluetoothGamepad.State.Connecting -> "Connessione a ${state.name}…"
            is BluetoothGamepad.State.Connected -> "Connesso a ${state.name}"
            is BluetoothGamepad.State.Failed -> "Errore: ${state.reason}"
        }
        val hosts = if (active && hasBluetoothPermission()) {
            runCatching { bluetooth.pairedHosts().map { it.address to (it.name ?: it.address) } }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return BluetoothPanel(
            active = active,
            status = status,
            pairedHosts = hosts,
            onStart = ::startBluetooth,
            onVisible = {
                discoverable.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                )
            },
            onDisable = ::disableBluetooth,
            profileId = bluetoothProfileId,
            onProfile = { id ->
                prefs.bluetoothProfile = id
                useBluetoothProfile(id)
            },
            onConnect = { address ->
                bluetooth.pairedHosts().firstOrNull { it.address == address }?.let(bluetooth::connect)
            },
        )
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startBluetooth() {
        if (hasBluetoothPermission()) {
            enableBluetooth()
        } else {
            val permissions = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            bluetoothPermissions.launch(permissions.toTypedArray())
        }
    }

    /**
     * Selects what the phone emulates over Bluetooth. The Switch profile also forwards
     * rumble to the phone's vibrator and shows the player number.
     */
    private fun useBluetoothProfile(id: String) {
        switchJobs.forEach { it.cancel() }
        switchJobs = emptyList()
        switchPlayer = 0
        bluetoothProfileId = id
        if (id != SwitchProProfile.ID) {
            bluetooth.profile = GenericGamepadProfile()
            restoreDeviceClass()
            return
        }
        applyGamepadDeviceClass()
        val current = bluetooth.profile as? SwitchProProfile ?: SwitchProProfile(switchAddress())
        bluetooth.profile = current
        switchJobs = listOf(
            lifecycleScope.launch { current.player.collect { switchPlayer = it } },
            lifecycleScope.launch {
                current.rumble.collect { on -> onFeedback(Feedback(if (on) 160 else 0, 0, 0)) }
            },
        )
    }

    /**
     * The Switch drops anything that is not a gamepad right after pairing: in Switch mode
     * the phone advertises class 0x002508 through Shizuku, and gets its own back afterwards.
     */
    private fun applyGamepadDeviceClass() {
        if (shizuku.availability() != ShizukuScreen.Availability.READY) {
            toast("Per la Switch serve Shizuku attivo: senza, la console rifiuta il telefono")
            return
        }
        lifecycleScope.launch {
            val previous = shizuku.setDeviceClass(GAMEPAD_DEVICE_CLASS)
            if (previous >= 0 && previous != GAMEPAD_DEVICE_CLASS && prefs.originalDeviceClass < 0) {
                prefs.originalDeviceClass = previous
            }
        }
    }

    private fun restoreDeviceClass() {
        val original = prefs.originalDeviceClass
        if (original < 0 || shizuku.availability() != ShizukuScreen.Availability.READY) return
        lifecycleScope.launch {
            if (shizuku.setDeviceClass(original) >= 0) prefs.originalDeviceClass = -1
        }
    }

    /** Stable controller address for the Switch: Nintendo prefix + bytes of the device id. */
    private fun switchAddress(): ByteArray {
        val id = prefs.deviceId
        return byteArrayOf(
            0x98.toByte(), 0xB6.toByte(), 0xE9.toByte(),
            (id shr 16).toByte(), (id shr 8).toByte(), id.toByte(),
        )
    }

    private fun enableBluetooth() {
        prefs.bluetoothEnabled = true
        BluetoothKeepAliveService.start(this)
        bluetooth.start()
    }

    private fun disableBluetooth() {
        restoreDeviceClass()
        prefs.bluetoothEnabled = false
        bluetooth.stop()
        BluetoothKeepAliveService.stop(this)
    }

    /** Looks for OpenGamepad companions and Switch sysmodules on the local network. */
    private fun searchLan() {
        searching = true
        lifecycleScope.launch {
            foundHosts = runCatching { org.opengamepad.net.Discovery.search() }
                .onFailure { android.util.Log.e("Discovery", "search failed", it) }
                .getOrDefault(emptyList())
            searching = false
            if (foundHosts.isEmpty()) toast("Nessun dispositivo OpenGamepad trovato sulla rete")
        }
    }

    private fun scan() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Inquadra il codice QR mostrato dall'app PC")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun connectTo(raw: String) {
        android.util.Log.i("MainActivity", "connect target: $raw")
        val target = ConnectTarget.parse(raw)
        if (target == null) {
            toast("Formato non riconosciuto: $raw")
            return
        }
        if (!target.usb) prefs.lastTarget = "${target.hosts.first()}:${target.port}"
        connection.connect(target)
    }

    private fun onFeedback(feedback: Feedback) {
        // 255 = the host has no player numbers (Linux companion).
        if (feedback.ledNumber < 16) playerLed = feedback.ledNumber
        val strength = maxOf(feedback.largeMotor, feedback.smallMotor)
        val vibrator = vibrator() ?: return
        if (strength == 0) {
            vibrator.cancel()
        } else if (vibrator.hasAmplitudeControl()) {
            vibrator.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_MS, strength.coerceIn(1, 255)))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    /**
     * Debug builds only, for testing over ADB:
     *   --es target IP:PORT | --ez usb true | --es macrosFile /data/local/tmp/macros.json
     */
    private fun applyTestExtras() {
        intent.getStringExtra("target")?.let(::connectTo)
        if (intent.getBooleanExtra("usb", false)) connection.connect(ConnectTarget.usb())
        intent.getStringExtra("macrosFile")?.let { path ->
            runCatching { applyMacros(MacroConfig.parse(java.io.File(path).readText()), save = false) }
                .onFailure { toast("Macro di test non caricate: ${it.message}") }
        }
    }

    private fun describe(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.Idle -> "Non connesso"
        is ConnectionStatus.Connecting -> "Connessione a ${status.target}…"
        is ConnectionStatus.Connected ->
            "Connesso a ${status.target} (${status.pcOs})" + if (playerLed > 0) " · giocatore $playerLed" else ""
        is ConnectionStatus.Failed -> "Errore: ${status.reason}"
    }

    private fun refreshShizuku() {
        shizukuState = shizuku.availability()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val RUMBLE_MAX_MS = 10_000L
        private const val GAMEPAD_DEVICE_CLASS = 0x002508 // peripheral, gamepad
    }
}
