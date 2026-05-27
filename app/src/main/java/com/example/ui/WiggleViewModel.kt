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
    val isDefaultUltraWide: Boolean = false,
    val parentLogicalId: String? = null
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
    val previewStateMessage: String = "",
    val captureTrigger: Int = 0,
    val autoZoomRatio: Float = 1.0f,
    val isAutoZoomApplied: Boolean = false
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
                            val physicalIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                chars.physicalCameraIds
                            } else {
                                emptySet<String>()
                            }

                            if (physicalIds.isNotEmpty()) {
                                Log.d(TAG, "Camera $id has physical camera IDs: $physicalIds")
                                // Add parent logical camera ID as an option too!
                                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val mainFocal = focalLengths?.firstOrNull()
                                val isUltraWide = mainFocal != null && mainFocal < 3.0f
                                lenses.add(
                                    CameraLensDetails(
                                        id = id,
                                        name = "Auto Smart Multi-Lens #$id",
                                        focalLength = mainFocal,
                                        isDefaultUltraWide = isUltraWide,
                                        parentLogicalId = null
                                    )
                                )

                                // Add each individual physical lens
                                for (pId in physicalIds) {
                                    try {
                                        val pChars = cameraManager.getCameraCharacteristics(pId)
                                        val pFocal = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                                        val isPUltraWide = pFocal != null && ((mainFocal != null && pFocal < mainFocal * 0.8f) || pFocal < 3.0f)
                                        val label = when {
                                            isPUltraWide -> "Phys. Lens #$pId - Ultra-Wide (~0.5x)"
                                            pFocal != null && mainFocal != null && pFocal > mainFocal * 1.5f -> "Phys. Lens #$pId - Telephoto (~2x+)"
                                            pFocal != null && mainFocal == null && pFocal >= 8.0f -> "Phys. Lens #$pId - Telephoto (~2x+)"
                                            else -> "Phys. Lens #$pId - Wide Main (1x)"
                                        }
                                        lenses.add(
                                            CameraLensDetails(
                                                id = pId,
                                                name = label,
                                                focalLength = pFocal,
                                                isDefaultUltraWide = isPUltraWide,
                                                parentLogicalId = id
                                            )
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error querying physical lens $pId", e)
                                    }
                                }
                            } else {
                                // Simple individual back camera
                                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                val mainFocal = focalLengths?.firstOrNull()
                                val isUltraWide = mainFocal != null && mainFocal < 3.0f
                                val label = when {
                                    isUltraWide -> "Camera #$id - Ultra-Wide (~0.5x)"
                                    mainFocal != null && mainFocal >= 8.0f -> "Camera #$id - Telephoto (~2x+)"
                                    else -> "Camera #$id - Standard Wide (1x)"
                                }
                                lenses.add(
                                    CameraLensDetails(
                                        id = id,
                                        name = label,
                                        focalLength = mainFocal,
                                        isDefaultUltraWide = isUltraWide,
                                        parentLogicalId = null
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking characteristics of camera $id", e)
                    }
                }

                if (lenses.isEmpty()) {
                    // Fallback mock definitions for testing/emulators where physical descriptors might be omitted
                    lenses.add(CameraLensDetails("0", "Cam 0 - Standard (1x)", 4.25f, false, null))
                    lenses.add(CameraLensDetails("1", "Cam 1 - Ultra-Wide (0.5x)", 1.85f, true, null))
                }

                // Default Primary: first non-ultrawide. Default Secondary: first ultrawide or second item.
                val primary = lenses.firstOrNull { !it.isDefaultUltraWide && it.parentLogicalId != null }
                    ?: lenses.firstOrNull { !it.isDefaultUltraWide }
                    ?: lenses.firstOrNull()
                val secondary = lenses.firstOrNull { it.isDefaultUltraWide && it.parentLogicalId != null }
                    ?: lenses.firstOrNull { it.isDefaultUltraWide }
                    ?: lenses.getOrNull(1)
                    ?: lenses.firstOrNull()

                _uiState.value = _uiState.value.copy(
                    availableLenses = lenses,
                    primaryLens = primary,
                    secondaryLens = secondary,
                    concurrentPreviewSupported = concurrentCapable,
                    previewStateMessage = if (concurrentCapable) "Dual Cameras Active" else "Single Viewfinder (Smart Fallback Capture)"
                )
                autoCalibrateFOV()
            } catch (e: Exception) {
                Log.e(TAG, "Failed discovering cameras", e)
            }
        }
    }

    fun autoCalibrateFOV(bitmapA: Bitmap? = null, bitmapB: Bitmap? = null) {
        if (bitmapA != null && bitmapB != null) {
            viewModelScope.launch {
                val bestZoom = withContext(Dispatchers.Default) {
                    calculateVisualZoomMatch(bitmapA, bitmapB)
                }
                _uiState.value = _uiState.value.copy(
                    zoomB = bestZoom,
                    autoZoomRatio = bestZoom,
                    isAutoZoomApplied = true
                )
                Log.d(TAG, "Auto FOV visually calibrated: zoomB=$bestZoom")
            }
            return
        }
        
        // Fallback: focal length ratio
        val primary = _uiState.value.primaryLens
        val secondary = _uiState.value.secondaryLens
        var ratio = 2.0f
        if (primary?.focalLength != null && secondary?.focalLength != null && secondary.focalLength > 0f) {
            ratio = primary.focalLength / secondary.focalLength
        }
        val clamped = ratio.coerceIn(1.0f, 3.0f)
        _uiState.value = _uiState.value.copy(zoomB = clamped, autoZoomRatio = clamped, isAutoZoomApplied = true)
        Log.d(TAG, "Auto FOV calibrated (focal length fallback): zoomB=$clamped")
    }

    /**
     * Compares the center of camera A's preview with increasingly zoomed crops
     * of camera B's preview to find the zoom level where B best matches A.
     * 
     * Uses Zero-mean Normalized Cross-Correlation (ZNCC) on luminance values,
     * which is invariant to brightness/exposure differences between the two cameras.
     * Searches from 1.00x to 3.00x in 0.01x steps.
     */
    private fun calculateVisualZoomMatch(bmpA: Bitmap, bmpB: Bitmap): Float {
        val wA = bmpA.width
        val hA = bmpA.height
        val wB = bmpB.width
        val hB = bmpB.height
        
        Log.d(TAG, "Visual match: A=${wA}x${hA}, B=${wB}x${hB}")
        
        // Read all pixels from both images upfront (fast bulk read)
        val allPixelsA = IntArray(wA * hA)
        bmpA.getPixels(allPixelsA, 0, wA, 0, 0, wA, hA)
        val allPixelsB = IntArray(wB * hB)
        bmpB.getPixels(allPixelsB, 0, wB, 0, 0, wB, hB)
        
        // Comparison grid: 64x64 = 4096 sample points per zoom level
        val gridSize = 64
        
        // Reference region: center 50% of image A
        val refSize = minOf(wA, hA) / 2
        val refX0 = (wA - refSize) / 2
        val refY0 = (hA - refSize) / 2
        
        // Sample A's center into luminance array
        val lumA = FloatArray(gridSize * gridSize)
        var sumA = 0.0
        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                val ax = refX0 + (gx * refSize) / gridSize
                val ay = refY0 + (gy * refSize) / gridSize
                val c = allPixelsA[ay * wA + ax]
                val l = ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
                lumA[gy * gridSize + gx] = l
                sumA += l
            }
        }
        val meanA = (sumA / lumA.size).toFloat()
        
        // Precompute A's variance
        var varSumA = 0.0
        for (l in lumA) {
            val d = (l - meanA).toDouble()
            varSumA += d * d
        }
        
        var bestZoom = 2.0f
        var maxScore = -2.0f
        
        // Search zoom 1.00 to 3.00 in steps of 0.01 (200 iterations)
        for (zi in 100..300) {
            val zoom = zi / 100.0f
            
            // At this zoom level, the visible crop of B is:
            val cropW = (wB / zoom).toInt()
            val cropH = (hB / zoom).toInt()
            if (cropW < 20 || cropH < 20) continue
            
            val cx0 = (wB - cropW) / 2
            val cy0 = (hB - cropH) / 2
            
            // Take center 50% of B's crop (matching A's center 50% strategy)
            val innerSize = minOf(cropW, cropH) / 2
            if (innerSize < 10) continue
            val ix0 = cx0 + (cropW - innerSize) / 2
            val iy0 = cy0 + (cropH - innerSize) / 2
            
            // Sample B's region into luminance array
            val lumB = FloatArray(gridSize * gridSize)
            var sumB = 0.0
            for (gy in 0 until gridSize) {
                for (gx in 0 until gridSize) {
                    val bx = ix0 + (gx * innerSize) / gridSize
                    val by = iy0 + (gy * innerSize) / gridSize
                    if (bx in 0 until wB && by in 0 until hB) {
                        val c = allPixelsB[by * wB + bx]
                        val l = ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
                        lumB[gy * gridSize + gx] = l
                        sumB += l
                    }
                }
            }
            val meanB = (sumB / lumB.size).toFloat()
            
            // Compute ZNCC (zero-mean normalized cross-correlation)
            var crossSum = 0.0
            var varSumB = 0.0
            for (i in lumA.indices) {
                val dA = (lumA[i] - meanA).toDouble()
                val dB = (lumB[i] - meanB).toDouble()
                crossSum += dA * dB
                varSumB += dB * dB
            }
            
            val den = Math.sqrt(varSumA * varSumB)
            val score = if (den > 0) (crossSum / den).toFloat() else 0f
            
            if (score > maxScore) {
                maxScore = score
                bestZoom = zoom
            }
        }
        
        Log.d(TAG, "Visual zoom match: bestZoom=$bestZoom, score=$maxScore")
        return bestZoom
    }

    fun setPrimaryLens(lens: CameraLensDetails) {
        _uiState.value = _uiState.value.copy(primaryLens = lens)
        if (_uiState.value.isAutoZoomApplied) {
            autoCalibrateFOV()
        }
    }

    fun setSecondaryLens(lens: CameraLensDetails) {
        _uiState.value = _uiState.value.copy(secondaryLens = lens)
        if (_uiState.value.isAutoZoomApplied) {
            autoCalibrateFOV()
        }
    }

    fun setZoomA(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomA = zoom, isAutoZoomApplied = false)
    }

    fun setZoomB(zoom: Float) {
        _uiState.value = _uiState.value.copy(zoomB = zoom, isAutoZoomApplied = false)
    }

    fun triggerCapture() {
        _uiState.value = _uiState.value.copy(captureTrigger = _uiState.value.captureTrigger + 1)
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
