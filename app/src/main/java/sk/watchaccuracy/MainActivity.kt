@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package sk.watchaccuracy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import android.view.MotionEvent
import androidx.core.content.ContextCompat

private sealed interface Screen {
    data object Watches : Screen
    data object AddWatch : Screen
    data class WatchDetail(val watchId: String) : Screen
    data class Templates(val watchId: String) : Screen
    data class Camera(val watchId: String, val shape: DialShape) : Screen
    data class Review(val watchId: String, val shape: DialShape, val path: String, val capturedAt: Long, val read: ReadTime) : Screen
    data class Record(val watchId: String, val measurementId: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchAccuracyApp() }
    }
}

@Composable
private fun WatchAccuracyApp() {
    val context = LocalContext.current
    val repo = remember { WatchRepository(context) }
    var watches by remember { mutableStateOf(repo.load()) }
    var backStack by remember { mutableStateOf(listOf<Screen>(Screen.Watches)) }
    val screen = backStack.last()
    fun navigate(next: Screen) { backStack = backStack + next }
    fun goBack() { if (backStack.size > 1) backStack = backStack.dropLast(1) }
    var palette by remember { mutableStateOf(repo.palette()) }
    var language by remember { mutableStateOf(repo.language()) }
    var shutterPosition by remember { mutableStateOf(repo.shutterPosition()) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    val t = uiText(language)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) transferMessage = if (repo.exportArchive(uri, watches).isSuccess) t.exportSuccess else t.exportError
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repo.importArchive(uri).fold(
            onSuccess = { imported ->
                val merged = repo.merge(watches, imported)
                watches = merged
                repo.save(merged)
                transferMessage = t.importSuccess
            },
            onFailure = { transferMessage = t.importError }
        )
    }
    BackHandler(enabled = backStack.size > 1) { goBack() }
    val colors = when (palette) {
        AppPalette.CLASSIC -> darkColorScheme(
            primary = Color(0xFFD8B568), onPrimary = Color(0xFF17140D),
            secondary = Color(0xFFA8D39D), onSecondary = Color(0xFF10210F),
            background = Color(0xFF0B0D0A), onBackground = Color(0xFFF3EFE6),
            surface = Color(0xFF171A14), onSurface = Color(0xFFF3EFE6),
            surfaceVariant = Color(0xFF20241C), onSurfaceVariant = Color(0xFFBDB9AE),
            outline = Color(0xFF383D32), secondaryContainer = Color(0xFF2D3328)
        )
        AppPalette.BLUE -> lightColorScheme(primary = Color(0xFF172538), secondary = Color(0xFF527FA6), background = Color(0xFFF0F4F7), surface = Color(0xFFF9FCFF))
        AppPalette.MONO -> lightColorScheme(primary = Color(0xFF191919), secondary = Color(0xFF666666), background = Color(0xFFF3F3F3), surface = Color.White)
    }
    SideEffect {
        (context as? Activity)?.window?.apply {
            statusBarColor = colors.background.toArgb()
            navigationBarColor = colors.background.toArgb()
            androidx.core.view.WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = palette != AppPalette.CLASSIC
                isAppearanceLightNavigationBars = palette != AppPalette.CLASSIC
            }
        }
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                Screen.Watches -> WatchesScreen(t, watches, { navigate(Screen.WatchDetail(it)) }, { navigate(Screen.AddWatch) }, { navigate(Screen.Settings) })
                Screen.AddWatch -> AddWatchScreen(t, watches, ::goBack) { brand, model ->
                    watches = watches + Watch(brand = brand.trim(), model = model.trim()); repo.save(watches); backStack = listOf(Screen.Watches)
                }
                is Screen.WatchDetail -> WatchDetailScreen(t, watches.first { it.id == s.watchId }, ::goBack, { navigate(Screen.Templates(s.watchId)) }) { navigate(Screen.Record(s.watchId, it)) }
                is Screen.Templates -> TemplateScreen(t, ::goBack) { navigate(Screen.Camera(s.watchId, it)) }
                is Screen.Camera -> CameraScreen(t, s.shape, shutterPosition, ::goBack) { path, at ->
                    val watch = watches.first { it.id == s.watchId }
                    val isGmt = (watch.brand + " " + watch.model).contains("GMT", ignoreCase = true)
                    val read = ClockReader.read(context, path, at, isKnownGmt = isGmt)
                    navigate(Screen.Review(s.watchId, s.shape, path, at, if (isGmt) read.copy(layout = DialLayout.GMT, layoutConfidence = 1f) else read))
                }
                is Screen.Review -> ReviewScreen(t, s, ::goBack) { h, m, sec, layout ->
                    val measurement = Measurement(capturedAtMillis = s.capturedAt, dialHour = h, dialMinute = m, dialSecond = sec, photoPath = s.path, shape = s.shape, layout = layout)
                    watches = watches.map { if (it.id == s.watchId) it.copy(measurements = it.measurements + measurement) else it }
                    repo.save(watches); backStack = listOf(Screen.Watches, Screen.WatchDetail(s.watchId))
                }
                is Screen.Record -> RecordScreen(t, watches.first { it.id == s.watchId }, s.measurementId, ::goBack)
                Screen.Settings -> SettingsScreen(
                    t, palette, { palette = it; repo.savePalette(it) }, language, { language = it; repo.saveLanguage(it) },
                    shutterPosition, { shutterPosition = it; repo.saveShutterPosition(it) },
                    { exportLauncher.launch("watch-accuracy-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip") },
                    { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    transferMessage, { transferMessage = null }, ::goBack
                )
            }
        }
    }
}

@Composable private fun AppHeader(title: String, backLabel: String, back: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, backLabel) }
        Text(title, fontSize = 27.sp, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable private fun WatchesScreen(t: UiText, watches: List<Watch>, open: (String) -> Unit, add: () -> Unit, settings: () -> Unit) {
    Scaffold(topBar = { AppHeader(t.watches, t.back, action = { IconButton(onClick = settings) { Icon(Icons.Default.Settings, t.settings) } }) }, bottomBar = { PrimaryBottomButton(t.addWatch, Icons.Default.Add, add) }) { pad ->
        if (watches.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(t.noWatches) }
        else LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(watches) { watch ->
                val accent = MaterialTheme.colorScheme.secondary
                val clockHand = MaterialTheme.colorScheme.onSurface
                Card(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)).clickable { open(watch.id) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(58.dp)) { drawCircle(accent, style = Stroke(2.dp.toPx())); drawLine(clockHand, center, center.copy(y = center.y - 17.dp.toPx()), 2.dp.toPx()); drawLine(clockHand, center, center.copy(x = center.x + 14.dp.toPx(), y = center.y + 8.dp.toPx()), 2.dp.toPx()) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(watch.brand, fontSize = 19.sp, fontFamily = FontFamily.Serif); Text("${watch.model} · ${watch.measurements.size} ${t.measurements}", style = MaterialTheme.typography.bodySmall) }
                    Text(latestRate(t, watch), color = MaterialTheme.colorScheme.secondary)
                } }
            }
        }
    }
}

private fun latestRate(t: UiText, w: Watch): String {
    if (w.measurements.size < 2) return "—"
    val v = DeviationCalculator.secondsPerDay(w.measurements[w.measurements.lastIndex - 1], w.measurements.last()) ?: return "—"
    return String.format(Locale.getDefault(), "%+.1f %s", v, t.secondsPerDay)
}

@Composable private fun AddWatchScreen(t: UiText, watches: List<Watch>, back: () -> Unit, save: (String, String) -> Unit) {
    var brand by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
    var brandsOpen by remember { mutableStateOf(false) }; var modelsOpen by remember { mutableStateOf(false) }
    val brands = (knownModels.keys + watches.map { it.brand }).distinct().filter { brand.isBlank() || it.contains(brand, true) }
    val models = ((knownModels.entries.firstOrNull { it.key.equals(brand, true) }?.value ?: emptyList()) + watches.filter { it.brand.equals(brand, true) }.map { it.model }).distinct().filter { model.isBlank() || it.contains(model, true) }
    Scaffold(topBar = { AppHeader(t.addWatch, t.back, back) }, bottomBar = { PrimaryBottomButton(t.saveWatch, Icons.Default.Check, { if (brand.isNotBlank() && model.isNotBlank()) save(brand, model) }, brand.isNotBlank() && model.isNotBlank()) }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box { OutlinedTextField(brand, { brand = it; model = ""; brandsOpen = true }, Modifier.fillMaxWidth(), label = { Text(t.brand) }, placeholder = { Text(t.chooseBrand) }, leadingIcon = { Icon(Icons.Default.Watch, null) }, trailingIcon = { IconButton({ brandsOpen = !brandsOpen }) { Icon(Icons.Default.ArrowDropDown, null) } }, singleLine = true)
                DropdownMenu(brandsOpen && brands.isNotEmpty(), { brandsOpen = false }, Modifier.fillMaxWidth(.88f)) { brands.forEach { value -> DropdownMenuItem({ Text(value) }, { brand = value; model = ""; brandsOpen = false; modelsOpen = true }) } }
            }
            Box { OutlinedTextField(model, { model = it; modelsOpen = true }, Modifier.fillMaxWidth(), label = { Text(t.model) }, placeholder = { Text(t.chooseModel) }, trailingIcon = { IconButton({ modelsOpen = !modelsOpen }) { Icon(Icons.Default.ArrowDropDown, null) } }, singleLine = true)
                DropdownMenu(modelsOpen && models.isNotEmpty(), { modelsOpen = false }, Modifier.fillMaxWidth(.88f)) { models.forEach { value -> DropdownMenuItem({ Text(value) }, { model = value; modelsOpen = false }) } }
            }
            Text(t.recentBrands, style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { watches.map { it.brand }.distinct().take(5).forEach { AssistChip(onClick = { brand = it }, label = { Text(it) }) } }
        }
    }
}

@Composable private fun WatchDetailScreen(t: UiText, watch: Watch, back: () -> Unit, newMeasurement: () -> Unit, openRecord: (String) -> Unit) {
    Scaffold(topBar = { AppHeader("${watch.brand} ${watch.model}", t.back, back) }, bottomBar = { PrimaryBottomButton(t.newMeasurement, Icons.Default.CameraAlt, newMeasurement) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 18.dp)) {
            item { Card(shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(t.lastDeviation); Text(latestRate(t, watch), fontSize = 30.sp, fontFamily = FontFamily.Serif) } }; Spacer(Modifier.height(17.dp)); Text(t.measurementHistory, style = MaterialTheme.typography.labelMedium) }
            items(watch.measurements.reversed()) { m -> MeasurementRow(t, watch, m) { openRecord(m.id) } }
        }
    }
}

@Composable private fun MeasurementRow(t: UiText, watch: Watch, measurement: Measurement, open: () -> Unit) {
    val index = watch.measurements.indexOfFirst { it.id == measurement.id }
    val rate = if (index > 0) DeviationCalculator.secondsPerDay(watch.measurements[index - 1], measurement)?.let { String.format(Locale.getDefault(), "%+.1f", it) } ?: "—" else "—"
    val accent = MaterialTheme.colorScheme.primary; val hand = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(42.dp)) { drawCircle(accent, style = Stroke(1.8.dp.toPx())); drawLine(hand, center, center.copy(y = center.y - 11.dp.toPx()), 1.6.dp.toPx()); drawLine(hand, center, center.copy(x = center.x + 9.dp.toPx(), y = center.y + 5.dp.toPx()), 1.6.dp.toPx()) }
        Column(Modifier.padding(start = 13.dp).weight(1f)) { Text(date(measurement.capturedAtMillis)); Text("${t.photo} ${clock(measurement.capturedAtMillis)} · ${t.dial} ${dialTime(measurement)}", style = MaterialTheme.typography.bodySmall) }; Text(rate, color = MaterialTheme.colorScheme.secondary)
    }
    HorizontalDivider()
}

@Composable private fun TemplateScreen(t: UiText, back: () -> Unit, next: (DialShape) -> Unit) {
    var selected by remember { mutableStateOf(DialShape.ROUND) }
    Scaffold(topBar = { AppHeader(t.dialShape, t.back, back) }, bottomBar = { PrimaryBottomButton(t.continueCamera, Icons.Default.ArrowForward, { next(selected) }) }) { pad ->
        Column(Modifier.padding(pad).padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(t.shapeHelp)
            ShapeChoice(DialShape.ROUND, t.round, "Rolex Oyster Perpetual", selected) { selected = it }
            ShapeChoice(DialShape.SQUARE, t.square, "TAG Heuer Monaco", selected) { selected = it }
            ShapeChoice(DialShape.RECTANGLE, t.rectangle, "Jaeger-LeCoultre Reverso", selected) { selected = it }
        }
    }
}

@Composable private fun ShapeChoice(shape: DialShape, title: String, example: String, selected: DialShape, choose: (DialShape) -> Unit) {
    val outline = when (shape) { DialShape.ROUND -> CircleShape; DialShape.SQUARE -> RoundedCornerShape(12.dp); DialShape.RECTANGLE -> RoundedCornerShape(9.dp) }
    Card(Modifier.fillMaxWidth().clickable { choose(shape) }.then(if (selected == shape) Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(17.dp)) else Modifier), colors = CardDefaults.cardColors(containerColor = if (selected == shape) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(if (shape == DialShape.RECTANGLE) 48.dp else 64.dp, 64.dp).border(2.dp, MaterialTheme.colorScheme.secondary, outline)); Column(Modifier.padding(start = 18.dp).weight(1f)) { Text(title, fontSize = 19.sp); Text(example, style = MaterialTheme.typography.bodySmall) }; if (selected == shape) Icon(Icons.Default.CheckCircle, null) }
    }
}

@Composable private fun CameraScreen(t: UiText, shape: DialShape, shutterPosition: ShutterPosition, back: () -> Unit, captured: (String, Long) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val shutterSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }
    var permitted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if (!permitted) request.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permitted) androidx.compose.ui.viewinterop.AndroidView(factory = { ctx -> PreviewView(ctx).also { view ->
            view.scaleType = PreviewView.ScaleType.FIT_CENTER
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also { it.surfaceProvider = view.surfaceProvider }
                imageCapture = ImageCapture.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(ImageCapture.FLASH_MODE_OFF).build()
                provider.unbindAll()
                boundCamera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                val camera = boundCamera ?: return@addListener
                val zoom = 3f.coerceIn(camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f, camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 3f)
                camera.cameraControl.setZoomRatio(zoom)
                fun focus(x: Float, y: Float) {
                    val point = view.meteringPointFactory.createPoint(x, y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                    camera.cameraControl.startFocusAndMetering(action)
                }
                view.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) { focus(event.x, event.y); view.performClick(); true } else true
                }
                view.postDelayed({ focus(view.width / 2f, view.height / 2f) }, 700)
            }, ContextCompat.getMainExecutor(ctx))
        } }, modifier = Modifier.fillMaxSize()) else Text(t.cameraPermission, color = Color.White, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(back, Modifier.background(Color.Black.copy(alpha=.45f), CircleShape)) { Icon(Icons.Default.Close, t.close, tint = Color.White) }
            IconButton({ torchOn = !torchOn; boundCamera?.cameraControl?.enableTorch(torchOn) }, Modifier.background(Color.Black.copy(alpha=.45f), CircleShape)) { Icon(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, t.flash, tint = if (torchOn) Color(0xFFD1AD68) else Color.White) }
        }
        CameraGuide(shape, Modifier.align(Alignment.Center))
        Text(t.cameraGuide, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp))
        Button(enabled = !capturing, onClick = {
            val capture = imageCapture ?: return@Button
            capturing = true
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            val requestedAt = System.currentTimeMillis()
            val file = File(context.filesDir, "dial_${requestedAt}.jpg")
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val completedAt = System.currentTimeMillis()
                    // With the low-latency capture mode exposure lies between the
                    // request and JPEG completion. The midpoint avoids assigning the
                    // long JPEG-processing delay to the photographed hand position.
                    val exposureAt = requestedAt + (completedAt - requestedAt) / 2
                    val cropped = PhotoCropper.cropToTemplate(file.absolutePath, shape)
                    captured(cropped, exposureAt)
                }
                override fun onError(exception: ImageCaptureException) { capturing = false }
            })
        }, modifier = Modifier.align(when (shutterPosition) { ShutterPosition.LEFT -> Alignment.BottomStart; ShutterPosition.CENTER -> Alignment.BottomCenter; ShutterPosition.RIGHT -> Alignment.BottomEnd }).navigationBarsPadding().padding(24.dp).size(76.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {}
    }
}

@Composable private fun CameraGuide(shape: DialShape, modifier: Modifier) {
    val dims = when (shape) {
        DialShape.RECTANGLE -> Modifier.fillMaxWidth(.50f).aspectRatio(2f / 3f)
        else -> Modifier.fillMaxWidth(.72f).aspectRatio(1f)
    }
    val corner = when (shape) { DialShape.ROUND -> CircleShape; DialShape.SQUARE -> RoundedCornerShape(34.dp); DialShape.RECTANGLE -> RoundedCornerShape(24.dp) }
    val guideColor = Color(0xFFD1AD68)
    Box(modifier.then(dims).border(2.dp, guideColor, corner)) {
        if (shape == DialShape.ROUND) {
            Canvas(Modifier.fillMaxSize()) {
                val tick = size.minDimension * .055f
                val stroke = 2.dp.toPx()
                drawLine(guideColor, center.copy(y = 0f), center.copy(y = tick), stroke)
                drawLine(guideColor, center.copy(y = size.height), center.copy(y = size.height - tick), stroke)
                drawLine(guideColor, center.copy(x = 0f), center.copy(x = tick), stroke)
                drawLine(guideColor, center.copy(x = size.width), center.copy(x = size.width - tick), stroke)
                val cross = size.minDimension * .035f
                drawLine(guideColor, center.copy(x = center.x - cross), center.copy(x = center.x + cross), stroke)
                drawLine(guideColor, center.copy(y = center.y - cross), center.copy(y = center.y + cross), stroke)
            }
            Text("12", color = guideColor, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
            Text("3", color = guideColor, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            Text("6", color = guideColor, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            Text("9", color = guideColor, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        }
    }
}

@Composable private fun ReviewScreen(t: UiText, s: Screen.Review, retake: () -> Unit, save: (Int, Int, Int, DialLayout) -> Unit) {
    var h by remember { mutableStateOf(s.read.hour.takeIf { it >= 0 }?.toString().orEmpty()) }; var m by remember { mutableStateOf(s.read.minute.takeIf { it >= 0 }?.toString().orEmpty()) }; var sec by remember { mutableStateOf(s.read.second.takeIf { it >= 0 }?.toString().orEmpty()) }
    var layout by remember { mutableStateOf(s.read.layout) }; var layoutOpen by remember { mutableStateOf(false) }
    fun layoutName(value: DialLayout) = when (value) { DialLayout.CLASSIC -> t.layoutClassic; DialLayout.GMT -> t.layoutGmt; DialLayout.SMALL_SECONDS -> t.layoutSmallSeconds; DialLayout.REGULATOR -> t.layoutRegulator; DialLayout.JUMP_HOUR -> t.layoutJumpHour }
    val validTime = h.toIntOrNull()?.let { it in 0..23 } == true &&
        m.toIntOrNull()?.let { it in 0..59 } == true && sec.toIntOrNull()?.let { it in 0..59 } == true
    Scaffold(topBar = { AppHeader(t.measurementCheck, t.back, retake) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ManualDialPhoto(t, s.path, s.shape, s.capturedAt, s.read, { h = it.toString() }, { m = it.toString() }, { sec = it.toString() })
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t.dialTime, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TimeInput(t.hours, h, { h = it }, Modifier.weight(1f)); TimeInput(t.minutes, m, { m = it }, Modifier.weight(1f)); TimeInput(t.seconds, sec, { sec = it }, Modifier.weight(1f)) }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary); Text(t.moreDetails, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t.photoTime, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${date(s.capturedAt)} · ${clockMillis(s.capturedAt)}", fontSize = 19.sp)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(t.detectedLayout, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box { OutlinedButton(onClick = { layoutOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(layoutName(layout), modifier = Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
                        DropdownMenu(layoutOpen, { layoutOpen = false }) { DialLayout.entries.forEach { value -> DropdownMenuItem({ Text(layoutName(value)) }, { layout = value; layoutOpen = false }) } }
                    }
                    Text("${t.detectionConfidence}: ${(s.read.layoutConfidence * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, color = if (s.read.layoutConfidence < .75f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
            }
            Button(onClick = { save(h.toInt(), m.toInt(), sec.toInt(), layout) }, enabled = validTime, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(9.dp)); Text(t.saveMeasurement, fontSize = 17.sp) }
        }
    }
}

@Composable private fun ManualDialPhoto(t: UiText, path: String, shape: DialShape, capturedAt: Long, read: ReadTime, setHour: (Int) -> Unit, setMinute: (Int) -> Unit, setSecond: (Int) -> Unit) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    var center by remember(path) { mutableStateOf<Offset?>(null) }
    var hands by remember(path) { mutableStateOf(emptyList<Offset>()) }
    var viewSize by remember(path) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val latestCenter by rememberUpdatedState(center)
    val latestHands by rememberUpdatedState(hands)
    val photoModifier = when (shape) { DialShape.ROUND -> Modifier.size(230.dp).clip(CircleShape); DialShape.SQUARE -> Modifier.size(230.dp).clip(RoundedCornerShape(18.dp)); DialShape.RECTANGLE -> Modifier.size(154.dp, 231.dp).clip(RoundedCornerShape(15.dp)) }

    fun angle(origin: Offset, point: Offset) = (atan2((point.x - origin.x).toDouble(), (origin.y - point.y).toDouble()) * 180.0 / PI + 360.0) % 360.0
    fun updateTime(origin: Offset, points: List<Offset>) {
        if (points.size != 3) return
        val minute = (angle(origin, points[1]) / 6.0).roundToInt() % 60
        val second = (angle(origin, points[2]) / 6.0).roundToInt() % 60
        val hourAngle = angle(origin, points[0])
        val h12 = (((hourAngle - minute * .5 + 15.0) / 30.0).toInt() + 12) % 12
        val reference = Calendar.getInstance().apply { timeInMillis = capturedAt }.get(Calendar.HOUR_OF_DAY)
        val hour = listOf(h12, h12 + 12).minBy { kotlin.math.abs(it - reference) }
        setHour(hour); setMinute(minute); setSecond(second)
    }
    LaunchedEffect(viewSize, read) {
        if (viewSize.width <= 0 || viewSize.height <= 0 || center != null) return@LaunchedEffect
        val origin = Offset(viewSize.width * read.centerX, viewSize.height * read.centerY)
        val minDimension = minOf(viewSize.width, viewSize.height).toFloat()
        fun endpoint(detected: Int, fallback: Double, length: Float): Offset {
            val degrees = if (detected >= 0) detected.toDouble() else fallback
            val radians = degrees * PI / 180.0
            return Offset((origin.x + kotlin.math.sin(radians) * length).toFloat(), (origin.y - kotlin.math.cos(radians) * length).toFloat())
        }
        center = origin
        hands = listOf(
            endpoint(read.hourImageAngle, (read.hour % 12) * 30.0 + read.minute * .5, minDimension * .28f),
            endpoint(read.minuteImageAngle, read.minute * 6.0, minDimension * .38f),
            endpoint(read.secondImageAngle, read.second * 6.0, minDimension * .42f)
        )
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t.handSetup, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) { Text("AI", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary) }
        }
        Text(t.aiHandSuggestion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        Box(photoModifier.onSizeChanged { viewSize = it }.pointerInput(path, viewSize) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val origin = latestCenter ?: return@awaitEachGesture
                val points = listOf(origin) + latestHands
                val selected = points.indices.minByOrNull { (points[it] - down.position).getDistance() } ?: return@awaitEachGesture
                if ((points[selected] - down.position).getDistance() > 38.dp.toPx()) return@awaitEachGesture
                drag(down.id) { change ->
                    val position = Offset(change.position.x.coerceIn(0f, size.width.toFloat()), change.position.y.coerceIn(0f, size.height.toFloat()))
                    if (selected == 0) {
                        center = position
                        updateTime(position, latestHands)
                    } else {
                        val changed = latestHands.toMutableList()
                        if (changed.size == 3) {
                            changed[selected - 1] = position
                            hands = changed
                            updateTime(latestCenter ?: origin, changed)
                        }
                    }
                    change.consume()
                }
            }
        }) {
            if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Canvas(Modifier.matchParentSize()) {
                val colors = listOf(Color(0xFFD1AD68), Color(0xFF66BBFF), Color(0xFF66DD88), Color(0xFFFF665F))
                val origin = center
                if (origin != null) {
                    hands.forEachIndexed { index, point -> val color = colors[index + 1]; drawLine(color, origin, point, 3.dp.toPx()); drawCircle(Color.White, 10.dp.toPx(), point); drawCircle(color, 7.dp.toPx(), point) }
                    drawCircle(Color.White, 10.dp.toPx(), origin); drawCircle(colors[0], 7.dp.toPx(), origin); drawCircle(Color.Black, 3.dp.toPx(), origin)
                }
            }
        }
    }
}

@Composable private fun TimeInput(label: String, value: String, change: (String) -> Unit, modifier: Modifier) { OutlinedTextField(value, change, modifier, label = { Text(label) }, singleLine = true) }
@Composable private fun Photo(path: String, shape: DialShape) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    val photoModifier = when (shape) {
        DialShape.ROUND -> Modifier.size(245.dp).clip(CircleShape)
        DialShape.SQUARE -> Modifier.size(245.dp).clip(RoundedCornerShape(18.dp))
        DialShape.RECTANGLE -> Modifier.size(174.dp, 260.dp).clip(RoundedCornerShape(15.dp))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, photoModifier, contentScale = ContentScale.Crop)
        else Box(photoModifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable private fun RecordScreen(t: UiText, watch: Watch, id: String, back: () -> Unit) {
    val m = watch.measurements.first { it.id == id }; val index = watch.measurements.indexOf(m); val rate = if (index > 0) DeviationCalculator.secondsPerDay(watch.measurements[index-1], m) else null
    Scaffold(topBar = { AppHeader(t.recordDetail, t.back, back) }) { pad -> Column(Modifier.padding(pad).padding(18.dp)) { Photo(m.photoPath, m.shape); Spacer(Modifier.height(15.dp)); Card(shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(17.dp)) { Text(t.calculatedDeviation); Text(rate?.let { String.format(Locale.getDefault(), "%+.1f %s", it, t.secondsPerDay) } ?: t.first, fontSize = 28.sp, fontFamily = FontFamily.Serif) } }; DataRow(t.measurementDate, date(m.capturedAtMillis)); DataRow(t.photoTime, clockMillis(m.capturedAtMillis)); DataRow(t.dialTime, dialTime(m)) } }
}

@Composable private fun DataRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) { Text(label, Modifier.weight(1f)); Text(value) }; HorizontalDivider() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(t: UiText, palette: AppPalette, setPalette: (AppPalette) -> Unit, language: String, setLanguage: (String) -> Unit, shutterPosition: ShutterPosition, setShutterPosition: (ShutterPosition) -> Unit, exportData: () -> Unit, importData: () -> Unit, transferMessage: String?, clearMessage: () -> Unit, back: () -> Unit) {
    var lang by remember { mutableStateOf(language) }
    Scaffold(topBar = { AppHeader(t.settings, t.back, back) }) { pad -> Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(t.environment, style = MaterialTheme.typography.labelMedium); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Language, null); Text(t.language, Modifier.padding(start = 12.dp).weight(1f)); SingleChoiceSegmentedButtonRow { listOf("sk" to "Slovenčina", "en" to "English").forEachIndexed { i, pair -> SegmentedButton(selected = lang == pair.first, onClick = { lang = pair.first; setLanguage(pair.first) }, shape = SegmentedButtonDefaults.itemShape(i,2)) { Text(pair.second) } } } }
        Text(t.colorCombination, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppPalette.entries.forEach { p -> PaletteChoice(t, p, palette == p, { setPalette(p) }, Modifier.weight(1f)) } }
        Text(t.shutterPosition, style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { ShutterPosition.entries.forEachIndexed { i, position -> SegmentedButton(selected = shutterPosition == position, onClick = { setShutterPosition(position) }, shape = SegmentedButtonDefaults.itemShape(i, ShutterPosition.entries.size), modifier = Modifier.weight(1f)) { Text(when (position) { ShutterPosition.LEFT -> t.left; ShutterPosition.CENTER -> t.center; ShutterPosition.RIGHT -> t.right }) } } }
        Text(t.backup, style = MaterialTheme.typography.labelMedium)
        Text(t.backupDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(exportData, Modifier.weight(1f)) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text(t.exportData) }
            OutlinedButton(importData, Modifier.weight(1f)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(t.importData) }
        }
        transferMessage?.let { message -> AssistChip(onClick = clearMessage, label = { Text(message) }, leadingIcon = { Icon(Icons.Default.Info, null) }) }
        Text(t.permissions, style = MaterialTheme.typography.labelMedium); PermissionRow(Icons.Default.CameraAlt, t.camera, t.cameraReason, true); PermissionRow(Icons.Default.Photo, t.photos, t.photosReason, true); PermissionRow(Icons.Default.Notifications, t.reminders, t.remindersReason, false)
    } }
}

@Composable private fun PaletteChoice(t: UiText, palette: AppPalette, selected: Boolean, choose: () -> Unit, modifier: Modifier) {
    val pair = when (palette) { AppPalette.CLASSIC -> Color(0xFF22241E) to Color(0xFFD1AD68); AppPalette.BLUE -> Color(0xFF172538) to Color(0xFF7CA8CE); AppPalette.MONO -> Color(0xFF191919) to Color(0xFFB7B7B7) }
    val label = when (palette) { AppPalette.CLASSIC -> t.classic; AppPalette.BLUE -> t.blue; AppPalette.MONO -> t.monochrome }
    Card(modifier.clickable(onClick = choose).then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(14.dp)) else Modifier), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Row { Box(Modifier.size(22.dp).background(pair.first, CircleShape)); Box(Modifier.offset(x=(-5).dp).size(22.dp).background(pair.second, CircleShape)) }; Spacer(Modifier.height(7.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

@Composable private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, initial: Boolean) { var on by remember { mutableStateOf(initial) }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Column(Modifier.padding(start=12.dp).weight(1f)) { Text(title); Text(sub, style=MaterialTheme.typography.bodySmall) }; Switch(on, {on=it}) } }
@Composable private fun PrimaryBottomButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit, enabled: Boolean = true) { Surface(color = MaterialTheme.colorScheme.background) { Button(click, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp).height(58.dp), enabled = enabled, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Icon(icon, null); Spacer(Modifier.width(9.dp)); Text(text, fontSize = 17.sp) } } }

private fun date(ms: Long) = SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(Date(ms))
private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun clockMillis(ms: Long) = SimpleDateFormat("HH:mm:ss,SSS", Locale.getDefault()).format(Date(ms))
private fun dialTime(m: Measurement) = String.format(Locale.getDefault(), "%02d:%02d:%02d", m.dialHour, m.dialMinute, m.dialSecond)
