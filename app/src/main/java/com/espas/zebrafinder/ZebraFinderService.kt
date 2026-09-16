package com.espas.zebrafinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import java.util.UUID

private val SERVICE_UUID =
    UUID.fromString(
        "7d9f0001-6f65-4a3e-9f23-2e4d8f3c0001"
    )

private val COMMAND_UUID =
    UUID.fromString(
        "7d9f0002-6f65-4a3e-9f23-2e4d8f3c0001"
    )

class ZebraFinderService : Service() {

    companion object {
        private const val CHANNEL_ID =
            "zebra_finder_service"

        private const val NOTIFICATION_ID =
            1001
    }

    private lateinit var bluetoothManager:
            BluetoothManager

    private lateinit var bluetoothAdapter:
            BluetoothAdapter

    private var gattServer:
            BluetoothGattServer? = null

    private var isAdvertising =
        false

    private val handler =
        Handler(Looper.getMainLooper())

    private var activeTone:
            ToneGenerator? = null

    private var previousAlarmVolume:
            Int? = null


    // ========================================================
    // Service Lifecycle
    // ========================================================

    override fun onCreate() {
        super.onCreate()

        bluetoothManager =
            getSystemService(
                BluetoothManager::class.java
            )

        bluetoothAdapter =
            bluetoothManager.adapter

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        startFinder()
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (!isAdvertising) {
            startFinder()
        }

        // Android soll den Service nach Möglichkeit
        // wiederherstellen, falls der Prozess beendet wird.
        return START_STICKY
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }


    override fun onDestroy() {

        stopAdvertising()
        closeGattServer()
        stopAlarm()

        super.onDestroy()
    }


    // ========================================================
    // Notification
    // ========================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "ZebraFinder",
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            channel.description =
                "Hält ZebraFinder im Hintergrund aktiv"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }


    private fun createNotification():
            android.app.Notification {

        val openAppIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "ZebraFinder aktiv"
            )
            .setContentText(
                "Dieses Gerät ist per Bluetooth auffindbar"
            )
            .setSmallIcon(
                R.mipmap.ic_launcher
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(true)
            .setPriority(
                NotificationCompat
                    .PRIORITY_LOW
            )
            .build()
    }


    // ========================================================
    // Finder starten
    // ========================================================

    private fun startFinder() {

        if (!hasBluetoothPermissions()) {
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            return
        }

        if (!startGattServer()) {
            return
        }

        startAdvertising()
    }


    // ========================================================
    // Permissions
    // ========================================================

    private fun hasBluetoothPermissions():
            Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            checkSelfPermission(
                Manifest.permission
                    .BLUETOOTH_ADVERTISE
            ) ==
                    PackageManager.PERMISSION_GRANTED &&

                    checkSelfPermission(
                        Manifest.permission
                            .BLUETOOTH_CONNECT
                    ) ==
                    PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }


    // ========================================================
    // GATT Server
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun startGattServer():
            Boolean {

        if (gattServer != null) {
            return true
        }

        try {

            gattServer =
                bluetoothManager
                    .openGattServer(
                        this,
                        gattServerCallback
                    )

        } catch (
            _: SecurityException
        ) {

            return false
        }

        val server =
            gattServer
                ?: return false

        val service =
            BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService
                    .SERVICE_TYPE_PRIMARY
            )

        val commandCharacteristic =
            BluetoothGattCharacteristic(
                COMMAND_UUID,

                BluetoothGattCharacteristic
                    .PROPERTY_WRITE,

                BluetoothGattCharacteristic
                    .PERMISSION_WRITE
            )

        service.addCharacteristic(
            commandCharacteristic
        )

        return server.addService(
            service
        )
    }


    @SuppressLint("MissingPermission")
    private val gattServerCallback =
        object : BluetoothGattServerCallback() {

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic:
                BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {

                if (
                    characteristic?.uuid ==
                    COMMAND_UUID
                ) {

                    val command =
                        value
                            ?.toString(
                                Charsets.UTF_8
                            )
                            ?.trim()
                            ?: ""

                    if (command == "RING") {

                        handler.post {
                            playAlarm()
                        }
                    }
                }

                if (
                    responseNeeded &&
                    device != null
                ) {

                    try {

                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null
                        )

                    } catch (
                        _: SecurityException
                    ) {
                    }
                }
            }
        }


    private fun closeGattServer() {

        try {

            gattServer?.close()

        } catch (
            _: SecurityException
        ) {
        }

        gattServer = null
    }


    // ========================================================
    // Advertising
    // ========================================================

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {

        if (isAdvertising) {
            return
        }

        val advertiser =
            bluetoothAdapter
                .bluetoothLeAdvertiser
                ?: return

        val advertiseData =
            AdvertiseData.Builder()
                .addServiceUuid(
                    ParcelUuid(
                        SERVICE_UUID
                    )
                )
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

        val scanResponse =
            AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings
                        .ADVERTISE_MODE_LOW_LATENCY
                )
                .setTxPowerLevel(
                    AdvertiseSettings
                        .ADVERTISE_TX_POWER_HIGH
                )
                .setConnectable(true)
                .setTimeout(0)
                .build()

        try {

            advertiser.startAdvertising(
                settings,
                advertiseData,
                scanResponse,
                advertiseCallback
            )

        } catch (
            _: SecurityException
        ) {
        }
    }


    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect:
                AdvertiseSettings
            ) {

                isAdvertising = true
            }

            override fun onStartFailure(
                errorCode: Int
            ) {

                isAdvertising = false
            }
        }


    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {

        if (!isAdvertising) {
            return
        }

        try {

            bluetoothAdapter
                .bluetoothLeAdvertiser
                ?.stopAdvertising(
                    advertiseCallback
                )

        } catch (
            _: SecurityException
        ) {
        }

        isAdvertising = false
    }


    // ========================================================
    // Alarm
    // ========================================================

    private fun playAlarm() {

        stopAlarm()

        val audioManager =
            getSystemService(
                AudioManager::class.java
            )

        previousAlarmVolume =
            audioManager.getStreamVolume(
                AudioManager.STREAM_ALARM
            )

        val maxVolume =
            audioManager.getStreamMaxVolume(
                AudioManager.STREAM_ALARM
            )

        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            maxVolume,
            0
        )

        activeTone =
            ToneGenerator(
                AudioManager.STREAM_ALARM,
                100
            )

        activeTone?.startTone(
            ToneGenerator
                .TONE_CDMA_ALERT_CALL_GUARD,
            8000
        )

        handler.postDelayed(
            {
                stopAlarm()
            },
            8200
        )
    }


    private fun stopAlarm() {

        activeTone?.stopTone()
        activeTone?.release()
        activeTone = null

        val oldVolume =
            previousAlarmVolume

        if (oldVolume != null) {

            val audioManager =
                getSystemService(
                    AudioManager::class.java
                )

            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                oldVolume,
                0
            )
        }

        previousAlarmVolume = null
    }
}