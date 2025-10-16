package com.frankfengnz.bleclient

import android.Manifest
import android.bluetooth.*
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*
import kotlin.coroutines.resume

class BluetoothManager(private val context: Context) {
    private val tag = "BluetoothManager"
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var notifySupportsRead = false
    private var isConnected = false
    private var notificationsEnabled = false

    // Nordic UART Service UUIDs
    private val nordicUartServiceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val nordicUartRxCharacteristicUuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // server RX (WRITE)
    private val nordicUartTxCharacteristicUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // server TX (NOTIFY/READ)

    @Volatile private var pendingResponse: StringBuilder? = null

    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasBluetoothPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN
        ) else listOf(
            Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
        )
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermissions()) return emptyList()
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectToDevice(device: BluetoothDevice): Result<String> = withContext(Dispatchers.Main) {
        if (isConnected) disconnect()
        return@withContext withTimeoutOrNull(30000) {
            suspendCancellableCoroutine { cont ->
                try {
                    notificationsEnabled = false
                    bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                // Discover services (optionally could request MTU here)
                                gatt.discoverServices()
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                isConnected = false
                                if (!cont.isCompleted) cont.resume(Result.failure(Exception("GATT disconnected: $status")))
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                if (!cont.isCompleted) cont.resume(Result.failure(Exception("Service discovery failed: $status")))
                                return
                            }
                            val service = gatt.getService(nordicUartServiceUuid)
                            if (service == null) {
                                if (!cont.isCompleted) cont.resume(Result.failure(Exception("Nordic UART service not found")))
                                return
                            }
                            var w = service.getCharacteristic(nordicUartRxCharacteristicUuid)
                            var n = service.getCharacteristic(nordicUartTxCharacteristicUuid)
                            if (w == null) w = service.characteristics.firstOrNull { c ->
                                val p = c.properties
                                (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                        (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            }
                            if (n == null) n = service.characteristics.firstOrNull { c ->
                                val p = c.properties
                                (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                                        (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ||
                                        (p and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                            }
                            writeCharacteristic = w
                            notifyCharacteristic = n
                            notifySupportsRead = (n?.properties ?: 0 and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                            Log.e(tag, "notifySupportsRead :$notifySupportsRead")
                            if (writeCharacteristic == null || notifyCharacteristic == null) {
                                if (!cont.isCompleted) cont.resume(Result.failure(Exception("Required characteristics not found")))
                                return
                            }
                            // Enable notifications/indications on notify char
                            val ok = gatt.setCharacteristicNotification(notifyCharacteristic, true)
                            val cccd = notifyCharacteristic?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (cccd != null) {
                                val props = notifyCharacteristic!!.properties
                                val enable = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val status = gatt.writeDescriptor(cccd, enable)
                                    Log.d(tag, "writeDescriptor(33+) status=$status ok=$ok")
                                } else {
                                    @Suppress("DEPRECATION")
                                    run {
                                        @Suppress("DEPRECATION") cccd.value = enable
                                        @Suppress("DEPRECATION") val wrote = gatt.writeDescriptor(cccd)
                                        Log.d(tag, "writeDescriptor(<33) wrote=$wrote ok=$ok")
                                    }
                                }
                            } else {
                                // If no CCCD, proceed but warn
                                notificationsEnabled = true
                                isConnected = true
                                if (!cont.isCompleted) cont.resume(Result.success("Connected via BLE (no CCCD)"))
                            }
                        }

                        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                            Log.d(tag, "onDescriptorWrite: ${descriptor.uuid}, status=$status")
                            if (descriptor.uuid.toString().lowercase() == "00002902-0000-1000-8000-00805f9b34fb") {
                                notificationsEnabled = (status == BluetoothGatt.GATT_SUCCESS)
                                isConnected = notificationsEnabled
                                if (!gatt.services.isNullOrEmpty() && !isConnected && notifySupportsRead) {
                                    // Still allow read-only flows
                                    isConnected = true
                                }
                                if (!cont.isCompleted) {
                                    if (isConnected) cont.resume(Result.success("Connected via BLE"))
                                    else cont.resume(Result.failure(Exception("Failed to enable notifications")))
                                }
                            }
                        }

                        // Pre-33 notifications
                        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                            if (characteristic.uuid == notifyCharacteristic?.uuid) {
                                val data = characteristic.value ?: return
                                val part = String(data)
                                Log.d(tag, "onCharacteristicChanged0: $part")
                                // (pendingResponse ?: StringBuilder().also { pendingResponse = it }).append(part)
                            }
                        }

                        // 33+ notifications
                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                            if (characteristic.uuid == notifyCharacteristic?.uuid) {
                                val part = String(value)
                                Log.d(tag, "onCharacteristicChanged1: $part")
                                // (pendingResponse ?: StringBuilder().also { pendingResponse = it }).append(part)
                            }
                        }

                        // Pre-33 read callback
                        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == notifyCharacteristic?.uuid) {
                                val data = characteristic.value ?: return
                                val part = String(data)
                                Log.d(tag, "onCharacteristicRead0: $part")
                                (pendingResponse ?: StringBuilder().also { pendingResponse = it }).append(part)
                            }
                        }

                        // 33+ read callback
                        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == notifyCharacteristic?.uuid) {
                                val part = String(value)
                                Log.d(tag, "onCharacteristicRead1: $part")
                                (pendingResponse ?: StringBuilder().also { pendingResponse = it }).append(part)
                            }
                        }

                        override fun onCharacteristicWrite(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int
                        ) {
                            //super.onCharacteristicWrite(gatt, characteristic, status)
                            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == writeCharacteristic?.uuid) {
                                Log.i(tag, "onCharacteristicWrite: success")
                                if (notifySupportsRead) {
                                    while (true) {
                                        val readIssued =
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                bluetoothGatt!!.readCharacteristic(
                                                    notifyCharacteristic!!
                                                )
                                            } else {
                                                @Suppress("DEPRECATION")
                                                bluetoothGatt!!.readCharacteristic(
                                                    notifyCharacteristic
                                                )
                                            }
                                        if (readIssued) {
                                            //Log.e(tag, "Failed to read to characteristic")
                                            break
                                        }
                                        Thread.sleep(10)
                                    }
                                }
                            } else {
                                Log.e(tag, "onCharacteristicWrite: failed $status")
                            }
                        }
                    })
                } catch (e: SecurityException) {
                    cont.resume(Result.failure(Exception("Permission denied: ${e.message}")))
                }
            }
        } ?: Result.failure(Exception("Connection timeout"))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendMessageJust(message: String): Result<String> {
        try {
            if (!isConnected || bluetoothGatt == null || writeCharacteristic == null || notifyCharacteristic == null) {
                return Result.failure(Exception("Not connected to BLE device"))
            }
            val props = writeCharacteristic!!.properties
            val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val payload = message.toByteArray()
            do {
                val wroteOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = bluetoothGatt!!.writeCharacteristic(
                        writeCharacteristic!!,
                        payload,
                        writeType
                    )
                    status == BluetoothStatusCodes.SUCCESS
                } else {
                    writeCharacteristic!!.writeType = writeType
                    @Suppress("DEPRECATION")
                    run { writeCharacteristic!!.value = payload }
                    @Suppress("DEPRECATION")
                    bluetoothGatt!!.writeCharacteristic(writeCharacteristic)
                }
                if (wroteOk) {
                    //Log.e(tag, "Failed to write to characteristic")
                    break
                }
                delay(100)
            } while (true)
            Log.i(tag, "Write to characteristic done: $message")
        } catch (e: Exception) {
            Log.e(tag, "BLE Send failed: ${e.message}")
            return Result.failure(Exception("BLE Send failed: ${e.message}"))
        }
        return Result.success("Success")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendMessage(message: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || bluetoothGatt == null || writeCharacteristic == null || notifyCharacteristic == null) {
                return@withContext Result.failure(Exception("Not connected to BLE device"))
            }
            Log.d(tag, "Sending: $message, notificationsEnabled: $notificationsEnabled")
            if (!notificationsEnabled) {
                // Some servers are read-only; allow fallback
                delay(150)
            }
            pendingResponse = StringBuilder()
            val props = writeCharacteristic!!.properties
            val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val payload = message.toByteArray()
            val wroteOk: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = bluetoothGatt!!.writeCharacteristic(writeCharacteristic!!, payload, writeType)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    writeCharacteristic!!.writeType = writeType
                    @Suppress("DEPRECATION")
                    run { writeCharacteristic!!.value = payload }
                    @Suppress("DEPRECATION")
                    bluetoothGatt!!.writeCharacteristic(writeCharacteristic)
                }
            }
            if (!wroteOk) return@withContext Result.failure(Exception("Failed to write to characteristic"))

            Log.d(tag, "Pending after notify wait: ${pendingResponse?.isEmpty()}, notifySupportsRead: $notifySupportsRead")
            // If no notification yet and char supports READ, try an explicit read to trigger server onCharacteristicReadRequest
            /*
            if (notifySupportsRead) {
                val readIssued: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Log.d(tag, "Issuing readCharacteristic0")
                    bluetoothGatt!!.readCharacteristic(notifyCharacteristic!!)
                } else {
                    @Suppress("DEPRECATION")
                    Log.d(tag, "Issuing readCharacteristic1")
                    bluetoothGatt!!.readCharacteristic(notifyCharacteristic)
                }
                if (!readIssued) {
                    Log.e(tag, "Failed to read to characteristic")
                    //return@withContext Result.failure(Exception("Failed to read to characteristic"))
                }
            }
            */
            var waitedRead = 0
            val maxWaitRead = 120_000 // allow long waits for some devices
            while (waitedRead < maxWaitRead) {
                delay(100)
                if (pendingResponse?.isNotEmpty() == true) break
                waitedRead += 100
            }

            val resp = pendingResponse?.toString()?.takeIf { it.isNotEmpty() }
                ?: "No response received (${maxWaitRead}ms timeout)"
            Result.success(resp)
        } catch (e: Exception) {
            Result.failure(Exception("BLE Send failed: ${e.message}"))
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        try {
            isConnected = false
            notificationsEnabled = false
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        } finally {
            bluetoothGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
            pendingResponse = null
        }
    }

    fun getConnectionStatus(): Boolean = isConnected
}
