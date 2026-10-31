package ru.adsrv.frpjump.ui.enroll

import android.os.Build
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.enroll.QrDecoder
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.TorchGlyph
import ru.adsrv.frpjump.ui.findActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera, full screen, reading QR codes until one is an enroll line.
 * [onScanned] gets every code read and answers whether it took it; the
 * first one taken ticks and stops the scanning (the caller closes this).
 * Other codes show [R.string.scan_not_enroll] while scanning goes on.
 * Needs the camera permission already granted.
 */
@Composable
fun QrScannerScreen(onScanned: (String) -> Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val take by rememberUpdatedState(onScanned)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    /** A line about the last code or the torch, under the viewfinder. */
    var message by remember { mutableStateOf<Int?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    BackHandler(onBack = onClose)

    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(previewView, lifecycleOwner) {
        val state = previewView.previewStreamState
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        state.observe(lifecycleOwner, observer)
        onDispose { state.removeObserver(observer) }
    }

    // The camera is dark whatever the theme: light status and navigation bar icons over it.
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { bars.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars.isAppearanceLightNavigationBars = it }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val main = ContextCompat.getMainExecutor(context)
        val analysisThread = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        var disposed = false
        var bound: Pair<ProcessCameraProvider, Array<androidx.camera.core.UseCase>>? = null

        fun onText(text: String) {
            if (disposed || done.get()) return
            if (take(text)) {
                done.set(true)
                val tick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
                view.performHapticFeedback(tick)
            } else {
                message = R.string.scan_not_enroll
            }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    // Only the newest frame is decoded; the ones that came meanwhile are dropped.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(analysisThread, QrAnalyzer(done) { text -> main.execute { onText(text) } })
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                bound = provider to arrayOf(preview, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "camera not opened: ${e.javaClass.simpleName}")
                failed = true
            }
        }, main)

        onDispose {
            disposed = true
            bound?.let { (provider, cases) ->
                // No frame may reach the executor once it is shut down.
                cases.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
                provider.unbind(*cases)
            }
            camera = null
            analysisThread.shutdown()
        }
    }

    fun switchTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            message = R.string.scan_no_torch
            return
        }
        val on = !torch
        val result = cam.cameraControl.enableTorch(on)
        result.addListener({
            try {
                result.get()
                torch = on
                if (message == R.string.scan_torch_failed) message = null
            } catch (e: Exception) {
                Log.w(TAG, "torch not switched: ${e.javaClass.simpleName}")
                message = R.string.scan_torch_failed
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // The camera takes a moment to show its first frame.
        if (!streaming && !failed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 3.dp)
        }
        Viewfinder(hint = if (failed) R.string.scan_camera_failed else R.string.scan_hint, message = message)
        Row(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose, colors = overlayButton(false)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            IconButton(onClick = ::switchTorch, enabled = camera != null, colors = overlayButton(torch)) {
                Icon(TorchGlyph, contentDescription = stringResource(if (torch) R.string.scan_torch_off else R.string.scan_torch_on))
            }
        }
    }
}

/** Light on the camera; filled while [on] (the torch is lit). */
@Composable
private fun overlayButton(on: Boolean) = IconButtonDefaults.iconButtonColors(
    containerColor = if (on) Color.White else Color.Black.copy(alpha = 0.45f),
    contentColor = if (on) Color.Black else Color.White,
    disabledContainerColor = Color.Black.copy(alpha = 0.3f),
    disabledContentColor = Color.White.copy(alpha = 0.4f),
)

/** The dimmed camera with a clear square in the middle, its corners marked, the [hint] and [message] under it. */
@Composable
private fun Viewfinder(hint: Int, message: Int?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight) * 0.7f
        val top = (maxHeight - side) / 2
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen, so the square can be cut out of the dimming.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val s = side.toPx()
            val origin = Offset((size.width - s) / 2, (size.height - s) / 2)
            val radius = 20.dp.toPx()
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(Color.Transparent, origin, GeometrySize(s, s), CornerRadius(radius), blendMode = BlendMode.Clear)
            val arm = s * 0.14f
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            for ((cx, cy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                val x = origin.x + cx * s
                val y = origin.y + cy * s
                val dx = if (cx == 0f) 1f else -1f
                val dy = if (cy == 0f) 1f else -1f
                drawLine(Color.White, Offset(x, y + dy * radius), Offset(x, y + dy * arm), stroke.width, StrokeCap.Round)
                drawLine(Color.White, Offset(x + dx * radius, y), Offset(x + dx * arm, y), stroke.width, StrokeCap.Round)
                drawArc(
                    Color.White,
                    startAngle = 90f * when (cx to cy) { 0f to 0f -> 2; 1f to 0f -> 3; 1f to 1f -> 0; else -> 1 },
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(if (cx == 0f) x else x - 2 * radius, if (cy == 0f) y else y - 2 * radius),
                    size = GeometrySize(2 * radius, 2 * radius),
                    style = stroke,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = top + side + 20.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A shadow keeps the white text readable over a bright picture.
            Text(
                stringResource(hint),
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f)),
            )
            message?.let {
                val colors = AppTheme.colors
                Text(
                    stringResource(it),
                    color = colors.onErrorContainer,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(colors.errorContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Decodes frames on the analysis thread, one at a time (the newest; see
 * the backpressure strategy), and hands each code read to [onText] until
 * [done].
 */
private class QrAnalyzer(private val done: AtomicBoolean, private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private var luma = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            if (done.get()) return
            val plane = it.planes[0]
            val buffer = plane.buffer
            val stride = plane.rowStride
            // The plane's last row may stop at the width: size the copy by full rows.
            val needed = stride * it.height
            if (luma.size != needed) luma = ByteArray(needed)
            buffer.rewind()
            buffer.get(luma, 0, minOf(buffer.remaining(), needed))
            decoder.decode(luma, stride, it.width, it.height)?.let(onText)
        }
    }
}

private const val TAG = "QrScanner"
