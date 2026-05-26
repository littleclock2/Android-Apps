package com.example.signalfake

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.signalfake.ui.theme.SignalFakeTheme
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : ComponentActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isBluetoothEnabled by mutableStateOf(false)
    private var discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var connectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var receivedData by mutableStateOf("")
    private var messageToSend by mutableStateOf("")
    private var baudRate by mutableStateOf(9600)

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var connectedThread: ConnectedThread? = null

    private val handler = Handler(Looper.getMainLooper())

    // 所需权限
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    // 蓝牙启用请求
    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            isBluetoothEnabled = true
            startDiscovery()
        } else {
            Toast.makeText(this, "蓝牙必须开启才能使用应用", Toast.LENGTH_SHORT).show()
        }
    }

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.all { it.value }
        if (allPermissionsGranted) {
            checkBluetoothState()
        } else {
            Toast.makeText(this, "需要权限才能使用蓝牙功能", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        enableEdgeToEdge()
        setContent {
            SignalFakeTheme {
                BluetoothApp(
                    isBluetoothEnabled = isBluetoothEnabled,
                    discoveredDevices = discoveredDevices,
                    connectedDevice = connectedDevice,
                    connectionState = connectionState,
                    receivedData = receivedData,
                    messageToSend = messageToSend,
                    baudRate = baudRate,
                    onEnableBluetooth = ::enableBluetooth,
                    onRefreshDevices = ::startDiscovery,
                    onConnectDevice = ::connectToDevice,
                    onDisconnectDevice = ::disconnectDevice,
                    onSendMessage = ::sendMessage,
                    onBaudRateChange = { rate -> baudRate = rate },
                    onRequestPermissions = ::checkPermissions,
                    hasAllPermissions = hasAllPermissions()
                )
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkBluetoothState()
        }
    }

    private fun checkBluetoothState() {
        bluetoothAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                enableBluetooth()
            } else {
                isBluetoothEnabled = true
                startDiscovery()
            }
        } ?: run {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableBluetooth() {
        bluetoothAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                requestBluetooth.launch(enableBtIntent)
            } else {
                isBluetoothEnabled = true
                startDiscovery()
            }
        } ?: run {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!hasAllPermissions()) {
            checkPermissions()
            return
        }

        bluetoothAdapter?.let { adapter ->
            discoveredDevices.clear()
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasAllPermissions()) {
            checkPermissions()
            return
        }

        try {
            // 标准SPP UUID
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)

            connectionState = ConnectionState.CONNECTING
            connectedDevice = device

            // 在后台线程中执行连接
            Thread {
                try {
                    bluetoothSocket?.connect()
                    handler.post {
                        connectionState = ConnectionState.CONNECTED
                        Toast.makeText(this, "已连接到 ${device.name}", Toast.LENGTH_SHORT).show()

                        // 启动数据通信线程
                        connectedThread = ConnectedThread(bluetoothSocket!!)
                        connectedThread?.start()
                    }
                } catch (e: IOException) {
                    handler.post {
                        connectionState = ConnectionState.DISCONNECTED
                        connectedDevice = null
                        Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            connectionState = ConnectionState.DISCONNECTED
            Toast.makeText(this, "创建连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disconnectDevice() {
        try {
            connectedThread?.cancel()
            bluetoothSocket?.close()
            connectionState = ConnectionState.DISCONNECTED
            connectedDevice = null
            receivedData = ""
            Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("Bluetooth", "断开连接错误", e)
        }
    }

    private fun sendMessage(message: String) {
        if (connectionState != ConnectionState.CONNECTED) {
            Toast.makeText(this, "未连接设备", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            connectedThread?.write(message.toByteArray())
            messageToSend = ""
        } catch (e: Exception) {
            Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream = socket.inputStream
        private val outputStream: OutputStream = socket.outputStream
        private val buffer = ByteArray(1024)

        override fun run() {
            var numBytes: Int

            while (true) {
                try {
                    // 读取数据
                    numBytes = inputStream.read(buffer)
                    val receivedText = String(buffer, 0, numBytes)

                    handler.post {
                        receivedData += receivedText
                        // 限制显示的数据量，防止内存问题
                        if (receivedData.length > 10000) {
                            receivedData = receivedData.substring(receivedData.length - 5000)
                        }
                    }
                } catch (e: IOException) {
                    Log.d("Bluetooth", "输入流断开", e)
                    handler.post {
                        disconnectDevice()
                    }
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
            } catch (e: IOException) {
                Log.e("Bluetooth", "写入输出流时出错", e)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e("Bluetooth", "关闭连接socket时出错", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectDevice()
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothApp(
    isBluetoothEnabled: Boolean,
    discoveredDevices: List<BluetoothDevice>,
    connectedDevice: BluetoothDevice?,
    connectionState: ConnectionState,
    receivedData: String,
    messageToSend: String,
    baudRate: Int,
    onEnableBluetooth: () -> Unit,
    onRefreshDevices: () -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnectDevice: () -> Unit,
    onSendMessage: (String) -> Unit,
    onBaudRateChange: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
    hasAllPermissions: Boolean
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf(messageToSend) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("蓝牙调试工具") },
                actions = {
                    IconButton(
                        onClick = onRefreshDevices,
                        enabled = hasAllPermissions && isBluetoothEnabled
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新设备")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (!hasAllPermissions) {
                PermissionRequestSection(onRequestPermissions)
            } else {
                // 蓝牙状态显示
                BluetoothStatus(
                    isEnabled = isBluetoothEnabled,
                    connectedDevice = connectedDevice,
                    connectionState = connectionState,
                    onEnableBluetooth = onEnableBluetooth,
                    onDisconnectDevice = onDisconnectDevice
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 波特率设置
                BaudRateSelector(
                    baudRate = baudRate,
                    onBaudRateChange = onBaudRateChange
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 设备列表
                Text(
                    text = "可用设备",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (isBluetoothEnabled) {
                    DeviceList(
                        devices = discoveredDevices,
                        onConnectDevice = onConnectDevice,
                        connectionState = connectionState
                    )
                } else {
                    Text("请先启用蓝牙")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 数据通信区域
                if (connectionState == ConnectionState.CONNECTED) {
                    CommunicationSection(
                        receivedData = receivedData,
                        message = message,
                        onMessageChange = { message = it },
                        onSendMessage = {
                            onSendMessage(it)
                            message = ""
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRequestSection(onRequestPermissions: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "需要权限",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "此应用需要蓝牙和位置权限才能扫描和连接附近的蓝牙设备",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(onClick = onRequestPermissions) {
                Text("授予权限")
            }
        }
    }
}

@Composable
fun BluetoothStatus(
    isEnabled: Boolean,
    connectedDevice: BluetoothDevice?,
    connectionState: ConnectionState,
    onEnableBluetooth: () -> Unit,
    onDisconnectDevice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "蓝牙状态",
                    tint = when {
                        !isEnabled -> Color.Gray
                        connectionState == ConnectionState.CONNECTED -> Color.Green
                        connectionState == ConnectionState.CONNECTING -> Color.Yellow
                        else -> Color.Blue
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        !isEnabled -> "蓝牙已禁用"
                        connectionState == ConnectionState.CONNECTED -> "蓝牙已连接"
                        connectionState == ConnectionState.CONNECTING -> "正在连接..."
                        else -> "蓝牙已启用"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onEnableBluetooth) {
                    Text("启用蓝牙")
                }
            }

            connectedDevice?.let { device ->
                Spacer(modifier = Modifier.height(8.dp))

                if (ActivityCompat.checkSelfPermission(
                        this as Context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return@Column
                }
                Text("设备: ${device.name ?: "未知设备"}")

                Text("地址: ${device.address}", style = MaterialTheme.typography.bodySmall)

                if (connectionState == ConnectionState.CONNECTED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onDisconnectDevice) {
                        Text("断开连接")
                    }
                }
            }
        }
    }
}

@Composable
fun BaudRateSelector(
    baudRate: Int,
    onBaudRateChange: (Int) -> Unit
) {
    val baudRates = listOf(9600, 19200, 38400, 57600, 115200)
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("波特率设置", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            Box {
                Button(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("当前: $baudRate bps")
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "选择波特率")
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    baudRates.forEach { rate ->
                        DropdownMenuItem(
                            text = { Text("$rate bps") },
                            onClick = {
                                onBaudRateChange(rate)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceList(
    devices: List<BluetoothDevice>,
    onConnectDevice: (BluetoothDevice) -> Unit,
    connectionState: ConnectionState
) {
    if (devices.isEmpty()) {
        Text("未发现设备，请确保设备处于可发现模式")
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 200.dp)
        ) {
            items(devices) { device ->
                DeviceItem(
                    device = device,
                    onConnect = { onConnectDevice(device) },
                    isConnecting = connectionState == ConnectionState.CONNECTING
                )
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: BluetoothDevice,
    onConnect: () -> Unit,
    isConnecting: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            if (ActivityCompat.checkSelfPermission(
                    this as Context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return@Column
            }
            Text(
                text = device.name ?: "未知设备",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier.align(Alignment.End),
                enabled = !isConnecting
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("连接中...")
                } else {
                    Text("连接")
                }
            }
        }
    }
}

@Composable
fun CommunicationSection(
    receivedData: String,
    message: String,
    onMessageChange: (String) -> Unit,
    onSendMessage: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "数据通信",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 接收数据区域
        Text("接收数据:", style = MaterialTheme.typography.titleSmall)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            Text(
                text = receivedData.ifEmpty { "无接收数据" },
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize(),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 发送数据区域
        Text("发送数据:", style = MaterialTheme.typography.titleSmall)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入要发送的消息") },
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Text
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { onSendMessage(message) },
                modifier = Modifier.size(48.dp),
                enabled = message.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 快捷发送按钮
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("AT", "TEST", "PING", "HELLO").forEach { cmd ->
                Button(
                    onClick = { onSendMessage(cmd) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(cmd)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BluetoothAppPreview() {
    SignalFakeTheme {
        BluetoothApp(
            isBluetoothEnabled = true,
            discoveredDevices = listOf(),
            connectedDevice = null,
            connectionState = ConnectionState.DISCONNECTED,
            receivedData = "",
            messageToSend = "",
            baudRate = 9600,
            onEnableBluetooth = {},
            onRefreshDevices = {},
            onConnectDevice = {},
            onDisconnectDevice = {},
            onSendMessage = {},
            onBaudRateChange = {},
            onRequestPermissions = {},
            hasAllPermissions = true
        )
    }
}