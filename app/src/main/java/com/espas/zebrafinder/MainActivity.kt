package com.espas.zebrafinder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.espas.zebrafinder.ui.theme.ZebraFinderTheme
import kotlinx.coroutines.delay
import java.util.UUID

private val ZEBRA_SERVICE_UUID =
    UUID.fromString("7d9f0001-6f65-4a3e-9f23-2e4d8f3c0001")

private val ZEBRA_COMMAND_UUID =
    UUID.fromString("7d9f0002-6f65-4a3e-9f23-2e4d8f3c0001")

private val ZEBRA_SERVICE_PARCEL_UUID =
    ParcelUuid(ZEBRA_SERVICE_UUID)

private const val GL_PIN = "492680"
private const val GL_UNLOCK_DURATION_MS = 5 * 60 * 1000L
private const val PREFS_NAME = "zebrafinder"
private const val PREF_MANUAL_DEVICE_NAME = "manual_device_name"

data class ZebraDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val trend: SignalTrend = SignalTrend.STABLE,
    val bluetoothDevice: BluetoothDevice
)

enum class SignalTrend {
    CLOSER,
    STABLE,
    FARTHER
}

data class DistanceBand(
    val label: String,
    val pulseMs: Int,
    val proximity: String
)

fun distanceBandForRssi(rssi: Int): DistanceBand =
    when {
        rssi >= -45 -> DistanceBand("< 0.5 m", 250, "SEHR NAH")
        rssi >= -55 -> DistanceBand("0.5–1 m", 400, "NAH")
        rssi >= -65 -> DistanceBand("1–2 m", 700, "NAH")
        rssi >= -75 -> DistanceBand("2–5 m", 1100, "MITTEL")
        rssi >= -85 -> DistanceBand("5–10 m", 1600, "WEIT")
        else -> DistanceBand("> 10 m", 2000, "SEHR WEIT")
    }

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: android.bluetooth.BluetoothAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopBleScan() }
    private var activeGatt: BluetoothGatt? = null
    private var finderTone: ToneGenerator? = null

    private var permissionsGranted by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var foundDevices by mutableStateOf<List<ZebraDevice>>(emptyList())
    private var commandStatus by mutableStateOf("")
    private var localDeviceName by mutableStateOf("Unbekannt")

    private var isGlUnlocked by mutableStateOf(false)
    private var pinError by mutableStateOf("")

    private val rssiHistory = mutableMapOf<String, ArrayDeque<Int>>()

    private val glAutoLockRunnable = Runnable {
        isGlUnlocked = false
        pinError = ""
        stopBleScan()
        foundDevices = emptyList()
        commandStatus = ""
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            permissionsGranted = hasRequiredPermissions()
            if (permissionsGranted) {
                localDeviceName = readLocalDeviceName()
                startFinderService()
            }
        }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { processScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                commandStatus = "Scan-Fehler: $errorCode"
            }
        }

    @SuppressLint("MissingPermission")
    private val gattClientCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        commandStatus = "Verbunden – suche Dienst..."
                    }

                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        runOnUiThread {
                            commandStatus = "Bluetooth-Berechtigung fehlt"
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    if (activeGatt == gatt) {
                        activeGatt = null
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    runOnUiThread {
                        commandStatus = "Service-Suche fehlgeschlagen"
                    }
                    gatt.disconnect()
                    return
                }

                val service = gatt.getService(ZEBRA_SERVICE_UUID)
                val characteristic =
                    service?.getCharacteristic(ZEBRA_COMMAND_UUID)

                if (service == null || characteristic == null) {
                    runOnUiThread {
                        commandStatus = "ZebraFinder-Dienst nicht gefunden"
                    }
                    gatt.disconnect()
                    return
                }

                sendRingCommand(gatt, characteristic)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                runOnUiThread {
                    commandStatus =
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            "RING gesendet ✓"
                        } else {
                            "RING Fehler: $status"
                        }
                }

                handler.postDelayed(
                    {
                        try {
                            gatt.disconnect()
                        } catch (_: SecurityException) {
                        }
                    },
                    500
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        permissionsGranted = hasRequiredPermissions()
        localDeviceName = readLocalDeviceName()

        setContent {
            ZebraFinderTheme {
                if (isGlUnlocked) {
                    MainScreen(
                        localDeviceName = localDeviceName,
                        permissionsGranted = permissionsGranted,
                        isScanning = isScanning,
                        devices = foundDevices,
                        commandStatus = commandStatus,
                        onRequestPermissions = { requestRequiredPermissions() },
                        onStartScan = { startBleScan(autoStop = true, clearResults = true) },
                        onStopScan = { stopBleScan() },
                        onRingDevice = { ringDevice(it) },
                        onSetDeviceName = { setDeviceName(it) },
                        onRadarPulse = { playFinderTick() },
                        onRadarStateChange = { active ->
                            if (active) {
                                startBleScan(autoStop = false, clearResults = false)
                            } else {
                                stopBleScan()
                            }
                        }
                    )
                } else {
                    GlPinScreen(
                        errorMessage = pinError,
                        onPinSubmit = { unlockGlAccess(it) }
                    )
                }
            }
        }

        if (permissionsGranted) {
            startFinderService()
        }
    }

    private fun unlockGlAccess(pin: String) {
        if (pin == GL_PIN) {
            isGlUnlocked = true
            pinError = ""
            handler.removeCallbacks(glAutoLockRunnable)
            handler.postDelayed(glAutoLockRunnable, GL_UNLOCK_DURATION_MS)
        } else {
            isGlUnlocked = false
            pinError = "Falsche PIN"
        }
    }

    private fun startFinderService(action: String? = null) {
        val intent = Intent(this, ZebraFinderService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    @SuppressLint("MissingPermission")
    private fun readLocalDeviceName(): String {
        val storedFallback =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_MANUAL_DEVICE_NAME, null)
                ?.trim()
                .orEmpty()

        return try {
            val adapterName = bluetoothAdapter.name?.trim().orEmpty()
            when {
                adapterName.isNotBlank() &&
                        !adapterName.equals("Unbekannt", true) -> adapterName
                storedFallback.isNotBlank() -> storedFallback
                else -> "ZebraFinder"
            }
        } catch (_: SecurityException) {
            if (storedFallback.isNotBlank()) storedFallback else "ZebraFinder"
        }
    }

    @SuppressLint("MissingPermission")
    private fun setDeviceName(requestedName: String) {
        val clean = requestedName.trim().take(24)

        if (clean.isBlank()) {
            commandStatus = "Kennung darf nicht leer sein"
            return
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_MANUAL_DEVICE_NAME, clean)
            .apply()

        val changed =
            try {
                @Suppress("DEPRECATION")
                bluetoothAdapter.setName(clean)
            } catch (_: SecurityException) {
                false
            }

        localDeviceName = if (changed) clean else readLocalDeviceName()

        if (changed) {
            commandStatus = "BLE-Kennung gesetzt: $clean"
            startFinderService(ZebraFinderService.ACTION_REFRESH_ADVERTISING)
        } else {
            commandStatus =
                "Kennung gespeichert. Android hat den Bluetooth-Namen nicht geändert."
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        permissionLauncher.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(
        autoStop: Boolean,
        clearResults: Boolean
    ) {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            commandStatus = "Bluetooth ist ausgeschaltet"
            return
        }

        handler.removeCallbacks(scanTimeoutRunnable)

        if (isScanning) {
            if (autoStop) {
                handler.postDelayed(scanTimeoutRunnable, 15_000)
            }
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        if (clearResults) {
            foundDevices = emptyList()
            rssiHistory.clear()
        }
        commandStatus = ""

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(ZEBRA_SERVICE_PARCEL_UUID)
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

        isScanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)

        if (autoStop) {
            handler.postDelayed(scanTimeoutRunnable, 15_000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        handler.removeCallbacks(scanTimeoutRunnable)
        if (!isScanning) return

        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }

        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val name =
            result.scanRecord?.deviceName
                ?: try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }
                ?: "ZebraFinder ${result.device.address.takeLast(5)}"

        val history =
            rssiHistory.getOrPut(result.device.address) { ArrayDeque() }

        history.addLast(result.rssi)
        while (history.size > 5) {
            history.removeFirst()
        }

        val smoothedRssi = history.average().toInt()
        val previous =
            foundDevices.firstOrNull { it.address == result.device.address }

        val trend =
            when {
                previous == null -> SignalTrend.STABLE
                smoothedRssi >= previous.rssi + 3 -> SignalTrend.CLOSER
                smoothedRssi <= previous.rssi - 3 -> SignalTrend.FARTHER
                else -> SignalTrend.STABLE
            }

        val device =
            ZebraDevice(
                name = name,
                address = result.device.address,
                rssi = smoothedRssi,
                trend = trend,
                bluetoothDevice = result.device
            )

        foundDevices =
            foundDevices
                .filterNot { it.address == device.address }
                .plus(device)
                .sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    private fun ringDevice(device: ZebraDevice) {
        val bluetoothDevice = device.bluetoothDevice

        stopBleScan()
        commandStatus = "Verbinde mit ${device.name}..."

        try {
            activeGatt?.close()

            activeGatt =
                bluetoothDevice.connectGatt(
                    this,
                    false,
                    gattClientCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
        } catch (_: SecurityException) {
            commandStatus = "Bluetooth-Verbindung nicht erlaubt"
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendRingCommand(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val command = "RING".toByteArray(Charsets.UTF_8)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result =
                    gatt.writeCharacteristic(
                        characteristic,
                        command,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )

                runOnUiThread {
                    commandStatus =
                        if (result == BluetoothStatusCodes.SUCCESS) {
                            "RING wird gesendet..."
                        } else {
                            "RING konnte nicht gesendet werden"
                        }
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = command
                @Suppress("DEPRECATION")
                characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                @Suppress("DEPRECATION")
                val started = gatt.writeCharacteristic(characteristic)

                runOnUiThread {
                    commandStatus =
                        if (started) {
                            "RING wird gesendet..."
                        } else {
                            "RING konnte nicht gesendet werden"
                        }
                }
            }
        } catch (_: SecurityException) {
            runOnUiThread {
                commandStatus = "Bluetooth-Berechtigung fehlt"
            }
        }
    }

    private fun playFinderTick() {
        try {
            val audioManager = getSystemService(AudioManager::class.java)
            val stream = AudioManager.STREAM_ALARM
            val oldVolume = audioManager.getStreamVolume(stream)
            val maxVolume = audioManager.getStreamMaxVolume(stream)

            // Radar-Tick soll auch auf Geräten mit sehr leisem Alarmkanal
            // hörbar sein, aber deutlich leiser bleiben als der RING-Alarm.
            val radarVolume = maxOf(oldVolume, maxOf(1, maxVolume / 2))

            if (oldVolume < radarVolume) {
                audioManager.setStreamVolume(stream, radarVolume, 0)
            }

            finderTone?.release()
            finderTone = ToneGenerator(stream, 75)
            finderTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

            // Ursprüngliche Alarm-Lautstärke direkt nach dem kurzen Tick
            // wiederherstellen. Der schnellste Radar-Puls liegt deutlich
            // über diesen 140 ms.
            handler.postDelayed({
                try {
                    if (oldVolume < radarVolume) {
                        audioManager.setStreamVolume(stream, oldVolume, 0)
                    }
                } catch (_: Exception) {
                }
            }, 140)

        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(glAutoLockRunnable)
        stopBleScan()

        try {
            activeGatt?.close()
        } catch (_: SecurityException) {
        }

        activeGatt = null
        finderTone?.release()
        finderTone = null

        super.onDestroy()
    }
}

@Composable
fun GlPinScreen(
    errorMessage: String,
    onPinSubmit: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ZebraFinder",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Text(
                text = "GL-Zugriff",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Bitte GL-PIN eingeben.",
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { value ->
                    pin = value.filter { it.isDigit() }.take(6)
                },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage.isNotBlank()) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Button(
                onClick = {
                    onPinSubmit(pin)
                    pin = ""
                },
                enabled = pin.length == 6,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Freischalten")
            }

            Text(
                text = "Nach der Anmeldung ist der Finder 5 Minuten freigeschaltet.",
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MainScreen(
    localDeviceName: String,
    permissionsGranted: Boolean,
    isScanning: Boolean,
    devices: List<ZebraDevice>,
    commandStatus: String,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onRingDevice: (ZebraDevice) -> Unit,
    onSetDeviceName: (String) -> Unit,
    onRadarPulse: () -> Unit,
    onRadarStateChange: (Boolean) -> Unit
) {
    var selectedRadarAddress by remember { mutableStateOf<String?>(null) }
    var showNameEditor by remember { mutableStateOf(false) }
    var nameInput by remember(localDeviceName) { mutableStateOf(localDeviceName) }
    var soundEnabled by remember { mutableStateOf(true) }

    val selectedDevice =
        devices.firstOrNull { it.address == selectedRadarAddress }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "ZebraFinder",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                text = "Dieses Gerät",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = localDeviceName,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )

            TextButton(
                onClick = {
                    showNameEditor = !showNameEditor
                    nameInput = localDeviceName
                }
            ) {
                Text(if (showNameEditor) "Kennung schließen" else "Kennung ändern")
            }
        }

        if (showNameEditor) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(24) },
                        label = { Text("BLE-Gerätekennung") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            onSetDeviceName(nameInput)
                            showNameEditor = false
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Kennung übernehmen")
                    }
                }
            }
        }

        item {
            Text(
                text =
                    if (permissionsGranted) {
                        "Finder-Dienst: bereit"
                    } else {
                        "Bluetooth-Berechtigungen fehlen"
                    },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!permissionsGranted) {
            item {
                Button(onClick = onRequestPermissions) {
                    Text("Berechtigungen anfordern")
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = onStartScan,
                        enabled = !isScanning
                    ) {
                        Text(
                            if (isScanning) "Suche läuft..." else "Zebras suchen"
                        )
                    }

                    if (isScanning) {
                        Spacer(modifier = Modifier.size(8.dp))
                        OutlinedButton(onClick = onStopScan) {
                            Text("Stop")
                        }
                    }
                }
            }

            if (commandStatus.isNotBlank()) {
                item {
                    Text(
                        text = commandStatus,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (selectedDevice != null) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    RadarFinder(
                        device = selectedDevice,
                        soundEnabled = soundEnabled,
                        onSoundEnabledChange = { soundEnabled = it },
                        onPulse = onRadarPulse
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
            }

            item {
                Text(
                    text = "Gefundene Zebras: ${devices.size}",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            items(
                items = devices,
                key = { it.address }
            ) { device ->
                ZebraDeviceRow(
                    device = device,
                    radarSelected = selectedRadarAddress == device.address,
                    onRadar = {
                        val opening = selectedRadarAddress != device.address
                        selectedRadarAddress = if (opening) device.address else null
                        onRadarStateChange(opening)
                    },
                    onRing = { onRingDevice(device) }
                )
            }
        }
    }
}

@Composable
fun ZebraDeviceRow(
    device: ZebraDevice,
    radarSelected: Boolean,
    onRadar: () -> Unit,
    onRing: () -> Unit
) {
    val band = distanceBandForRssi(device.rssi)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = device.name,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "${band.proximity} · ${band.label}",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "${device.rssi} dBm · ${trendLabel(device.trend)}",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "BLE: ${device.address}",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(onClick = onRadar) {
                Text(if (radarSelected) "Radar schließen" else "Radar")
            }

            Spacer(modifier = Modifier.size(8.dp))

            Button(
                onClick = onRing
            ) {
                Text("PIEPSEN")
            }
        }
    }
}

@Composable
fun RadarFinder(
    device: ZebraDevice,
    soundEnabled: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onPulse: () -> Unit
) {
    val band = distanceBandForRssi(device.rssi)
    val transition = rememberInfiniteTransition(label = "radar")

    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(
                    durationMillis = band.pulseMs,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
        label = "radarPulse"
    )

    LaunchedEffect(band.pulseMs, soundEnabled, device.address) {
        while (true) {
            if (soundEnabled) {
                onPulse()
            }
            delay(band.pulseMs.toLong())
        }
    }

    val ringColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = device.name,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = band.label,
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "${device.rssi} dBm · ${trendLabel(device.trend)}",
            style = MaterialTheme.typography.bodyLarge
        )

        Canvas(
            modifier =
                Modifier
                    .padding(vertical = 12.dp)
                    .size(210.dp)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            listOf(0.28f, 0.52f, 0.76f, 1.0f).forEach { fraction ->
                drawCircle(
                    color = ringColor.copy(alpha = 0.22f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = 2f)
                )
            }

            for (index in 0..2) {
                val phase = (pulse + index / 3f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - phase) * 0.65f),
                    radius = maxRadius * (0.12f + phase * 0.88f),
                    center = center,
                    style = Stroke(width = 5f)
                )
            }

            val normalized =
                ((device.rssi + 90f) / 50f).coerceIn(0f, 1f)
            val targetRadius =
                maxRadius * (0.88f - normalized * 0.70f)

            drawCircle(
                color = ringColor,
                radius = 10f,
                center = Offset(center.x, center.y - targetRadius)
            )

            drawCircle(
                color = ringColor,
                radius = 7f,
                center = center
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("Piepser")
            Spacer(modifier = Modifier.size(8.dp))
            Switch(
                checked = soundEnabled,
                onCheckedChange = onSoundEnabledChange
            )
        }

        Text(
            text = "Radar zeigt Nähe, nicht Richtung.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

fun trendLabel(trend: SignalTrend): String =
    when (trend) {
        SignalTrend.CLOSER -> "↑ NÄHER"
        SignalTrend.FARTHER -> "↓ WEITER"
        SignalTrend.STABLE -> "≈ GLEICH"
    }
