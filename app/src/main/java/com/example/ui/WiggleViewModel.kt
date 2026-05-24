package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.WiggleCapture
import com.example.data.WiggleRepository
import com.example.util.WiggleProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL

data class CameraLensDetails(
    val id: String,
    val name: String,
    val focalLength: Float?,
    val isDefaultUltraWide: Boolean = false
)

data class WiggleUiState(
    val selectedCapture: WiggleCapture? = null,
    val isCapturing: Boolean = false,
    val availableLenses: List<CameraLensDetails> = emptyList(),
    val primaryLens: CameraLensDetails? = null,
    val secondaryLens: CameraLensDetails? = null,
    val zoomA: Float = 1.0f,
    val zoomB: Float = 1.0f,
    val concurrentPreviewSupported: Boolean = false,
    val previewStateMessage: String = ""
)

class WiggleViewModel(private val repository: WiggleRepository) : ViewModel() {
    private val TAG = "WiggleViewModel"

    val capturesList: StateFlow<List<WiggleCapture>> = repository.allCaptures
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(WiggleUiState())
    val uiState: StateFlow<WiggleUiState> = _uiState.asStateFlow()

    fun initCameraDiscovery(context: Context) {
        viewModelScope.launch {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val lenses = mutableListOf<CameraLensDetails>()
                var concurrentCapable = false

                // Check concurrent camera preview support in Android 11+ (API 30)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val concurrentSets = cameraManager.concurrentCameraIds
                    if (concurrentSets.isNotEmpty()) {
                        concurrentCapable = true
                        Log.d(TAG, "Device supports concurrent physical camera streams!")
                    }
                }

                for (id in cameraManager.cameraIdList) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val mainFocal = focalLengths?.firstOrNull()
                            
                            // Guess if it's ultra wide based on typical focal length < 3.0mm
                            val isUltraWide = mainFocal != null && mainFocal < 3.0f
                            val labelName = when {
                                isUltraWide -> "Camera #$id - Ultra-Wide (~0.5x)"
                                mainFocal != null && mainFocal >= 6.0f -> "Camera #$id - Telephoto (~2x+)"
                                else -> "Camera #$id - Standard Wide (1x)"
                            }

                            lenses.add(
                                CameraLensDetails(
                                    id = id,
                                    name = labelName,
                                    focalLength = mainFocal,
                                    isDefaultUltraWide = isUltraWide
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking characteristics of camera $id", e)
                    }
                }

                if (lenses.isEmpty()) {
                    // Fallback mock definitions for testing/emulators where physical descriptors might be omitted
                    lenses.add(CameraLensDetails("0", "Cam 0 - Standard (1x)", 4.25f, false))
                    lenses.add(CameraLensDetails("1", "Cam 1 - Ultra-Wide (0.5x)", 1.85f, true))
                }

                val primary = lenses.firstOrNull { !it.isDefaultUltraWide } ?: lenses.firstOrNull()
                val secondary = lenses.firstOrNull { it.isDefaultUltraWide } ?: lenses.getOrNull(1) ?: lenses.firstOrNull()

                _uiState.value = _uiState.value.copy(
                    availableLenses = lenses,
                    primaryLens = primary,
                    secondaryLens = secondary,
                    concurrentPreviewSupported = concurrentCapable,
                    previewStateMessage = if (concurrentCapable) "Dual Cameras Active" else "Single Viewfinder (Smart Fallback Capture)"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed discovering cameras", e)
            }
        }
    }

    fun setPrimaryLens(lens: CameraLensDetails) {
        _uiState.value = _uiState.value.copy(primaryLens = lens)
    }

    fun setSecondaryLens(lens: CameraLensDetails) {
        _uiState.value = _uiState.value.copy(secondaryLens = lens)
    }

    fun setZoomA(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomA = zoom)
    }

    fun setZoomB(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomB = zoom)
    }

    fun selectCapture(capture: WiggleCapture?) {
        _uiState.value = _uiState.value.copy(selectedCapture = capture)
    }

    fun deleteCapture(context: Context, capture: WiggleCapture) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete internal files safely
                File(capture.imageAPath).delete()
                File(capture.imageBPath).delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting internal raw cache files", e)
            }
            repository.deleteCapture(capture.id)
            if (_uiState.value.selectedCapture?.id == capture.id) {
                _uiState.value = _uiState.value.copy(selectedCapture = null)
            }
        }
    }

    /**
     * Executes the Dual Capture capture process.
     */
    fun performDualCapture(context: Context, bitmapA: Bitmap?, bitmapB: Bitmap?) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isCapturing = true)
            try {
                if (bitmapA == null || bitmapB == null) {
                    Log.e(TAG, "Null bitmaps supplied. Cannot perform capture.")
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isCapturing = false)
                    }
                    return@launch
                }

                val timestamp = System.currentTimeMillis()

                // Save to local cached directory for rapid app rendering in LazyColumns / players
                val pathA = WiggleProcessor.saveToInternalFiles(context, bitmapA, "camA_$timestamp.jpg") ?: ""
                val pathB = WiggleProcessor.saveToInternalFiles(context, bitmapB, "camB_$timestamp.jpg") ?: ""

                // Save BOTH images at the very same time to the user's Gallery
                val publicUriA = WiggleProcessor.saveToGallery(context, bitmapA, "Wiggle_${timestamp}_Left")
                val publicUriB = WiggleProcessor.saveToGallery(context, bitmapB, "Wiggle_${timestamp}_Right")

                Log.d(TAG, "Exported both raw shots to media store: A: $publicUriA, B: $publicUriB")

                // Insert database record
                val newCapture = WiggleCapture(
                    imageAPath = pathA,
                    imageBPath = pathB,
                    timestamp = timestamp
                )

                val insertId = repository.insertCapture(newCapture)
                val savedCapture = newCapture.copy(id = insertId)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        selectedCapture = savedCapture,
                        isCapturing = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing stereoscopic dual frame capture", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isCapturing = false)
                }
            }
        }
    }
}

class WiggleViewModelFactory(private val repository: WiggleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WiggleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WiggleViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
