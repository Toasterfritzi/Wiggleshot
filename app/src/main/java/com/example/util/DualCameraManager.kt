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

    private val imageReaderA = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
    private val imageReaderB = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val mainExecutor = context.mainExecutor

    private var bytesA: ByteArray? = null
    private var bytesB: ByteArray? = null
    
    private var zoomA: Float = 1.0f
    private var zoomB: Float = 1.0f

    init {
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
            
            // Note: CONTROL_ZOOM_RATIO on logical camera affects both, we just use max or similar.
            // But true independent zoom requires physical stream control or software cropping.
            // For now, we set the logical zoom to the max of both and crop later if needed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, maxOf(zoomA, zoomB))
            }
            
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("DualCameraManager", "Error starting preview", e)
        }
    }

    fun setZoom(zA: Float, zB: Float) {
        zoomA = zA
        zoomB = zB
        startPreview() // re-apply
    }

    fun takePicture() {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            builder.addTarget(imageReaderA.surface)
            builder.addTarget(imageReaderB.surface)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, maxOf(zoomA, zoomB))
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
