package com.frankfengnz.bleclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.frankfengnz.bleclient.ui.theme.BLEClientTheme

class MainActivity : ComponentActivity() {
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Handle Bluetooth enable result
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BLEClientTheme {
                BluetoothClientApp(
                    onRequestBluetoothEnable = { enableBluetooth() },
                    onRequestPermissions = { requestPermissions() }
                )
            }
        }
    }

    private fun enableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestPermissionLauncher.launch(permissions)
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun BluetoothClientApp(
    onRequestBluetoothEnable: () -> Unit,
    onRequestPermissions: () -> Unit,
    viewModel: BluetoothViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // Determine if we should show the status card
    val showStatusCard = !(uiState.isBluetoothSupported &&
            uiState.isBluetoothEnabled &&
            uiState.hasPermissions &&
            uiState.isConnected)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Client") },
                backgroundColor = MaterialTheme.colors.primarySurface,
                contentColor = MaterialTheme.colors.onPrimary
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showStatusCard) {
                item {
                    // Status Card
                    BluetoothStatusCard(
                        uiState = uiState,
                        onRequestBluetoothEnable = onRequestBluetoothEnable,
                        onRequestPermissions = onRequestPermissions,
                        onRefreshDevices = { viewModel.loadPairedDevices() }
                    )
                }
            }

            if (uiState.hasPermissions && uiState.isBluetoothEnabled) {
                if (!uiState.isConnected) {
                    item {
                        // Device Selection
                        DeviceSelectionCard(
                            devices = uiState.pairedDevices,
                            isLoading = uiState.isLoading,
                            onDeviceSelected = { device -> viewModel.connectToDevice(device) }
                        )
                    }
                } else {
                    item {
                        // Connection Info
                        ConnectedDeviceCard(
                            device = uiState.connectedDevice,
                            onDisconnect = { viewModel.disconnect() }
                        )
                    }

                    item {
                        // Message Interface
                        MessageInterface(
                            messages = uiState.messages,
                            isLoading = uiState.isLoading,
                            onSendMessage = { message -> viewModel.sendMessage(message) },
                            onSendMessageJust = { message -> viewModel.sendMessageJust(message) },
                            onClearMessages = { viewModel.clearMessages() },
                            onConcurrencyTesting = { viewModel.startConcurrencyTest() }
                        )
                    }
                }
            }
        }

        // Error handling
        uiState.error?.let { error ->
            LaunchedEffect(error) {
                viewModel.clearError()
            }
        }
    }
}

@Composable
fun BluetoothStatusCard(
    uiState: BluetoothUiState,
    onRequestBluetoothEnable: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefreshDevices: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (uiState.isConnected)
            MaterialTheme.colors.primarySurface
        else MaterialTheme.colors.surface,
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Bluetooth Status",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            StatusRow("Bluetooth Supported", uiState.isBluetoothSupported)
            StatusRow("Bluetooth Enabled", uiState.isBluetoothEnabled)
            StatusRow("Permissions Granted", uiState.hasPermissions)
            StatusRow("Connected", uiState.isConnected)

            // Show auto-connecting status
            if (uiState.isAutoConnecting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Auto-connecting ...")
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                if (!uiState.isBluetoothEnabled) {
                    Button(
                        onClick = onRequestBluetoothEnable,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Enable Bluetooth")
                    }
                }

                if (!uiState.hasPermissions) {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Grant Permissions")
                    }
                }

                if (uiState.hasPermissions && uiState.isBluetoothEnabled) {
                    Button(onClick = onRefreshDevices) {
                        Text("Refresh Devices")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, status: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Text(
            text = if (status) "✓" else "✗",
            color = if (status) Color.Green else Color.Red,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DeviceSelectionCard(
    devices: List<android.bluetooth.BluetoothDevice>,
    isLoading: Boolean,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Paired Devices",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text("No paired devices found")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp), // Fixed height to make it scrollable
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(devices) { device ->
                        DeviceItem(
                            device = device,
                            isLoading = isLoading,
                            onSelected = { onDeviceSelected(device) }
                        )
                        if (device != devices.last()) {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: android.bluetooth.BluetoothDevice,
    isLoading: Boolean,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = try { device.name ?: "Unknown Device" } catch (_: SecurityException) { "Unknown Device" },
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        }

        Button(
            onClick = onSelected,
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
                Text("Connect")
            }
        }
    }
}

@Composable
fun ConnectedDeviceCard(
    device: android.bluetooth.BluetoothDevice?,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colors.primarySurface,
        elevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Connected to:",
                    style = MaterialTheme.typography.body2
                )
                Text(
                    text = try { device?.name ?: "Unknown Device" } catch (_: SecurityException) { "Unknown Device" },
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device?.address ?: "",
                    style = MaterialTheme.typography.body2
                )
            }

            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error
                )
            ) {
                Text("Disconnect", color = Color.White)
            }
        }
    }
}

@Composable
fun MessageInterface(
    messages: List<MessageItem>,
    isLoading: Boolean,
    onSendMessage: (String) -> Unit,
    onSendMessageJust: (String?) -> Unit,
    onClearMessages: () -> Unit,
    onConcurrencyTesting: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Messages",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold
                )

                if (messages.isNotEmpty()) {
                    TextButton(onClick = {
                        messageText = ""
                        onClearMessages()
                    }) {
                        Text("Clear")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Message Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Enter message") },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                )

                Button(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            onSendMessage(messageText)
                        }
                    },
                    enabled = true
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("Send")
                    }
                }
                /*
                Button(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            onSendMessageJust(messageText)
                        }
                    },
                    enabled = true
                ) {
                    Text("Just")
                }
                */
            }
            /*
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        onConcurrencyTesting()
                    }
                ) {
                    Text("Concurrency Testing")
                }
            }
            */
        }
    }
}

@Composable
fun MessageBubble(message: MessageItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (message.isOutgoing)
            MaterialTheme.colors.primary
        else MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Text(
            text = message.text,
            modifier = Modifier.padding(12.dp),
            color = if (message.isOutgoing)
                MaterialTheme.colors.onPrimary
            else MaterialTheme.colors.onSurface
        )
    }
}