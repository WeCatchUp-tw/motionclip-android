package com.example.motioncliptest

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

class UdpViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val ESP32_IP = "192.168.4.1"
        private const val ESP32_PORT = 12345
        private const val LOCAL_PORT = 12345
        private const val BUFFER_SIZE = 256
    }

    private val _latestData = MutableStateFlow<ImuData?>(null)
    val latestData: StateFlow<ImuData?> = _latestData.asStateFlow()

    private val _packetCount = MutableStateFlow(0)
    val packetCount: StateFlow<Int> = _packetCount.asStateFlow()

    private val _arrivalRate = MutableStateFlow(0)
    val arrivalRate: StateFlow<Int> = _arrivalRate.asStateFlow()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var rateJob: Job? = null
    private var demoJob: Job? = null

    // receive coroutine 與 rate timer coroutine 都會存取這個計數器，
    // 用 AtomicInteger 確保兩個執行緒同時操作時不會漏算
    private val windowPacketCount = AtomicInteger(0)

    fun startReceiving() {
        if (_isReceiving.value) return

        receiveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 固定綁 LOCAL_PORT=12345：ESP32 只把資料回傳到來源 port，
                // 若用隨機 port 送出 start，資料包會送到那個隨機 port，本地卻在聽 12345，永遠收不到
                val newSocket = DatagramSocket(LOCAL_PORT)

                // 強制綁定到 Wi-Fi 網路介面：手機同時有行動網路時，
                // 不綁定的話系統可能把 start 從行動網路送出，ESP32 在 Wi-Fi AP 上完全看不到
                val cm = getApplication<Application>()
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                wifiNetwork?.bindSocket(newSocket)

                socket = newSocket

                val startBytes = "start".toByteArray(Charsets.UTF_8)
                newSocket.send(
                    DatagramPacket(startBytes, startBytes.size, InetAddress.getByName(ESP32_IP), ESP32_PORT)
                )

                _isReceiving.value = true
                startRateTimer()

                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        // socket.receive() 是阻塞呼叫，必須在 Dispatchers.IO 執行，
                        // 500Hz 下若在主執行緒會立即 ANR
                        newSocket.receive(packet)
                        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parsePacket(raw)?.let { imuData ->
                            _latestData.value = imuData
                            _packetCount.value++
                            windowPacketCount.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        // socket 被 stopReceiving() 關閉時 receive() 會丟 SocketException，
                        // 用 break 讓 coroutine 自然退出，不往外拋
                        break
                    }
                }
            } catch (e: Exception) {
                socket?.close()
                socket = null
                _isReceiving.value = false
            }
        }
    }

    // Demo 模式:無實機 / 無 ESP32 時用假資料驅動整個畫面，
    // 完全不開 socket、不連網，其餘狀態流程與真實接收一致
    fun startDemoMode() {
        if (_isReceiving.value) return
        _isReceiving.value = true
        startRateTimer()

        demoJob = viewModelScope.launch(Dispatchers.Default) {
            var tick = 0L
            while (isActive) {
                // 封包數與頻率視窗「每一筆」都累計，數字才會反映真實的高頻到達率
                _packetCount.value++
                windowPacketCount.incrementAndGet()

                // 畫面節流:每 20 筆(約 40ms,≈25Hz)才更新一次 latestData，
                // 避免 500Hz 下每筆都觸發 Compose 重繪而拖垮模擬器
                if (tick % 20 == 0L) {
                    // 以筆數推算時間(秒)讓波形平滑，不依賴系統時鐘
                    val t = tick * 0.002
                    _latestData.value = ImuData(
                        accelX = (sin(t) * 2.0).toFloat(),
                        accelY = (9.8 + cos(t) * 0.5).toFloat(),
                        accelZ = (cos(t * 0.5) * 2.0).toFloat(),
                        gyroX = (sin(t * 2.0) * 0.05).toFloat(),
                        gyroY = (cos(t * 3.0) * 0.05).toFloat(),
                        gyroZ = (sin(t * 1.5) * 0.05).toFloat(),
                        temperature = (32.0 + sin(t * 0.1) * 0.5).toFloat()
                    )
                }
                tick++
                delay(2) // 約每 2ms 產生一筆假資料
            }
        }
    }

    fun stopReceiving() {
        if (!_isReceiving.value) return
        _isReceiving.value = false

        // Demo 模式沒有 socket 可關，必須直接取消這個 coroutine 才停得下來
        demoJob?.cancel()
        demoJob = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                socket?.let { s ->
                    val stopBytes = "stop".toByteArray(Charsets.UTF_8)
                    s.send(
                        DatagramPacket(stopBytes, stopBytes.size, InetAddress.getByName(ESP32_IP), ESP32_PORT)
                    )
                }
            } catch (_: Exception) {
                // 送 stop 失敗不影響後續關閉流程
            }
            // 關閉 socket 會讓 receive() 丟出 SocketException，
            // 這是讓阻塞中的 receiveJob 退出的唯一可靠方法
            socket?.close()
            socket = null
        }

        rateJob?.cancel()
        windowPacketCount.set(0)
    }

    private fun startRateTimer() {
        rateJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                // 每秒快照並重置視窗計數，得到「本秒到達幾包」= 到達頻率（含重複封包）
                _arrivalRate.value = windowPacketCount.getAndSet(0)
            }
        }
    }

    private fun parsePacket(raw: String): ImuData? {
        return try {
            val parts = raw.split(",")
            // 欄位數不對時直接回傳 null（例如 ESP32 的啟動確認字串 "Sensor data transmission started."）
            if (parts.size != 7) return null
            ImuData(
                accelX = parts[0].trim().toFloat(),
                accelY = parts[1].trim().toFloat(),
                accelZ = parts[2].trim().toFloat(),
                gyroX = parts[3].trim().toFloat(),
                gyroY = parts[4].trim().toFloat(),
                gyroZ = parts[5].trim().toFloat(),
                temperature = parts[6].trim().toFloat()
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopReceiving()
    }
}
