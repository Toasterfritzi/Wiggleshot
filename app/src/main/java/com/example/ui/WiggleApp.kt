package com.example.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.WiggleCapture
import com.example.util.WiggleProcessor
import com.example.ui.WiggleViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WiggleApp(viewModel: WiggleViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val captures by viewModel.capturesList.collectAsStateWithLifecycle()

    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        viewModel.initCameraDiscovery(context)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0F1115) // Deep luxury photographic dark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0F1115))
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FilterFrames,
                        contentDescription = "3D Logo",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "WIGGLE-CAM 3D",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E2430),
                    modifier = Modifier.clickable {
                        Toast.makeText(context, "Dual Camera Calibration Locked", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF00FFCC), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "STEREO ACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            if (cameraPermissionState.status.isGranted) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        // 1. Dual Camera Workspace
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF161920))
                                .border(1.dp, Color(0xFF232A38), RoundedCornerShape(24.dp))
                        ) {
                            if (uiState.selectedCapture == null) {
                                // Camera Mode
                                PreviewAndControlLayout(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onCapture = { bA, bB ->
                                        viewModel.performDualCapture(context, bA, bB)
                                    }
                                )
                            } else {
                                // Wiggle Player Mode
                                WigglePlayerLayout(
                                    capture = uiState.selectedCapture!!,
                                    viewModel = viewModel,
                                    onCloseReview = { viewModel.selectCapture(null) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3. Captures history / Database list
                        Text(
                            text = "GALLERY & CREATIONS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4E586E),
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )

                        if (captures.isEmpty()) {
                            EmptyGalleryState()
                        } else {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(captures) { item ->
                                    HistoryItemCard(
                                        item = item,
                                        isSelected = uiState.selectedCapture?.id == item.id,
                                        onSelect = { viewModel.selectCapture(item) },
                                        onDelete = { viewModel.deleteCapture(context, item) }
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                // Request Permission view
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161920), RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFF232A38), RoundedCornerShape(24.dp))
                            .padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Camera",
                            tint = Color(0xFF00FFCC),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This application strictly requires dual core back camera permissions to perform dynamic alignment calibration and capture gorgeous stereoscopic depth effects.",
                            fontSize = 14.sp,
                            color = Color(0xFF8A94A6),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Grant Permission",
                                color = Color(0xFF0F1115),
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreviewAndControlLayout(
    uiState: WiggleUiState,
    viewModel: WiggleViewModel,
    onCapture: (Bitmap?, Bitmap?) -> Unit
) {
    val context = LocalContext.current
    var liveBitmapA by remember { mutableStateOf<Bitmap?>(null) }
    var liveBitmapB by remember { mutableStateOf<Bitmap?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // Dual viewfinders display
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Viewfinder A: Primary
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, Color(0xFF232A38))
                ) {
                    CameraXViewfinder(
                        lensId = uiState.primaryLens?.id ?: "0",
                        zoomRatio = uiState.zoomA,
                        onFrame = { liveBitmapA = it }
                    )
                    
                    // Labelling overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Column {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                text = "LENS A: ${uiState.primaryLens?.name ?: "Unknown"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.clickable { expanded = true }
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                uiState.availableLenses.forEach { lens ->
                                    DropdownMenuItem(
                                        text = { Text(lens.name) },
                                        onClick = {
                                            viewModel.setPrimaryLens(lens)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(8.dp)
                            .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Column {
                            Text(
                                text = "ZOOM A: ${String.format("%.1fx", uiState.zoomA)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FFCC)
                            )
                            Slider(
                                value = uiState.zoomA,
                                onValueChange = { viewModel.setZoomA(it) },
                                valueRange = 1f..10f,
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }

                // Viewfinder B: Secondary
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, Color(0xFF232A38))
                ) {
                    CameraXViewfinder(
                        lensId = uiState.secondaryLens?.id ?: "1",
                        zoomRatio = uiState.zoomB,
                        onFrame = { liveBitmapB = it }
                    )

                    // Labelling overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Column {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                text = "LENS B: ${uiState.secondaryLens?.name ?: "Unknown"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.clickable { expanded = true }
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                uiState.availableLenses.forEach { lens ->
                                    DropdownMenuItem(
                                        text = { Text(lens.name) },
                                        onClick = {
                                            viewModel.setSecondaryLens(lens)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxWidth()
                            .padding(8.dp)
                            .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Column {
                            Text(
                                text = "ZOOM B: ${String.format("%.1fx", uiState.zoomB)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FFCC)
                            )
                            Slider(
                                value = uiState.zoomB,
                                onValueChange = { viewModel.setZoomB(it) },
                                valueRange = 1f..10f,
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            }

            // Central physical Shutter button bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161920))
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isCapturing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF00FFCC), modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "ALIGNING & INTERPOLATING...",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FFCC),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                            .padding(6.dp)
                            .border(3.dp, Color.White, CircleShape)
                            .clickable {
                                onCapture(liveBitmapA, liveBitmapB)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color(0xFF00FFCC), CircleShape)
                        )
                    }
                }
            }
        }
    }
}



@Composable
fun WigglePlayerLayout(
    capture: WiggleCapture,
    viewModel: WiggleViewModel,
    onCloseReview: () -> Unit
) {
    val context = LocalContext.current
    var currentFrameIndex by remember { mutableStateOf(0) }
    
    // Read files
    val bitmapA = remember(capture.imageAPath) { WiggleProcessor.loadBitmap(capture.imageAPath) }
    val bitmapB = remember(capture.imageBPath) { WiggleProcessor.loadBitmap(capture.imageBPath) }

    // Playback loop controller (A -> B)
    LaunchedEffect(Unit) {
        while (true) {
            val delayMs = 250L // 4 fps simple toggle
            delay(delayMs)
            currentFrameIndex = (currentFrameIndex + 1) % 2
        }
    }

    val activeDisplayBitmap = when (currentFrameIndex) {
        0 -> bitmapA
        else -> bitmapB
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Player screen area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (activeDisplayBitmap != null) {
                Image(
                    bitmap = activeDisplayBitmap.asImageBitmap(),
                    contentDescription = "Holographic Parallax Feed",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Return / Close button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(Color(0x990D0F12), CircleShape)
                    .clickable { onCloseReview() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Loop Frame Number Indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0x990D0F12), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val frameLabel = when (currentFrameIndex) {
                    0 -> "CAM A"
                    else -> "CAM B"
                }
                Text(
                    text = "$frameLabel",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FFCC)
                )
            }
        }

        // Live Controls Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161920))
                .padding(16.dp)
        ) {
            // Title descriptor
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "3D WIGGLE CONTROLLER",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                
                Text(
                    "SAVED TO GALLERY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FFCC)
                )
            }

            Text(
                "Mache mehr daraus mit KI!",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                capture.prompt,
                fontSize = 12.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("KI Prompt", capture.prompt)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Prompt kopiert!", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Prompt für Gemini (KI) kopieren", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun EmptyGalleryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Color(0xFF161920), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF232A38), RoundedCornerShape(16.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PermCameraMic,
            contentDescription = "No images",
            tint = Color(0xFF3B4861),
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "No stereoscopic animations captured yet",
            fontSize = 12.sp,
            color = Color(0xFF8A94A6)
        )
        Text(
            "Tap shutter to generate first 3D wiggle photo!",
            fontSize = 10.sp,
            color = Color(0xFF4E586E)
        )
    }
}

@Composable
fun HistoryItemCard(
    item: WiggleCapture,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val df = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(item.timestamp) { df.format(Date(item.timestamp)) }

    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF161920))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF232A38),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() }
    ) {
        // Thumbnail loading
        AsyncImage(
            model = item.imageAPath,
            contentDescription = "Wiggle capture",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Delete top-right trigger
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .background(Color(0xCC0D0F12), CircleShape)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = "Delete from history",
                tint = Color(0xFFFF4D4D),
                modifier = Modifier.size(14.dp)
            )
        }

        // Time indicator bottom-left
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color(0x990D0F12), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = formattedTime,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(context: Context): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation_proc ->
        addListener({
            try {
                continuation_proc.resumeWith(Result.success(get()))
            } catch (e: Exception) {
                continuation_proc.resumeWith(Result.failure(e))
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }
}

@Composable
fun CameraXViewfinder(
    lensId: String,
    zoomRatio: Float,
    onFrame: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember { ImageCapture.Builder().build() }

    var cameraControlState by remember { mutableStateOf<CameraControl?>(null) }

    // Unbind only this viewfinder's use cases when of disposal or lens alteration
    DisposableEffect(lensId) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbind(preview, imageCapture)
            } catch (e: Exception) {
                Log.e("CameraXViewfinder", "Error during granular viewfinder unbind", e)
            }
        }
    }

    // 1. Separate dynamic zoom tracking state (Smooth adjustment, absolutely no unbind camera calls!)
    LaunchedEffect(zoomRatio, cameraControlState) {
        val activeControl = cameraControlState ?: return@LaunchedEffect
        try {
            activeControl.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.e("CameraXViewfinder", "Error updating preview zoom smoothly dynamically", e)
        }
    }

    // 2. Camera engine initialization and lifecycle provider binding
    LaunchedEffect(lensId) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await(context)
            
            // Find cameras based on matching IDs
            var selectedSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val availableInfo = cameraProvider.availableCameraInfos
            for (info in availableInfo) {
                val details = info.toString()
                if (details.contains("id=$lensId") || details.contains("cameraId=$lensId")) {
                    selectedSelector = CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { it == info }
                        }
                        .build()
                    break
                }
            }

            try {
                cameraProvider.unbind(preview, imageCapture)
            } catch (e: Exception) {
                // Ignore if not bound
            }

            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selectedSelector,
                preview,
                imageCapture
            )

            preview.setSurfaceProvider(previewView.surfaceProvider)
            cameraControlState = camera.cameraControl
            
            // Apply initial zoom ratio gracefully
            camera.cameraControl.setZoomRatio(zoomRatio)

            // Dynamic background frame pulling for creating simulated stereoscopy pairs in-view
            while (true) {
                delay(120) // periodic high performance grab from screen
                previewView.bitmap?.let { screenBitmap ->
                    onFrame(screenBitmap)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraXViewfinder", "Failed binding CameraX lifecycle context safely", e)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

// Border utility for consistent Material design 
object BoxDefaults {
    fun borderStrokeWithSecondary() = BorderStroke(1.dp, Color(0xFF232A38))
}
