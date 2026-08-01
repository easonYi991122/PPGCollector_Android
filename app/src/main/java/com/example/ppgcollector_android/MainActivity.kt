package com.example.ppgcollector_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.viewModels
import com.example.ppgcollector_android.core.ble.BleCoordinatorAction
import com.example.ppgcollector_android.core.ble.BleCoordinatorSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.ui.theme.PPGCollector_AndroidTheme

class MainActivity : ComponentActivity() {
    private val captureViewModel: CaptureViewModel by viewModels()

    private val bleCoordinator
        get() = (application as PpgCollectorApplication).bleCoordinator

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        bleCoordinator.applyPermissionResult(grants)
        if (bleCoordinator.snapshot.permission.canUseBle) {
            bleCoordinator.startScanning(clearPreviousResults = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PPGCollector_AndroidTheme {
                val snapshot by bleCoordinator.snapshotFlow.collectAsStateWithLifecycle()
                val capture by captureViewModel.serviceState.collectAsStateWithLifecycle()
                val sessionName by captureViewModel.sessionName.collectAsStateWithLifecycle()
                val captureGate by captureViewModel.captureGate.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BleHome(
                        snapshot = snapshot,
                        capture = capture,
                        sessionName = sessionName,
                        captureGate = captureGate,
                        onSessionNameChange = captureViewModel::setSessionName,
                        onStartCapture = captureViewModel::startRecording,
                        onStopCapture = captureViewModel::stopRecording,
                        onScan = ::requestScan,
                        onStopScan = bleCoordinator::stopScanning,
                        onConnect = bleCoordinator::connect,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        captureViewModel.onStart()
    }

    override fun onStop() {
        captureViewModel.onStop()
        super.onStop()
    }

    private fun requestScan() {
        val missing = bleCoordinator.permissionRequest()
        if (missing.isEmpty()) {
            bleCoordinator.startScanning(clearPreviousResults = true)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@androidx.compose.runtime.Composable
private fun BleHome(
    snapshot: BleCoordinatorSnapshot,
    capture: CaptureServiceObservation,
    sessionName: String,
    captureGate: CaptureGateUiState,
    onSessionNameChange: (String) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> BleCoordinatorAction,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("CUPCollector", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("权限：${snapshot.permission.gateState}")
        Text("蓝牙：${snapshot.availability.title}")
        Text("连接：${snapshot.phase}")
        Text("数据流：${snapshot.freshness}")
        Text("录制服务：${capture.binding}")
        Text("录制：${capture.recording.state}")
        Text("分析：${capture.analysis.state}")
        if (capture.recording.state != CaptureRecordingState.RECORDING &&
            capture.recording.state != CaptureRecordingState.STOPPING
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sessionName,
                onValueChange = onSessionNameChange,
                label = { Text("录制名称") },
                singleLine = true,
                enabled = !snapshot.phase.isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onStartCapture, enabled = captureGate.canStart) {
                Text("开始录制")
            }
            captureGate.message?.let { Text("开始条件：$it") }
        }
        if (capture.recording.state == CaptureRecordingState.RECORDING ||
            capture.recording.state == CaptureRecordingState.STOPPING
        ) {
            Button(onClick = onStopCapture) { Text("停止并保存") }
        }
        capture.error?.let { Text("服务：$it", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onScan, enabled = !snapshot.isScanning) {
                Text("扫描 CUP")
            }
            Button(onClick = onStopScan, enabled = snapshot.isScanning) {
                Text("停止扫描")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (snapshot.discoveredDevices.isEmpty()) {
            Text("暂无 CUP 设备")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(snapshot.discoveredDevices, key = { it.id }) { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(device.name)
                            Text("RSSI ${device.rssi ?: "—"}")
                        }
                        Button(
                            onClick = { onConnect(device.id) },
                            enabled = device.isConnectable && !snapshot.phase.isBusy,
                        ) {
                            Text("连接")
                        }
                    }
                }
            }
        }
        snapshot.lastError?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
    }
}
