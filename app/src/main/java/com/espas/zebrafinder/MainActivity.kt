package com.espas.zebrafinder

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.espas.zebrafinder.ui.theme.ZebraFinderTheme
import java.util.UUID


// ============================================================
// ZebraFinder UUIDs
// ============================================================

private val ZEBRA_SERVICE_UUID =
    UUID.fromString(
        "7d9f0001-6f65-4a3e-9f23-2e4d8f3c0001"
    )

private val ZEBRA_COMMAND_UUID =
    UUID.fromString(
        "7d9f0002-6f65-4a3e-9f23-2e4d8f3c0001"
    )

private val ZEBRA_SERVICE_PARCEL_UUID =
    ParcelUuid(ZEBRA_SERVICE_UUID)


// ============================================================
// Gefundenes Zebra
// ============================================================

data class ZebraDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bluetoothDevice: BluetoothDevice
)


// ============================================================
// MainActivity
// ============================================================

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothManager:
            BluetoothManager

    private lateinit var bluetoothAdapter:
            android.bluetooth.BluetoothAdapter

    private val handler =
        Handler(Looper.getMainLooper())

    private var activeGatt:
            BluetoothGatt? = null


    // --------------------------------------------------------
    // UI Status
    // --------------------------------------------------------

    private var permissionsGranted by
    mutableStateOf(false)

    private var isScanning by
    mutableStateOf(false)

    private var foundDevices by
    mutableStateOf<List<ZebraDevice>>(
        emptyList()
    )

    private var commandStatus by
    mutableStateOf("")


    // ========================================================
    // Permissions
    // ========================================================

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) {

            permissionsGranted =
                hasRequiredPermissions()

            if (permissionsGranted) {

                startFinderService()
            }
        }


    // ========================================================
    // Scan Callback
    // ========================================================

    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                processScanResult(result)
            }


            override fun onBatchScanResults(
                results: MutableList<ScanResult>
            ) {

                results.forEach {
                    processScanResult(it)
                }
            }


            override fun onScanFailed(
                errorCode: Int
            ) {

                isScanning = false

                commandStatus =
                    "Scan-Fehler: $errorCode"
            }
        }


    // ========================================================
    // GATT Client
    // ========================================================

    @SuppressLint("MissingPermission")
    private val gattClientCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                if (
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    runOnUiThread {

                        commandStatus =
                            "Verbunden – suche Dienst..."
                    }

                    try {

                        gatt.discoverServices()

                    } catch (
                        _: SecurityException
                    ) {

                        runOnUiThread {

                            commandStatus =
                                "Bluetooth-Berechtigung fehlt"
                        }
                    }

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    gatt.close()

                    if (
                        activeGatt == gatt
                    ) {
                        activeGatt = null
                    }
                }
            }


            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    runOnUiThread {

                        commandStatus =
                            "Service-Suche fehlgeschlagen"
                    }

                    gatt.disconnect()
                    return
                }


                val service =
                    gatt.getService(
                        ZEBRA_SERVICE_UUID
                    )


                val characteristic =
                    service
                        ?.getCharacteristic(
                            ZEBRA_COMMAND_UUID
                        )


                if (
                    service == null ||
                    characteristic == null
                ) {

                    runOnUiThread {

                        commandStatus =
                            "ZebraFinder-Dienst nicht gefunden"
                    }

                    gatt.disconnect()
                    return
                }


                sendRingCommand(
                    gatt,
                    characteristic
                )
            }


            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic,
                status: Int
            ) {

                runOnUiThread {

                    commandStatus =
                        if (
                            status ==
                            BluetoothGatt.GATT_SUCCESS
                        ) {

                            "RING gesendet ✓"

                        } else {

                            "RING Fehler: $status"
                        }
                }


                handler.postDelayed(
                    {

                        try {

                            gatt.disconnect()

                        } catch (
                            _: SecurityException
                        ) {
                        }

                    },
                    500
                )
            }
        }


    // ========================================================
    // Activity Start
    // ========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        bluetoothManager =
            getSystemService(
                BluetoothManager::class.java
            )


        bluetoothAdapter =
            bluetoothManager.adapter


        permissionsGranted =
            hasRequiredPermissions()


        setContent {

            ZebraFinderTheme {

                MainScreen(

                    localDeviceName =
                        getLocalDeviceName(),

                    permissionsGranted =
                        permissionsGranted,

                    isScanning =
                        isScanning,

                    devices =
                        foundDevices,

                    commandStatus =
                        commandStatus,

                    onRequestPermissions = {
                        requestRequiredPermissions()
                    },

                    onStartScan = {
                        startBleScan()
                    },

                    onStopScan = {
                        stopBleScan()
                    },

                    onRingDevice = { device ->

                        ringDevice(device)
                    }
                )
            }
        }


        if (
            permissionsGranted
        ) {

            startFinderService()
        }
    }


    // ========================================================
    // Foreground Service starten
    // ========================================================

    private fun startFinderService() {

        val intent =
            Intent(
                this,
                ZebraFinderService::class.java
            )

        ContextCompat.startForegroundService(
            this,
            intent
        )
    }


    // ========================================================
    // Gerätename
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun getLocalDeviceName():
            String {

        return try {

            bluetoothAdapter.name
                ?: "Unbekannt"

        } catch (
            _: SecurityException
        ) {

            "Unbekannt"
        }
    }


    // ========================================================
    // Permissions prüfen
    // ========================================================

    private fun hasRequiredPermissions():
            Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            checkSelfPermission(
                Manifest.permission
                    .BLUETOOTH_SCAN
            ) ==
                    android.content.pm
                        .PackageManager
                        .PERMISSION_GRANTED &&

                    checkSelfPermission(
                        Manifest.permission
                            .BLUETOOTH_CONNECT
                    ) ==
                    android.content.pm
                        .PackageManager
                        .PERMISSION_GRANTED &&

                    checkSelfPermission(
                        Manifest.permission
                            .BLUETOOTH_ADVERTISE
                    ) ==
                    android.content.pm
                        .PackageManager
                        .PERMISSION_GRANTED

        } else {

            checkSelfPermission(
                Manifest.permission
                    .ACCESS_FINE_LOCATION
            ) ==
                    android.content.pm
                        .PackageManager
                        .PERMISSION_GRANTED
        }
    }


    private fun requestRequiredPermissions() {

        val permissions =

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                arrayOf(
                    Manifest.permission
                        .BLUETOOTH_SCAN,

                    Manifest.permission
                        .BLUETOOTH_CONNECT,

                    Manifest.permission
                        .BLUETOOTH_ADVERTISE
                )

            } else {

                arrayOf(
                    Manifest.permission
                        .ACCESS_FINE_LOCATION
                )
            }


        permissionLauncher.launch(
            permissions
        )
    }


    // ========================================================
    // BLE Scan
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun startBleScan() {

        if (!hasRequiredPermissions()) {

            requestRequiredPermissions()
            return
        }


        if (!bluetoothAdapter.isEnabled) {

            commandStatus =
                "Bluetooth ist ausgeschaltet"

            return
        }


        val scanner =
            bluetoothAdapter
                .bluetoothLeScanner
                ?: return


        foundDevices =
            emptyList()


        commandStatus =
            ""


        val filter =
            ScanFilter.Builder()

                .setServiceUuid(
                    ZEBRA_SERVICE_PARCEL_UUID
                )

                .build()


        val settings =
            ScanSettings.Builder()

                .setScanMode(
                    ScanSettings
                        .SCAN_MODE_LOW_LATENCY
                )

                .build()


        isScanning = true


        scanner.startScan(

            listOf(filter),

            settings,

            scanCallback
        )


        handler.postDelayed(
            {

                stopBleScan()

            },
            15_000
        )
    }


    @SuppressLint("MissingPermission")
    private fun stopBleScan() {

        if (!isScanning) {
            return
        }


        try {

            bluetoothAdapter
                .bluetoothLeScanner
                ?.stopScan(
                    scanCallback
                )

        } catch (
            _: SecurityException
        ) {
        }


        isScanning = false
    }


    // ========================================================
    // Scan Result
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun processScanResult(
        result: ScanResult
    ) {

        val name =
            result.scanRecord
                ?.deviceName
                ?: try {

                    result.device.name

                } catch (
                    _: SecurityException
                ) {

                    null
                }
                ?: "ZebraFinder"


        val localName =
            getLocalDeviceName()


        // eigenes Gerät nicht anzeigen
        if (
            name ==
            localName
        ) {
            return
        }


        val device =
            ZebraDevice(

                name =
                    name,

                address =
                    result.device.address,

                rssi =
                    result.rssi,

                bluetoothDevice =
                    result.device
            )


        foundDevices =
            foundDevices

                .filterNot {

                    it.address ==
                            device.address
                }

                .plus(device)

                .sortedByDescending {

                    it.rssi
                }
    }


    // ========================================================
    // Ring-Verbindung
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun ringDevice(
        device: ZebraDevice
    ) {

        stopBleScan()


        commandStatus =
            "Verbinde mit ${device.name}..."


        try {

            activeGatt?.close()


            activeGatt =
                device
                    .bluetoothDevice
                    .connectGatt(

                        this,

                        false,

                        gattClientCallback,

                        BluetoothDevice
                            .TRANSPORT_LE
                    )

        } catch (
            _: SecurityException
        ) {

            commandStatus =
                "Bluetooth-Verbindung nicht erlaubt"
        }
    }


    // ========================================================
    // RING senden
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun sendRingCommand(
        gatt: BluetoothGatt,
        characteristic:
        BluetoothGattCharacteristic
    ) {

        val command =
            "RING".toByteArray(
                Charsets.UTF_8
            )


        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                val result =
                    gatt.writeCharacteristic(
                        characteristic,
                        command,
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_DEFAULT
                    )


                runOnUiThread {

                    commandStatus =
                        if (
                            result ==
                            BluetoothStatusCodes.SUCCESS
                        ) {

                            "RING wird gesendet..."

                        } else {

                            "RING konnte nicht gesendet werden"
                        }
                }

            } else {

                @Suppress("DEPRECATION")
                characteristic.value =
                    command

                @Suppress("DEPRECATION")
                characteristic.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_DEFAULT

                @Suppress("DEPRECATION")
                val started =
                    gatt.writeCharacteristic(
                        characteristic
                    )


                runOnUiThread {

                    commandStatus =
                        if (started) {

                            "RING wird gesendet..."

                        } else {

                            "RING konnte nicht gesendet werden"
                        }
                }
            }

        } catch (
            _: SecurityException
        ) {

            runOnUiThread {

                commandStatus =
                    "Bluetooth-Berechtigung fehlt"
            }
        }
    }


    // ========================================================
    // Cleanup
    // ========================================================

    override fun onDestroy() {

        stopBleScan()


        try {

            activeGatt?.close()

        } catch (
            _: SecurityException
        ) {
        }


        activeGatt = null


        // WICHTIG:
        // ZebraFinderService wird NICHT gestoppt.
        // Er soll weiter im Hintergrund laufen.

        super.onDestroy()
    }
}


// ============================================================
// UI
// ============================================================

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

    onRingDevice:
        (ZebraDevice) -> Unit
) {

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),

        verticalArrangement =
            Arrangement.Top
    ) {


        Text(

            text =
                "ZebraFinder",

            style =
                MaterialTheme
                    .typography
                    .headlineMedium
        )


        Text(

            text =
                "Dieses Gerät: $localDeviceName",

            modifier =
                Modifier.padding(
                    top = 12.dp
                ),

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Text(

            text =
                if (
                    permissionsGranted
                ) {

                    "Finder-Dienst: bereit"

                } else {

                    "Bluetooth-Berechtigungen fehlen"
                },

            modifier =
                Modifier.padding(
                    top = 12.dp,
                    bottom = 20.dp
                )
        )


        if (
            !permissionsGranted
        ) {

            Button(

                onClick =
                    onRequestPermissions
            ) {

                Text(
                    "Berechtigungen anfordern"
                )
            }

            return@Column
        }


        // ----------------------------------------------------
        // Suche
        // ----------------------------------------------------

        Row(

            horizontalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {


            Button(

                onClick =
                    onStartScan,

                enabled =
                    !isScanning
            ) {

                Text(

                    if (
                        isScanning
                    ) {

                        "Suche läuft..."

                    } else {

                        "Zebras suchen"
                    }
                )
            }


            if (
                isScanning
            ) {

                Button(

                    onClick =
                        onStopScan
                ) {

                    Text(
                        "Stop"
                    )
                }
            }
        }


        if (
            commandStatus.isNotBlank()
        ) {

            Text(

                text =
                    commandStatus,

                modifier =
                    Modifier.padding(
                        top = 16.dp
                    ),

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge
            )
        }


        Text(

            text =
                "Gefundene Zebras: ${devices.size}",

            modifier =
                Modifier.padding(
                    top = 24.dp,
                    bottom = 8.dp
                ),

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        devices.forEach { device ->

            ZebraDeviceRow(

                device =
                    device,

                onRing = {

                    onRingDevice(
                        device
                    )
                }
            )
        }
    }
}


// ============================================================
// Gerät anzeigen
// ============================================================

@Composable
fun ZebraDeviceRow(

    device: ZebraDevice,

    onRing: () -> Unit
) {

    val proximity =
        when {

            device.rssi >= -50 ->
                "SEHR NAH"

            device.rssi >= -65 ->
                "NAH"

            device.rssi >= -75 ->
                "MITTEL"

            else ->
                "WEIT"
        }


    Column(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 12.dp
                )
    ) {


        Text(

            text =
                device.name,

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        Text(

            text =
                "$proximity   ${device.rssi} dBm",

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        Text(

            text =
                "BLE: ${device.address}",

            style =
                MaterialTheme
                    .typography
                    .bodySmall
        )


        Button(

            onClick =
                onRing,

            modifier =
                Modifier.padding(
                    top = 8.dp
                )
        ) {

            Text(
                "PIEPSEN"
            )
        }
    }
}