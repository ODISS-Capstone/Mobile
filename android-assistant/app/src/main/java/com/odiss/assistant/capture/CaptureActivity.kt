package com.odiss.assistant.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.odiss.assistant.audio.TtsController
import com.odiss.assistant.data.OdissRepository
import com.odiss.assistant.ocr.MedicationParser
import com.odiss.assistant.ocr.OcrTextExtractor
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 사용자 허가(촬영 버튼) 후 후면 카메라로 약/처방전을 촬영하고,
 * 온디바이스 OCR을 거쳐 결과를 ai-server 로 전송하는 전용 화면.
 *
 * 핸즈프리 서비스가 ocr_request/음성명령을 받았을 때 이 화면을 띄워
 * "허가를 구하고 촬영" 하는 흐름을 충족한다.
 */
class CaptureActivity : ComponentActivity() {

    private val repository by lazy { OdissRepository() }
    private val ocr by lazy { OcrTextExtractor(this) }
    private val tts by lazy { TtsController(this) }
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CaptureScreen()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CaptureScreen() {
        val context = LocalContext.current
        var hasCamera by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        var status by remember { mutableStateOf("약 또는 처방전을 화면에 맞추고 ‘촬영’을 눌러 주세요.") }
        var working by remember { mutableStateOf(false) }

        val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasCamera = granted
            if (!granted) {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!hasCamera) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (hasCamera) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            bindCamera(previewView)
                            previewView
                        },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("카메라 권한을 허용해 주세요.", color = Color.White, fontSize = 18.sp)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(status, fontSize = 18.sp)
                Button(
                    onClick = {
                        if (!working) {
                            working = true
                            status = "촬영 중…"
                            capturePhoto(
                                onResult = { text ->
                                    status = "약 정보를 서버로 보내는 중…"
                                    submit(text) { finish() }
                                },
                                onError = { msg ->
                                    working = false
                                    status = "촬영 실패: $msg. 다시 시도해 주세요."
                                },
                            )
                        }
                    },
                    enabled = hasCamera && !working,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text(if (working) "처리 중…" else "촬영", fontSize = 22.sp)
                }
                OutlinedButton(
                    onClick = { finish() },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("취소", fontSize = 18.sp)
                }
            }
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
            }.onFailure { Log.e(TAG, "camera bind failed: ${it.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture ?: run {
            onError("카메라 준비 안 됨")
            return
        }
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = runCatching { imageProxyToBitmap(image) }.getOrNull()
                    image.close()
                    if (bitmap == null) {
                        runOnUiThread { onError("이미지 변환 실패") }
                        return
                    }
                    lifecycleScope.launch {
                        val text = ocr.extract(bitmap)
                        onResult(text)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { onError(exception.message ?: "알 수 없는 오류") }
                }
            },
        )
    }

    private fun submit(rawText: String, onDone: () -> Unit) {
        val meds = MedicationParser.parse(rawText)
        lifecycleScope.launch {
            repository.sendOcrResult(rawText, meds, confidence = 0.8)
                .catch { e ->
                    Log.w(TAG, "ocr ws error: ${e.message}")
                    tts.speak("서버로 보내지 못했습니다. 다시 시도해 주세요.")
                }
                .collect { response ->
                    val spoken = (response.response_text ?: response.text ?: response.message).orEmpty()
                    if (response.requires_tts && spoken.isNotBlank()) tts.speak(spoken)
                }
            runCatching { repository.submitOcr(rawText, meds, confidence = 0.8) }
            onDone()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    override fun onDestroy() {
        captureExecutor.shutdown()
        runCatching { tts.shutdown() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureActivity"
    }
}
