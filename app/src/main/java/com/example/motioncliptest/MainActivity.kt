package com.example.motioncliptest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.motioncliptest.ui.theme.MotionClipTestTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // by viewModels() 取得與 Activity 生命週期綁定的 UdpViewModel，
    // UdpViewModel 是 AndroidViewModel,預設工廠會自動注入 Application
    private val viewModel: UdpViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotionClipTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MotionClipScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MotionClipScreen(viewModel: UdpViewModel, modifier: Modifier = Modifier) {
    // collectAsStateWithLifecycle():在 Compose 中收集 StateFlow,並「感知生命週期」——
    // App 進背景(STOPPED)時自動暫停收集、回前景時恢復,避免背景白白重繪浪費資源。
    // 直接用 collect 或 collectAsState 不會跟著生命週期暫停,所以這裡用前者。
    val isReceiving by viewModel.isReceiving.collectAsStateWithLifecycle()
    val latestData by viewModel.latestData.collectAsStateWithLifecycle()
    val packetCount by viewModel.packetCount.collectAsStateWithLifecycle()
    val arrivalRate by viewModel.arrivalRate.collectAsStateWithLifecycle()

    // 5 秒無資料警告:packetCount 一變動就重啟這個 effect,delay(5000) 被取消重來;
    // 若 5 秒內都沒有新封包,delay 才會跑完並把警告打開。停止接收時不顯示。
    var showNoDataWarning by remember { mutableStateOf(false) }
    LaunchedEffect(packetCount, isReceiving) {
        showNoDataWarning = false
        if (isReceiving) {
            delay(5000)
            showNoDataWarning = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (isReceiving) "● 接收中" else "○ 已停止",
            color = if (isReceiving) Color(0xFF2E7D32) else Color.Gray,
            fontSize = 18.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.startReceiving() }) { Text("開始接收") }
            Button(onClick = { viewModel.stopReceiving() }) { Text("停止接收") }
        }
        Button(onClick = { viewModel.startDemoMode() }) { Text("Demo 模式（無裝置）") }

        Divider()

        // 7 欄 IMU 數值:latestData 為 null 時每欄顯示 "--"
        ImuValueRow("accel.x", latestData?.accelX, "m/s²", 4)
        ImuValueRow("accel.y", latestData?.accelY, "m/s²", 4)
        ImuValueRow("accel.z", latestData?.accelZ, "m/s²", 4)
        ImuValueRow("gyro.x", latestData?.gyroX, "rad/s", 4)
        ImuValueRow("gyro.y", latestData?.gyroY, "rad/s", 4)
        ImuValueRow("gyro.z", latestData?.gyroZ, "rad/s", 4)
        ImuValueRow("temperature", latestData?.temperature, "°C", 2)

        Divider()

        Text("累計封包數：$packetCount", fontSize = 16.sp)
        Text("到達頻率：$arrivalRate Hz", fontSize = 16.sp)

        if (showNoDataWarning) {
            Text(
                text = "⚠ 未收到資料，請確認已連上 MotionClip WiFi",
                color = Color(0xFFC62828),
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun ImuValueRow(label: String, value: Float?, unit: String, decimals: Int) {
    // value 為 null(尚未收到資料)時顯示 "--",否則用指定小數位格式化
    val text = if (value == null) "--" else String.format("%.${decimals}f", value)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 16.sp)
        Text(
            text = "$text $unit",
            fontSize = 16.sp,
            // 等寬字型讓跳動的數字不會左右晃動,看起來穩定
            fontFamily = FontFamily.Monospace
        )
    }
}
