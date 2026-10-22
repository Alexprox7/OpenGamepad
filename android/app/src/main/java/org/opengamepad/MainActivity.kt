package org.opengamepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
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
import org.opengamepad.bt.BluetoothGamepad
import org.opengamepad.bt.BluetoothKeepAliveService
import org.opengamepad.input.GamepadInput
import org.opengamepad.macro.MacroConfig
import org.opengamepad.macro.MacroEngine
import org.opengamepad.input.PhysicalGamepad
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

    private var showPad by mutableStateOf(false)
    private var showMacros by mutableStateOf(false)
    private var showJson by mutableStateOf(false)
    private var macroConfig by mutableStateOf(MacroConfig.EMPTY)
    private var saverMode by mutableStateOf(org.opengamepad.screen.SaverMode.BLACK)
    private var shizukuState by mutableStateOf(ShizukuScreen.Availability.NOT_RUNNING)
    private var playerLed by mutableStateOf(0)

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::connectTo)
    }

    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Notifications are optional: only the Bluetooth permissions are required.
            if (hasBluetoothPermission()) enableBluetooth()
            else toast("Senza il permesso Bluetooth la modalità senza companion non è disponibile")
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
        macros = MacroEngine(lifecycleScope)
        applyMacros(
            runCatching { MacroConfig.parse(prefs.macroJson) }.getOrElse {
                toast("Configurazione macro non valida: ${it.message}")
                MacroConfig.EMPTY
            },
            save = false,
        )

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
                if (status is ConnectionStatus.Connected) showPad = true
                if (status is ConnectionStatus.Failed) toast("Connessione persa: ${status.reason}")
            }
        }
        watchWakeCombo()
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
            val statusText = (btState as? BluetoothGamepad.State.Connected)
                ?.let { "Bluetooth: ${it.name}" } ?: describe(status)
            val radial by macros.radial.collectAsState()
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
                    onClose = { showMacros = false },
                )
            } else if (showPad) {
                ControllerScreen(
                    input = input,
                    status = statusText,
                    blackout = saverState != SaverState.OFF,
                    radial = radial,
                    onScreenSaver = { saver.enter(saverMode) },
                    onDisconnect = {
                        saver.exit()
                        connection.disconnect()
                        bluetooth.disconnectHost()
                        input.resetTouch()
                        showPad = false
                    },
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
                    onUsb = { connection.connect(ConnectTarget.usb()) },
                    onConnect = ::connectTo,
                    onTryLayout = { showPad = true },
                    onMacros = { showMacros = true },
                    onSaverMode = { saverMode = it; prefs.saverMode = it },
                    onShizukuPermission = {
                        shizuku.requestPermission { refreshShizuku() }
                    },
                    bluetooth = bluetoothPanel(btState),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        hideSystemBars()
    }

    override fun onPause() {
        // Safety: never leave the screen dark when the app is not in front.
        saver.exit()
        input.resetTouch()
        super.onPause()
    }

    override fun onDestroy() {
        connection.release()
        // With Bluetooth mode on, the registration stays alive in the foreground service.
        if (!prefs.bluetoothEnabled) bluetooth.stop()
        shizuku.release()
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
            BluetoothGamepad.State.Ready ->
                "Pronto: abbina \"OpenGamepad\" dal PC/TV, oppure tocca un dispositivo già abbinato"
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

    private fun enableBluetooth() {
        prefs.bluetoothEnabled = true
        BluetoothKeepAliveService.start(this)
        bluetooth.start()
    }

    private fun disableBluetooth() {
        prefs.bluetoothEnabled = false
        bluetooth.stop()
        BluetoothKeepAliveService.stop(this)
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
        playerLed = feedback.ledNumber
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
    }
}
