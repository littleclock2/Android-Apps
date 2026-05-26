import android.Manifest
import android.R
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleManagerCallbacks
import no.nordicsemi.android.ble.data.Data
import java.util.UUID

class MainActivity : AppCompatActivity(), BleManagerCallbacks {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleManager: BleManager? = null
    private var connectedDevice: BluetoothDevice? = null

    private var waveChart: LineChart? = null
    private var thdValue: TextView? = null
    private var freqValue: TextView? = null
    private val harmonicValues = arrayOfNulls<TextView>(5)
    private lateinit var waveDataSet: LineDataSet

    companion object {
        private const val REQUEST_PERMISSIONS = 2
        private val SERVICE_UUID: UUID? = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化蓝牙适配器
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 初始化UI组件
        initViews()

        // 检查并请求权限
        checkPermissions()

        // 初始化BLE管理器
        bleManager = object : BleManager(this) {
            fun isRequiredServiceSupported(data: Data): Boolean {
                return true
            }

            fun onServicesInvalidated() {
                // 服务无效时的处理
            }
        }
        bleManager?.setGattCallbacks(this)
    }

    private fun initViews() {
        waveChart = findViewById(R.id.wave_chart)
        thdValue = findViewById(R.id.thd_value)
        freqValue = findViewById(R.id.freq_value)

        val harmonicIds = intArrayOf(R.id.harmonic1, R.id.harmonic2, R.id.harmonic3, R.id.harmonic4, R.id.harmonic5)
        for (i in harmonicIds.indices) {
            harmonicValues[i] = findViewById(harmonicIds[i])
        }

        val scanBtn: Button = findViewById(R.id.scan_btn)
        scanBtn.setOnClickListener { startDeviceScan() }

        setupChart()
    }

    private fun setupChart() {
        waveChart?.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            setDragEnabled(true)
            setScaleEnabled(true)
            setPinchZoom(true)

            val xAxis = xAxis
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
        }

        waveDataSet = LineDataSet(null, "Waveform").apply {
            color = resources.getColor(R.color.colorPrimary)
            lineWidth = 2f
            setDrawCircles(false)
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val permissionsToRequest = ArrayList<String>()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(arrayOfNulls<String>(0)), REQUEST_PERMISSIONS)
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "权限被拒绝: ${permissions[i]}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startDeviceScan() {
        Toast.makeText(this, "Starting device scan...", Toast.LENGTH_SHORT).show()
    }

    fun connectToDevice(device: BluetoothDevice?) {
        if (device == null) {
            Toast.makeText(this, "设备为空，无法连接", Toast.LENGTH_SHORT).show()
            return
        }

        if (bleManager?.isConnected == true) {
            bleManager?.disconnect()
        }
        connectedDevice = device
        bleManager?.connect(device)
            ?.retry(3, 100)
            ?.useAutoConnect(false)
            ?.enqueue()
    }

    private fun updateWaveChart(waveData: FloatArray) {
        val entries = ArrayList<Entry>()
        for (i in waveData.indices) {
            entries.add(Entry(i.toFloat(), waveData[i]))
        }

        waveDataSet.values = entries
        val lineData = LineData(waveDataSet)
        waveChart?.data = lineData
        waveChart?.invalidate()
    }

    private fun updateHarmonicDisplay(harmonics: FloatArray) {
        val maxAmplitude = harmonics[0].takeIf { it != 0f } ?: 1f

        for (i in 0..4) {
            val normalized = harmonics[i] / maxAmplitude
            harmonicValues[i]?.text = "H${i + 1}: %.2f".format(normalized)
        }
    }

    // BLE 回调方法（保持原样）
    fun onDeviceConnecting(device: BluetoothDevice) {
        Toast.makeText(this, "Connecting to ${device.name}", Toast.LENGTH_SHORT).show()
    }

    fun onDeviceConnected(device: BluetoothDevice) {
        Toast.makeText(this, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
    }

    fun onDeviceDisconnecting(device: BluetoothDevice) {
        Toast.makeText(this, "Disconnecting...", Toast.LENGTH_SHORT).show()
    }

    fun onDeviceDisconnected(device: BluetoothDevice) {
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
    }

    fun onLinkLossOccurred(device: BluetoothDevice) {
        Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show()
    }

    fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {}

    fun onBondingRequired(device: BluetoothDevice) {}

    fun onBonded(device: BluetoothDevice) {}

    fun onBondingFailed(device: BluetoothDevice) {}

    fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
        Toast.makeText(this, "Error: $message", Toast.LENGTH_SHORT).show()
    }

    fun onDeviceNotSupported(device: BluetoothDevice) {
        Toast.makeText(this, "Device not supported", Toast.LENGTH_SHORT).show()
    }

    fun onDataReceived(device: BluetoothDevice, data: Data) {
        try {
            val packet = SensorDataPacket.fromBytes(data.value)

            runOnUiThread {
                thdValue?.text = "THD: %.2f%%".format(packet.thd)
                freqValue?.text = "Frequency: %.2f Hz".format(packet.freq)

                updateWaveChart(packet.wave)
                updateHarmonicDisplay(packet.harmonics)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "数据解析失败，请检查包格式", Toast.LENGTH_SHORT).show()
        }
    }

    fun onDataSent(device: BluetoothDevice, data: Data) {}

    fun onDeviceReady(device: BluetoothDevice) {}

    fun onDestroy() {
        super.onDestroy()
        bleManager?.apply {
            disconnect()
            close()
        }
    }
}

interface AppCompatActivity {

}
