package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.P)
class DualCameraManager(
    private val context: Context,
    private val logicalCameraId: String,
    private val physicalIdA: String?,
    private val physicalIdB: String?,
    private val surfaceA: Surface,
    private val surfaceB: Surface,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val onDualCapture: (ByteArray, ByteArray) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val sizeA: Size
    private val sizeB: Size

    private val imageReaderA: ImageReader
    private val imageReaderB: ImageReader

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val mainExecutor = context.mainExecutor

    private var bytesA: ByteArray? = null
    private var bytesB: ByteArray? = null
    
    private var zoomA: Float = 1.0f
    private var zoomB: Float = 1.0f

    private var minZoomRatio: Float = 1.0f

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val chars = cameraManager.getCameraCharacteristics(logicalCameraId)
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    minZoomRatio = zoomRange.lower
                    Log.d("DualCameraManager", "Min zoom ratio supported: \$minZoomRatio")
                }
            }
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Failed to get zoom range", e)
        }

        sizeA = getLargest43Size(physicalIdA ?: logicalCameraId)
        sizeB = getLargest43Size(physicalIdB ?: logicalCameraId)
        
        Log.d("DualCameraManager", "Initializing ImageReaders with 4:3 sizes: sizeA=${sizeA.width}x${sizeA.height}, sizeB=${sizeB.width}x${sizeB.height}")

        imageReaderA = ImageReader.newInstance(sizeA.width, sizeA.height, ImageFormat.JPEG, 2)
        imageReaderB = ImageReader.newInstance(sizeB.width, sizeB.height, ImageFormat.JPEG, 2)

        imageReaderA.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                bytesA = ByteArray(buffer.capacity()).apply { buffer.get(this) }
                image.close()
                checkCaptureComplete()
            }
        }, backgroundHandler)

        imageReaderB.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                bytesB = ByteArray(buffer.capacity()).apply { buffer.get(this) }
                image.close()
                checkCaptureComplete()
            }
        }, backgroundHandler)
    }

    private fun getLargest43Size(cameraId: String): Size {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes != null && sizes.isNotEmpty()) {
                val fourThirdsSizes = sizes.filter { size ->
                    val aspect = size.width.toFloat() / size.height.toFloat()
                    val diff = Math.abs(aspect - (4.0f / 3.0f))
                    val diffInv = Math.abs(aspect - (3.0f / 4.0f))
                    diff < 0.1 || diffInv < 0.1
                }
                if (fourThirdsSizes.isNotEmpty()) {
                    return fourThirdsSizes.maxByOrNull { it.width * it.height }!!
                }
                return sizes.maxByOrNull { it.width * it.height }!!
            }
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error finding largest 4:3 size for camera $cameraId", e)
        }
        return Size(4032, 3024)
    }

    private fun checkCaptureComplete() {
        if (bytesA != null && bytesB != null) {
            val a = bytesA!!
            val b = bytesB!!
            bytesA = null
            bytesB = null
            mainExecutor.execute {
                onDualCapture(a, b)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            cameraManager.openCamera(logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    
                    try {
                        val configSurfaceA = OutputConfiguration(surfaceA)
                        if (physicalIdA != null && physicalIdA != logicalCameraId) {
                            configSurfaceA.setPhysicalCameraId(physicalIdA)
                        }

                        val configSurfaceB = OutputConfiguration(surfaceB)
                        if (physicalIdB != null && physicalIdB != logicalCameraId) {
                            configSurfaceB.setPhysicalCameraId(physicalIdB)
                        }

                        val configReaderA = OutputConfiguration(imageReaderA.surface)
                        if (physicalIdA != null && physicalIdA != logicalCameraId) {
                            configReaderA.setPhysicalCameraId(physicalIdA)
                        }

                        val configReaderB = OutputConfiguration(imageReaderB.surface)
                        if (physicalIdB != null && physicalIdB != logicalCameraId) {
                            configReaderB.setPhysicalCameraId(physicalIdB)
                        }
                        
                        val outputs = listOf(configSurfaceA, configSurfaceB, configReaderA, configReaderB)
                        
                        val sessionConfig = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            outputs,
                            mainExecutor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    startPreview()
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e("DualCameraManager", "Failed to configure session")
                                }
                            }
                        )
                        camera.createCaptureSession(sessionConfig)
                    } catch (e: Exception) {
                        Log.e("DualCameraManager", "Error setting up session", e)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error opening camera", e)
        }
    }

    private fun startPreview() {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            builder.addTarget(surfaceA)
            builder.addTarget(surfaceB)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
            }
            // Zoom is handled via TextureView scaling and Bitmap cropping instead of CONTROL_ZOOM_RATIO
            
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error starting preview", e)
        }
    }

    fun setZoom(zA: Float, zB: Float) {
        zoomA = zA
        zoomB = zB
        // We do not re-apply startPreview here anymore because zoom is only visual/post-processed.
        // The camera streams full FOV constantly.
    }

    fun takePicture() {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            builder.addTarget(imageReaderA.surface)
            builder.addTarget(imageReaderB.surface)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoomRatio)
            }
            
            // Basic quality settings
            builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            
            captureSession?.capture(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error taking picture", e)
        }
    }

    fun stop() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            // Important: we leave the images readers and background thread alive until garbage collected 
            // or we could cleanly quit the thread here if we don't plan to reuse
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error stopping camera", e)
        }
    }
}
