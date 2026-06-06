package com.odiss.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odiss.assistant.assistant.AssistantViewModel
import com.odiss.assistant.assistant.ChatLine
import com.odiss.assistant.assistant.ConnectionState
import com.odiss.assistant.audio.SttController
import com.odiss.assistant.audio.TtsController
import com.odiss.assistant.capture.CaptureActivity
import com.odiss.assistant.core.AssistantPreferences
import com.odiss.assistant.model.MedicationInput
import com.odiss.assistant.ocr.OcrTextExtractor
import com.odiss.assistant.service.HandsFreeService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun AssistantScreen(viewModel: AssistantViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val tts = remember { TtsController(context) }
    val stt = remember { SttController(context) }
    val ocr = remember { OcrTextExtractor(context) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasAudioPermission = granted }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    val prefs = remember { AssistantPreferences(context) }
    var handsFreeOn by remember { mutableStateOf(prefs.handsFreeEnabled) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 알림 권한 결과는 서비스 동작에 필수는 아니므로 무시 */ }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            viewModel.clearAwaitingOcr()
            return@rememberLauncherForActivityResult
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            viewModel.clearAwaitingOcr()
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            val text = ocr.extract(bitmap)
            val meds = parseMedicationFromRaw(text)
            viewModel.sendOcrPayload(text, meds, tts)
            viewModel.clearAwaitingOcr()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "ODISS 복약 도우미",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        ConnectionCard(
            connection = state.connection,
            serverLabel = state.serverLabel,
            status = state.status,
            onRetry = { viewModel.refreshHealth() },
        )

        HandsFreeCard(
            enabled = handsFreeOn,
            onToggle = { turnOn ->
                if (turnOn) {
                    if (!hasAudioPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    handsFreeOn = true
                    prefs.handsFreeEnabled = true
                    HandsFreeService.start(context)
                } else {
                    handsFreeOn = false
                    prefs.handsFreeEnabled = false
                    HandsFreeService.stop(context)
                }
            },
        )

        state.errorText?.let { error ->
            ErrorCard(message = error, onRetry = { viewModel.refreshHealth() }, onDismiss = { viewModel.dismissError() })
        }

        if (state.awaitingOcr) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
            ) {
                Text(
                    "서버가 처방전 촬영을 요청했습니다. 아래 버튼으로 약 사진을 선택해 주세요.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 18.sp,
                )
            }
        }

        BigActionButton(
            label = if (state.busy) "처리 중…" else "말하기",
            enabled = hasAudioPermission && !state.busy,
            container = Color(0xFF1B5E20),
        ) {
            coroutineScope.launch {
                val text = stt.listenOnce().first()
                viewModel.onSttTextRecognized(text, tts)
            }
        }

        BigActionButton(
            label = "약 직접 촬영 (카메라)",
            enabled = !state.busy,
            container = Color(0xFF00695C),
        ) {
            if (!hasCameraPermission) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            context.startActivity(Intent(context, CaptureActivity::class.java))
        }

        BigActionButton(
            label = "처방전 · 약 사진 선택",
            enabled = !state.busy,
            container = Color(0xFF0D47A1),
        ) {
            photoPicker.launch("image/*")
        }

        OutlinedButton(
            onClick = { viewModel.repeatLast(tts) },
            enabled = state.lastSpokenText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Text("다시 듣기", fontSize = 20.sp)
        }

        Text("대화 내용", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages) { line -> ChatBubble(line) }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionState,
    serverLabel: String,
    status: String,
    onRetry: () -> Unit,
) {
    val (dotColor, label) = when (connection) {
        ConnectionState.ONLINE -> Color(0xFF2E7D32) to "연결됨"
        ConnectionState.OFFLINE -> Color(0xFFC62828) to "연결 끊김"
        ConnectionState.CHECKING -> Color(0xFFF9A825) to "확인 중"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("서버 $label", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(status, fontSize = 14.sp, color = Color(0xFF555555))
                Text(serverLabel, fontSize = 12.sp, color = Color(0xFF888888))
            }
            if (connection != ConnectionState.CHECKING) {
                OutlinedButton(onClick = onRetry) { Text("재연결") }
            }
        }
    }
}

@Composable
private fun HandsFreeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("핸즈프리 음성비서", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (enabled) {
                        "켜짐 · ‘오디스’라고 부른 뒤 말씀하세요. 앱을 닫아도 동작합니다."
                    } else {
                        "꺼짐 · 켜면 상시 음성 인식과 자동 응답이 시작됩니다."
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF555555),
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, fontSize = 16.sp, color = Color(0xFFB71C1C))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("다시 시도") }
                OutlinedButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun BigActionButton(
    label: String,
    enabled: Boolean,
    container: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container),
    ) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val isUser = line.role == "user"
    val container = when (line.role) {
        "user" -> Color(0xFFE3F2FD)
        "odiss" -> Color(0xFFF1F8E9)
        else -> Color(0xFFF5F5F5)
    }
    val speaker = when (line.role) {
        "user" -> "나"
        "odiss" -> "ODISS"
        else -> "안내"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(speaker, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
            Text(line.text, fontSize = if (isUser) 16.sp else 18.sp)
        }
    }
}

private fun parseMedicationFromRaw(raw: String): List<MedicationInput> {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val meds = mutableListOf<MedicationInput>()
    for (line in lines) {
        if (line.length < 2) continue
        if (line.contains("mg") || line.contains("정") || line.contains("캡슐")) {
            meds += MedicationInput(name = line.take(80))
        }
    }
    if (meds.isEmpty() && raw.isNotBlank()) {
        meds += MedicationInput(name = raw.take(60))
    }
    return meds
}
