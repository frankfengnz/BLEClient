package com.frankfengnz.bleclient

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BluetoothUiState(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val pairedDevices: List<BluetoothDevice> = emptyList(),
    val connectedDevice: BluetoothDevice? = null,
    val isConnected: Boolean = false,
    val messages: List<MessageItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isAutoConnecting: Boolean = false
)

data class MessageItem(
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class BluetoothViewModel : ViewModel() {
    private val tag = "BluetoothViewModel"
    private var bluetoothManager: BluetoothManager? = null
    private var targetDeviceName: String? = null // "CH-900a07c4"

    private val _uiState = MutableStateFlow(BluetoothUiState())
    val uiState: StateFlow<BluetoothUiState> = _uiState.asStateFlow()

    init {
        Log.d(tag, "BluetoothViewModel created")
        Log.d(tag, "Target device for auto-connection: $targetDeviceName")
    }

    fun setTargetDeviceName(name: String) {
        Log.d(tag, "Setting target device name to: $name")
        targetDeviceName = name
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun initialize(context: Context) {
        Log.d(tag, "Initializing BluetoothViewModel with context")
        bluetoothManager = BluetoothManager(context)
        checkBluetoothStatus()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun checkBluetoothStatus() {
        Log.d(tag, "Checking Bluetooth status...")
        bluetoothManager?.let { manager ->
            val supported = manager.isBluetoothSupported()
            val enabled = manager.isBluetoothEnabled()
            val hasPermissions = manager.hasBluetoothPermissions()

            Log.d(tag, "Bluetooth status - Supported: $supported, Enabled: $enabled, Permissions: $hasPermissions")

            _uiState.value = _uiState.value.copy(
                isBluetoothSupported = supported,
                isBluetoothEnabled = enabled,
                hasPermissions = hasPermissions
            )

            if (hasPermissions && enabled) {
                Log.d(tag, "Bluetooth ready, loading paired devices and attempting auto-connection...")
                loadPairedDevicesAndAutoConnect()
            } else {
                Log.w(tag, "Bluetooth not ready - Permissions: $hasPermissions, Enabled: $enabled")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun loadPairedDevicesAndAutoConnect() {
        Log.d(tag, "Loading paired devices and attempting auto-connection...")
        bluetoothManager?.let { manager ->
            val devices = manager.getPairedDevices()
            _uiState.value = _uiState.value.copy(pairedDevices = devices)

            if (targetDeviceName == null || targetDeviceName!!.isEmpty()) {
                Log.d(tag, "No target device name specified for auto-connection, skipping")
                return
            }

            Log.d(tag, "Searching for target device '$targetDeviceName' among ${devices.size} paired devices...")

            // Auto-connect to target device if found
            val targetDevice = devices.find { device ->
                try {
                    val deviceName = device.name
                    Log.d(tag, "Checking device: $deviceName")
                    deviceName == targetDeviceName
                } catch (e: SecurityException) {
                    Log.w(tag, "SecurityException accessing device name: ${e.message}")
                    false
                }
            }

            if (targetDevice != null) {
                Log.i(tag, "🎯 Target device '$targetDeviceName' found! Starting auto-connection...")
                _uiState.value = _uiState.value.copy(isAutoConnecting = true)
                autoConnectToDevice(targetDevice)
            } else {
                Log.w(tag, "⚠️ Target device '$targetDeviceName' not found in paired devices")
                Log.d(tag, "Available devices:")
                devices.forEach { device ->
                    try {
                        Log.d(tag, "  - ${device.name} (${device.address})")
                    } catch (e: SecurityException) {
                        Log.d(tag, "  - Unknown name (${device.address})")
                    }
                }
            }
        }
    }

    fun loadPairedDevices() {
        Log.d(tag, "Manual refresh of paired devices requested")
        bluetoothManager?.let { manager ->
            val devices = manager.getPairedDevices()
            _uiState.value = _uiState.value.copy(pairedDevices = devices)
            Log.d(tag, "Paired devices list updated with ${devices.size} devices")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun autoConnectToDevice(device: BluetoothDevice) {
        Log.d(tag, "Starting auto-connection process...")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            bluetoothManager?.let { manager ->
                val result = manager.connectToDevice(device)
                result.fold(
                    onSuccess = { message ->
                        Log.i(tag, "✅ Auto-connection successful: $message")
                        _uiState.value = _uiState.value.copy(
                            connectedDevice = device,
                            isConnected = true,
                            isLoading = false,
                            isAutoConnecting = false,
                            messages = _uiState.value.messages + MessageItem("Auto-connected: $message", false)
                        )
                    },
                    onFailure = { error ->
                        Log.e(tag, "❌ Auto-connection failed: ${error.message}")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isAutoConnecting = false,
                            error = "Auto-connection failed: ${error.message}",
                            messages = _uiState.value.messages + MessageItem("Auto-connection failed: ${error.message}", false)
                        )
                    }
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(device: BluetoothDevice) {
        val deviceName = try { device.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
        Log.d(tag, "Manual connection requested to device: $deviceName (${device.address})")

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            bluetoothManager?.let { manager ->
                val result = manager.connectToDevice(device)
                result.fold(
                    onSuccess = { message ->
                        Log.i(tag, "✅ Manual connection successful: $message")
                        _uiState.value = _uiState.value.copy(
                            connectedDevice = device,
                            isConnected = true,
                            isLoading = false,
                            messages = _uiState.value.messages + MessageItem(message, false)
                        )
                    },
                    onFailure = { error ->
                        Log.e(tag, "❌ Manual connection failed: ${error.message}")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = error.message
                        )
                    }
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(message: String) {
        if (message.isBlank()) {
            Log.w(tag, "Attempted to send blank message, ignoring")
            return
        }

        Log.d(tag, "User sending message: '$message'")

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Add outgoing message to UI
            val outgoingMessage = MessageItem(message, true)
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + outgoingMessage
            )
            Log.d(tag, "Added outgoing message to UI")

            bluetoothManager?.let { manager ->
                val result = manager.sendMessage(message)
                result.fold(
                    onSuccess = { response ->
                        Log.i(tag, "✅ Message sent successfully, received response: '$response'")
                        val incomingMessage = MessageItem("Response: $response", false)
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + incomingMessage,
                            isLoading = false
                        )
                        //val ret = manager.readCharacteristicData()
                    },
                    onFailure = { error ->
                        Log.e(tag, "❌ Message send failed: ${error.message}")
                        val errorMessage = MessageItem("Error: ${error.message}", false)
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + errorMessage,
                            isLoading = false,
                            error = error.message
                        )
                    }
                )
            }
        }
    }

    val requests = listOf(
        "getsensorlist",
        "isconnected",
        "gpslocation",
        "cellular",
        "battery",
        "servicelist",
        "sw:package",
        "sys:version",
        "hardwareVersion",
        "voltage:external",
        "lastboot",
        "getBtMacAddr",
        "ignSource",
        "ignStat",
        "ecmData",
        "faultCode",
        "ecm",
        "canbus",
        "ignStat"
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startConcurrencyTest() {
        Log.d(tag, "Starting concurrency test with 10 rapid messages")
        requests.forEach { request ->
            viewModelScope.launch {
                Log.d(tag, "Sending: $request")
                bluetoothManager?.sendMessageJust(request)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessageJust(request: String?) {
        viewModelScope.launch {
            Log.d(tag, "Sending: $request")
            if (request.isNullOrEmpty()) return@launch
            bluetoothManager?.sendMessageJust(request)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        Log.d(tag, "User requested disconnect")
        bluetoothManager?.disconnect()
        _uiState.value = _uiState.value.copy(
            connectedDevice = null,
            isConnected = false
        )
        Log.d(tag, "UI state updated to disconnected")
    }

    fun clearError() {
        Log.d(tag, "Clearing error state")
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessages() {
        Log.d(tag, "Clearing message history (${_uiState.value.messages.size} messages)")
        _uiState.value = _uiState.value.copy(messages = emptyList())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        Log.d(tag, "BluetoothViewModel cleared, disconnecting...")
        bluetoothManager?.disconnect()
    }
}
