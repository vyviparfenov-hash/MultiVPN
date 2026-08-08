package com.amneziaclient.simple.ui.qrscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.amneziaclient.simple.R
import com.amneziaclient.simple.databinding.ActivityQrScanBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Экран сканирования QR для добавления профиля VPN. Отдаёт распознанный
 * текст через onActivityResult (EXTRA_RESULT_TEXT) — что делать с этим
 * текстом (какому протоколу он соответствует) решает вызывающая сторона
 * (см. MainViewModel.onQrScanned).
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val resultDelivered = AtomicBoolean(false)

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.error_camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_RESULT_TEXT = "qr_result_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonClose.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val scannerOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(scannerOptions)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val text = barcodes.firstOrNull()?.rawValue
                                if (text != null) onQrDetected(text)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onQrDetected(text: String) {
        if (!resultDelivered.compareAndSet(false, true)) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_TEXT, text))
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
