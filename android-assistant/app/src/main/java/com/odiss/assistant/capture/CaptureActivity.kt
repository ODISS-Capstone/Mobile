package com.odiss.assistant.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
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
import com.odiss.assistant.core.AssistantPreferences
import com.odiss.assistant.service.HandsFreeService
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 사용자 허가(촬영 버튼) 후 후면 카메라로 약/처방전을 촬영하고,
 * 촬영 이미지를 ai-server 로 업로드하고 서버 단위 OCR/복약 처리를 수행하는 전용 화면.
 *
 * 핸즈프리 서비스가 ocr_request/음성명령을 받았을 때 이 화면을 띄워
 * "허가를 구하고 촬영" 하는 흐름을 충족한다.
 */
class CaptureActivity : ComponentActivity() {

    private val tts by lazy { TtsController(this) }
    private val prefs by lazy { AssistantPreferences(this) }
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val cameraSound by lazy { MediaActionSound() }

    private var imageCapture: ImageCapture? = null
    private var promptSpoken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pauseHandsFreeIfNeeded()
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

        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!promptSpoken) {
                promptSpoken = true
                tts.speak("약 봉투나 처방전을 카메라에 비추고 촬영 버튼을 눌러 주세요.")
            }
        }

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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("약·처방전 촬영", fontSize = 20.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            ) {
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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(status, fontSize = 18.sp)
                Button(
                    onClick = {
                        if (!working) {
                            working = true
                            status = "촬영 중…"
                            capturePhoto(
                                onResult = { imageFile ->
                                    status = "촬영 완료. 분석하는 동안 대화를 이어갈게요."
                                    tts.speak("촬영했어요. 분석하는 동안 계속 도와드릴게요.") {
                                        runOnUiThread { finish() }
                                    }
                                    handOffToHandsFree(imageFile)
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

    private fun capturePhoto(onResult: (File) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture ?: run {
            onError("카메라 준비 안 됨")
            return
        }
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    runCatching { cameraSound.play(MediaActionSound.SHUTTER_CLICK) }
                    val bitmap = runCatching { imageProxyToBitmap(image) }.getOrNull()
                    image.close()
                    if (bitmap == null) {
                        runOnUiThread { onError("이미지 변환 실패") }
                        return
                    }
                    lifecycleScope.launch {
                        val file = bitmap.toTempJpeg()
                        onResult(file)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { onError(exception.message ?: "알 수 없는 오류") }
                }
            },
        )
    }

    private fun handOffToHandsFree(imageFile: File) {
        if (prefs.handsFreeEnabled) {
            HandsFreeService.processCapturedImage(this, imageFile.absolutePath)
        } else {
            HandsFreeService.startCaptureAnalysis(this, imageFile.absolutePath)
        }
    }

    private fun pauseHandsFreeIfNeeded() {
        if (prefs.handsFreeEnabled) HandsFreeService.pauseForCapture(this)
    }

    private fun resumeHandsFreeIfNeeded() {
        // OCR 처리는 HandsFreeService가 이어받아 끝낸 뒤 직접 재개한다.
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

    private fun Bitmap.toTempJpeg(): File {
        val file = File.createTempFile("odiss-ocr-", ".jpg", cacheDir)
        val resized = resizeForUpload(maxSide = 1280)
        FileOutputStream(file).use { out ->
            resized.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
        if (resized !== this) resized.recycle()
        return file
    }

    private fun Bitmap.resizeForUpload(maxSide: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val scale = maxSide.toFloat() / longest.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    override fun onDestroy() {
        captureExecutor.shutdown()
        runCatching { cameraSound.release() }
        runCatching { tts.shutdown() }
        resumeHandsFreeIfNeeded()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureActivity"
    }
}
