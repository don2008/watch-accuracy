@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package sk.watchaccuracy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
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
    var screen by remember { mutableStateOf<Screen>(Screen.Watches) }
    var palette by remember { mutableStateOf(repo.palette()) }
    var language by remember { mutableStateOf(repo.language()) }
    val t = uiText(language)
    val colors = when (palette) {
        AppPalette.CLASSIC -> lightColorScheme(primary = Color(0xFF22241E), secondary = Color(0xFF9A7535), background = Color(0xFFF4F1E8), surface = Color(0xFFFFFDF7))
        AppPalette.BLUE -> lightColorScheme(primary = Color(0xFF172538), secondary = Color(0xFF527FA6), background = Color(0xFFF0F4F7), surface = Color(0xFFF9FCFF))
        AppPalette.MONO -> lightColorScheme(primary = Color(0xFF191919), secondary = Color(0xFF666666), background = Color(0xFFF3F3F3), surface = Color.White)
    }
    SideEffect {
        (context as? Activity)?.window?.apply {
            statusBarColor = colors.background.toArgb()
            navigationBarColor = colors.background.toArgb()
        }
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                Screen.Watches -> WatchesScreen(t, watches, { screen = Screen.WatchDetail(it) }, { screen = Screen.AddWatch }, { screen = Screen.Settings })
                Screen.AddWatch -> AddWatchScreen(t, watches, { screen = Screen.Watches }) { brand, model ->
                    watches = watches + Watch(brand = brand.trim(), model = model.trim()); repo.save(watches); screen = Screen.Watches
                }
                is Screen.WatchDetail -> WatchDetailScreen(t, watches.first { it.id == s.watchId }, { screen = Screen.Watches }, { screen = Screen.Templates(s.watchId) }) { screen = Screen.Record(s.watchId, it) }
                is Screen.Templates -> TemplateScreen(t, { screen = Screen.WatchDetail(s.watchId) }) { screen = Screen.Camera(s.watchId, it) }
                is Screen.Camera -> CameraScreen(t, s.shape, { screen = Screen.Templates(s.watchId) }) { path, at -> screen = Screen.Review(s.watchId, s.shape, path, at, ClockReader.read(path, at)) }
                is Screen.Review -> ReviewScreen(t, s, { screen = Screen.Camera(s.watchId, s.shape) }) { h, m, sec ->
                    val measurement = Measurement(capturedAtMillis = s.capturedAt, dialHour = h, dialMinute = m, dialSecond = sec, photoPath = s.path, shape = s.shape)
                    watches = watches.map { if (it.id == s.watchId) it.copy(measurements = it.measurements + measurement) else it }
                    repo.save(watches); screen = Screen.WatchDetail(s.watchId)
                }
                is Screen.Record -> RecordScreen(t, watches.first { it.id == s.watchId }, s.measurementId) { screen = Screen.WatchDetail(s.watchId) }
                Screen.Settings -> SettingsScreen(t, palette, { palette = it; repo.savePalette(it) }, language, { language = it; repo.saveLanguage(it) }) { screen = Screen.Watches }
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
                Card(Modifier.fillMaxWidth().clickable { open(watch.id) }) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(54.dp)) { drawCircle(accent, style = Stroke(2.dp.toPx())); drawLine(Color.DarkGray, center, center.copy(y = center.y - 16.dp.toPx()), 2.dp.toPx()); drawLine(Color.DarkGray, center, center.copy(x = center.x + 13.dp.toPx(), y = center.y + 7.dp.toPx()), 2.dp.toPx()) }
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
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(date(measurement.capturedAtMillis)); Text("${t.photo} ${clock(measurement.capturedAtMillis)} · ${t.dial} ${dialTime(measurement)}", style = MaterialTheme.typography.bodySmall) }; Text(rate, color = MaterialTheme.colorScheme.secondary)
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

@Composable private fun CameraScreen(t: UiText, shape: DialShape, back: () -> Unit, captured: (String, Long) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var permitted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if (!permitted) request.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permitted) androidx.compose.ui.viewinterop.AndroidView(factory = { ctx -> PreviewView(ctx).also { view ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({ val provider = future.get(); val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }; imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(ImageCapture.FLASH_MODE_OFF).build(); provider.unbindAll(); boundCamera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture) }, ContextCompat.getMainExecutor(ctx))
        } }, modifier = Modifier.fillMaxSize()) else Text(t.cameraPermission, color = Color.White, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(back, Modifier.background(Color.Black.copy(alpha=.45f), CircleShape)) { Icon(Icons.Default.Close, t.close, tint = Color.White) }
            IconButton({ torchOn = !torchOn; boundCamera?.cameraControl?.enableTorch(torchOn) }, Modifier.background(Color.Black.copy(alpha=.45f), CircleShape)) { Icon(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, t.flash, tint = if (torchOn) Color(0xFFD1AD68) else Color.White) }
        }
        CameraGuide(shape, Modifier.align(Alignment.Center))
        Text(t.cameraGuide, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp))
        Button(onClick = { val capture = imageCapture ?: return@Button; val at = System.currentTimeMillis(); val file = File(context.filesDir, "dial_${at}.jpg"); capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback { override fun onImageSaved(result: ImageCapture.OutputFileResults) { val cropped = PhotoCropper.cropToTemplate(file.absolutePath, shape); captured(cropped, at) }; override fun onError(exception: ImageCaptureException) {} }) }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).size(76.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {}
    }
}

@Composable private fun CameraGuide(shape: DialShape, modifier: Modifier) {
    val dims = when (shape) { DialShape.RECTANGLE -> Modifier.size(190.dp, 285.dp); else -> Modifier.size(270.dp) }
    val corner = when (shape) { DialShape.ROUND -> CircleShape; DialShape.SQUARE -> RoundedCornerShape(34.dp); DialShape.RECTANGLE -> RoundedCornerShape(24.dp) }
    Box(modifier.then(dims).border(2.dp, Color(0xFFD1AD68), corner))
}

@Composable private fun ReviewScreen(t: UiText, s: Screen.Review, retake: () -> Unit, save: (Int, Int, Int) -> Unit) {
    var h by remember { mutableStateOf(s.read.hour.toString()) }; var m by remember { mutableStateOf(s.read.minute.toString()) }; var sec by remember { mutableStateOf(s.read.second.toString()) }
    Scaffold(topBar = { AppHeader(t.measurementCheck, t.back, retake) }, bottomBar = { PrimaryBottomButton(t.saveMeasurement, Icons.Default.Check, { save(h.toIntOrNull()?.coerceIn(0,23) ?: 0, m.toIntOrNull()?.coerceIn(0,59) ?: 0, sec.toIntOrNull()?.coerceIn(0,59) ?: 0) }) }) { pad ->
        Column(Modifier.padding(pad).padding(18.dp)) {
            Photo(s.path, s.shape); Spacer(Modifier.height(16.dp)); Text(t.photoTime, style = MaterialTheme.typography.labelMedium); Text("${date(s.capturedAt)} · ${clockMillis(s.capturedAt)}", fontSize = 19.sp); HorizontalDivider(Modifier.padding(vertical = 14.dp)); Text(t.dialTime, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TimeInput(t.hours, h, { h = it }, Modifier.weight(1f)); TimeInput(t.minutes, m, { m = it }, Modifier.weight(1f)); TimeInput(t.seconds, sec, { sec = it }, Modifier.weight(1f)) }
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
@Composable private fun SettingsScreen(t: UiText, palette: AppPalette, setPalette: (AppPalette) -> Unit, language: String, setLanguage: (String) -> Unit, back: () -> Unit) {
    var lang by remember { mutableStateOf(language) }
    Scaffold(topBar = { AppHeader(t.settings, t.back, back) }) { pad -> Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(t.environment, style = MaterialTheme.typography.labelMedium); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Language, null); Text(t.language, Modifier.padding(start = 12.dp).weight(1f)); SingleChoiceSegmentedButtonRow { listOf("sk" to "Slovenčina", "en" to "English").forEachIndexed { i, pair -> SegmentedButton(selected = lang == pair.first, onClick = { lang = pair.first; setLanguage(pair.first) }, shape = SegmentedButtonDefaults.itemShape(i,2)) { Text(pair.second) } } } }
        Text(t.colorCombination, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppPalette.entries.forEach { p -> PaletteChoice(t, p, palette == p, { setPalette(p) }, Modifier.weight(1f)) } }
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
@Composable private fun PrimaryBottomButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit, enabled: Boolean = true) { Surface(shadowElevation = 4.dp) { Button(click, Modifier.fillMaxWidth().padding(16.dp).height(52.dp), enabled = enabled) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text) } } }

private fun date(ms: Long) = SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(Date(ms))
private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun clockMillis(ms: Long) = SimpleDateFormat("HH:mm:ss,SSS", Locale.getDefault()).format(Date(ms))
private fun dialTime(m: Measurement) = String.format(Locale.getDefault(), "%02d:%02d:%02d", m.dialHour, m.dialMinute, m.dialSecond)
