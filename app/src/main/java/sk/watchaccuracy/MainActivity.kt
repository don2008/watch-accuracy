package sk.watchaccuracy

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val colors = when (palette) {
        AppPalette.CLASSIC -> lightColorScheme(primary = Color(0xFF22241E), secondary = Color(0xFF9A7535), background = Color(0xFFF4F1E8), surface = Color(0xFFFFFDF7))
        AppPalette.BLUE -> lightColorScheme(primary = Color(0xFF172538), secondary = Color(0xFF527FA6), background = Color(0xFFF0F4F7), surface = Color(0xFFF9FCFF))
        AppPalette.MONO -> lightColorScheme(primary = Color(0xFF191919), secondary = Color(0xFF666666), background = Color(0xFFF3F3F3), surface = Color.White)
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                Screen.Watches -> WatchesScreen(watches, { screen = Screen.WatchDetail(it) }, { screen = Screen.AddWatch }, { screen = Screen.Settings })
                Screen.AddWatch -> AddWatchScreen(watches, { screen = Screen.Watches }) { brand, model ->
                    watches = watches + Watch(brand = brand.trim(), model = model.trim()); repo.save(watches); screen = Screen.Watches
                }
                is Screen.WatchDetail -> WatchDetailScreen(watches.first { it.id == s.watchId }, { screen = Screen.Watches }, { screen = Screen.Templates(s.watchId) }) { screen = Screen.Record(s.watchId, it) }
                is Screen.Templates -> TemplateScreen({ screen = Screen.WatchDetail(s.watchId) }) { screen = Screen.Camera(s.watchId, it) }
                is Screen.Camera -> CameraScreen(s.shape, { screen = Screen.Templates(s.watchId) }) { path, at -> screen = Screen.Review(s.watchId, s.shape, path, at, ClockReader.read(path, at)) }
                is Screen.Review -> ReviewScreen(s, { screen = Screen.Camera(s.watchId, s.shape) }) { h, m, sec ->
                    val measurement = Measurement(capturedAtMillis = s.capturedAt, dialHour = h, dialMinute = m, dialSecond = sec, photoPath = s.path, shape = s.shape)
                    watches = watches.map { if (it.id == s.watchId) it.copy(measurements = it.measurements + measurement) else it }
                    repo.save(watches); screen = Screen.WatchDetail(s.watchId)
                }
                is Screen.Record -> RecordScreen(watches.first { it.id == s.watchId }, s.measurementId) { screen = Screen.WatchDetail(s.watchId) }
                Screen.Settings -> SettingsScreen(palette, { palette = it; repo.savePalette(it) }, repo.language(), { repo.saveLanguage(it) }) { screen = Screen.Watches }
            }
        }
    }
}

@Composable private fun AppHeader(title: String, back: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Späť") }
        Text(title, fontSize = 27.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable private fun WatchesScreen(watches: List<Watch>, open: (String) -> Unit, add: () -> Unit, settings: () -> Unit) {
    Scaffold(topBar = { AppHeader("Hodinky", action = { IconButton(onClick = settings) { Icon(Icons.Default.Settings, "Nastavenia") } }) }, bottomBar = { PrimaryBottomButton("Pridať hodinky", Icons.Default.Add, add) }) { pad ->
        if (watches.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("Zatiaľ nemáte uložené žiadne hodinky") }
        else LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(watches) { watch ->
                Card(Modifier.fillMaxWidth().clickable { open(watch.id) }) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(54.dp)) { drawCircle(MaterialTheme.colorScheme.secondary, style = Stroke(2.dp.toPx())); drawLine(Color.DarkGray, center, center.copy(y = center.y - 16.dp.toPx()), 2.dp.toPx()); drawLine(Color.DarkGray, center, center.copy(x = center.x + 13.dp.toPx(), y = center.y + 7.dp.toPx()), 2.dp.toPx()) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(watch.brand, fontSize = 19.sp); Text("${watch.model} · ${watch.measurements.size} meraní", style = MaterialTheme.typography.bodySmall) }
                    Text(latestRate(watch), color = MaterialTheme.colorScheme.secondary)
                } }
            }
        }
    }
}

private fun latestRate(w: Watch): String {
    if (w.measurements.size < 2) return "—"
    val v = DeviationCalculator.secondsPerDay(w.measurements[w.measurements.lastIndex - 1], w.measurements.last()) ?: return "—"
    return String.format(Locale.getDefault(), "%+.1f s/d", v)
}

@Composable private fun AddWatchScreen(watches: List<Watch>, back: () -> Unit, save: (String, String) -> Unit) {
    var brand by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }
    Scaffold(topBar = { AppHeader("Pridať hodinky", back) }, bottomBar = { PrimaryBottomButton("Uložiť hodinky", Icons.Default.Check, { if (brand.isNotBlank() && model.isNotBlank()) save(brand, model) }, brand.isNotBlank() && model.isNotBlank()) }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OutlinedTextField(brand, { brand = it }, Modifier.fillMaxWidth(), label = { Text("Značka") }, leadingIcon = { Icon(Icons.Default.Watch, null) }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model alebo typ") }, singleLine = true)
            Text("Naposledy použité značky", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { watches.map { it.brand }.distinct().take(5).forEach { AssistChip(onClick = { brand = it }, label = { Text(it) }) } }
        }
    }
}

@Composable private fun WatchDetailScreen(watch: Watch, back: () -> Unit, newMeasurement: () -> Unit, openRecord: (String) -> Unit) {
    Scaffold(topBar = { AppHeader("${watch.brand} ${watch.model}", back) }, bottomBar = { PrimaryBottomButton("Nové meranie", Icons.Default.CameraAlt, newMeasurement) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 18.dp)) {
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text("Posledná denná odchýlka"); Text(latestRate(watch), fontSize = 30.sp) } }; Spacer(Modifier.height(17.dp)); Text("História meraní", style = MaterialTheme.typography.labelMedium) }
            items(watch.measurements.reversed()) { m -> MeasurementRow(watch, m) { openRecord(m.id) } }
        }
    }
}

@Composable private fun MeasurementRow(watch: Watch, measurement: Measurement, open: () -> Unit) {
    val index = watch.measurements.indexOfFirst { it.id == measurement.id }
    val rate = if (index > 0) DeviationCalculator.secondsPerDay(watch.measurements[index - 1], measurement)?.let { String.format(Locale.getDefault(), "%+.1f", it) } ?: "—" else "—"
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(date(measurement.capturedAtMillis)); Text("Fotka ${clock(measurement.capturedAtMillis)} · ciferník ${dialTime(measurement)}", style = MaterialTheme.typography.bodySmall) }; Text(rate, color = MaterialTheme.colorScheme.secondary)
    }
    HorizontalDivider()
}

@Composable private fun TemplateScreen(back: () -> Unit, next: (DialShape) -> Unit) {
    var selected by remember { mutableStateOf(DialShape.ROUND) }
    Scaffold(topBar = { AppHeader("Tvar ciferníka", back) }, bottomBar = { PrimaryBottomButton("Pokračovať k fotoaparátu", Icons.Default.ArrowForward, { next(selected) }) }) { pad ->
        Column(Modifier.padding(pad).padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("Vyberte šablónu, ktorá najlepšie zodpovedá tvaru vašich hodiniek.")
            ShapeChoice(DialShape.ROUND, "Kruhový", "Rolex Oyster Perpetual", selected) { selected = it }
            ShapeChoice(DialShape.SQUARE, "Štvorcový", "TAG Heuer Monaco", selected) { selected = it }
            ShapeChoice(DialShape.RECTANGLE, "Obdĺžnikový", "Jaeger-LeCoultre Reverso", selected) { selected = it }
        }
    }
}

@Composable private fun ShapeChoice(shape: DialShape, title: String, example: String, selected: DialShape, choose: (DialShape) -> Unit) {
    val outline = when (shape) { DialShape.ROUND -> CircleShape; DialShape.SQUARE -> RoundedCornerShape(12.dp); DialShape.RECTANGLE -> RoundedCornerShape(9.dp) }
    Card(Modifier.fillMaxWidth().clickable { choose(shape) }.then(if (selected == shape) Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(17.dp)) else Modifier), colors = CardDefaults.cardColors(containerColor = if (selected == shape) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(if (shape == DialShape.RECTANGLE) 48.dp else 64.dp, 64.dp).border(2.dp, MaterialTheme.colorScheme.secondary, outline)); Column(Modifier.padding(start = 18.dp).weight(1f)) { Text(title, fontSize = 19.sp); Text(example, style = MaterialTheme.typography.bodySmall) }; if (selected == shape) Icon(Icons.Default.CheckCircle, null) }
    }
}

@Composable private fun CameraScreen(shape: DialShape, back: () -> Unit, captured: (String, Long) -> Unit) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var permitted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if (!permitted) request.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permitted) androidx.compose.ui.viewinterop.AndroidView(factory = { ctx -> PreviewView(ctx).also { view ->
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({ val provider = future.get(); val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }; imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build(); provider.unbindAll(); provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture) }, ContextCompat.getMainExecutor(ctx))
        } }, modifier = Modifier.fillMaxSize()) else Text("Fotoaparát potrebuje povolenie", color = Color.White, modifier = Modifier.align(Alignment.Center))
        IconButton(back, Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(alpha=.45f), CircleShape)) { Icon(Icons.Default.Close, "Zavrieť", tint = Color.White) }
        CameraGuide(shape, Modifier.align(Alignment.Center))
        Text("Umiestnite celý ciferník do vyznačeného tvaru", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp))
        Button(onClick = { val capture = imageCapture ?: return@Button; val at = System.currentTimeMillis(); val file = File(context.filesDir, "dial_${at}.jpg"); capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback { override fun onImageSaved(result: ImageCapture.OutputFileResults) { captured(file.absolutePath, at) }; override fun onError(exception: ImageCaptureException) {} }) }, modifier = Modifier.align(Alignment.BottomCenter).padding(30.dp).size(76.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {}
    }
}

@Composable private fun CameraGuide(shape: DialShape, modifier: Modifier) {
    val dims = when (shape) { DialShape.RECTANGLE -> Modifier.size(190.dp, 285.dp); else -> Modifier.size(270.dp) }
    val corner = when (shape) { DialShape.ROUND -> CircleShape; DialShape.SQUARE -> RoundedCornerShape(34.dp); DialShape.RECTANGLE -> RoundedCornerShape(24.dp) }
    Box(modifier.then(dims).border(2.dp, Color(0xFFD1AD68), corner))
}

@Composable private fun ReviewScreen(s: Screen.Review, retake: () -> Unit, save: (Int, Int, Int) -> Unit) {
    var h by remember { mutableStateOf(s.read.hour.toString()) }; var m by remember { mutableStateOf(s.read.minute.toString()) }; var sec by remember { mutableStateOf(s.read.second.toString()) }
    Scaffold(topBar = { AppHeader("Kontrola merania", retake) }, bottomBar = { PrimaryBottomButton("Uložiť meranie", Icons.Default.Check, { save(h.toIntOrNull()?.coerceIn(0,23) ?: 0, m.toIntOrNull()?.coerceIn(0,59) ?: 0, sec.toIntOrNull()?.coerceIn(0,59) ?: 0) }) }) { pad ->
        Column(Modifier.padding(pad).padding(18.dp)) {
            Photo(s.path); Spacer(Modifier.height(16.dp)); Text("Čas vytvorenia fotografie", style = MaterialTheme.typography.labelMedium); Text("${date(s.capturedAt)} · ${clockMillis(s.capturedAt)}", fontSize = 19.sp); HorizontalDivider(Modifier.padding(vertical = 14.dp)); Text("Čas odčítaný z ciferníka", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TimeInput("hodiny", h, { h = it }, Modifier.weight(1f)); TimeInput("minúty", m, { m = it }, Modifier.weight(1f)); TimeInput("sekundy", sec, { sec = it }, Modifier.weight(1f)) }
        }
    }
}

@Composable private fun TimeInput(label: String, value: String, change: (String) -> Unit, modifier: Modifier) { OutlinedTextField(value, change, modifier, label = { Text(label) }, singleLine = true) }
@Composable private fun Photo(path: String) { val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }; if (bitmap != null) Image(bitmap, null, Modifier.fillMaxWidth().height(245.dp), contentScale = ContentScale.Crop) else Box(Modifier.fillMaxWidth().height(245.dp).background(MaterialTheme.colorScheme.surfaceVariant)) }

@Composable private fun RecordScreen(watch: Watch, id: String, back: () -> Unit) {
    val m = watch.measurements.first { it.id == id }; val index = watch.measurements.indexOf(m); val rate = if (index > 0) DeviationCalculator.secondsPerDay(watch.measurements[index-1], m) else null
    Scaffold(topBar = { AppHeader("Detail merania", back) }) { pad -> Column(Modifier.padding(pad).padding(18.dp)) { Photo(m.photoPath); Spacer(Modifier.height(15.dp)); Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(17.dp)) { Text("Vypočítaná denná odchýlka"); Text(rate?.let { String.format(Locale.getDefault(), "%+.1f s/deň", it) } ?: "Prvé meranie", fontSize = 28.sp) } }; DataRow("Dátum merania", date(m.capturedAtMillis)); DataRow("Čas fotografie", clockMillis(m.capturedAtMillis)); DataRow("Čas na ciferníku", dialTime(m)) } }
}

@Composable private fun DataRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) { Text(label, Modifier.weight(1f)); Text(value) }; HorizontalDivider() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(palette: AppPalette, setPalette: (AppPalette) -> Unit, language: String, setLanguage: (String) -> Unit, back: () -> Unit) {
    var lang by remember { mutableStateOf(language) }
    Scaffold(topBar = { AppHeader(if (lang == "sk") "Nastavenia" else "Settings", back) }) { pad -> Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Prostredie", style = MaterialTheme.typography.labelMedium); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Language, null); Text("Jazyk", Modifier.padding(start = 12.dp).weight(1f)); SingleChoiceSegmentedButtonRow { listOf("sk" to "Slovenčina", "en" to "English").forEachIndexed { i, pair -> SegmentedButton(selected = lang == pair.first, onClick = { lang = pair.first; setLanguage(pair.first) }, shape = SegmentedButtonDefaults.itemShape(i,2)) { Text(pair.second) } } } }
        Text("Farebná kombinácia", style = MaterialTheme.typography.labelMedium); AppPalette.entries.forEach { p -> Row(Modifier.fillMaxWidth().clickable { setPalette(p) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(palette == p, { setPalette(p) }); Text(when(p){AppPalette.CLASSIC->"Klasická";AppPalette.BLUE->"Modrá";AppPalette.MONO->"Čiernobiela"}) } }
        Text("Povolenia", style = MaterialTheme.typography.labelMedium); PermissionRow(Icons.Default.CameraAlt, "Fotoaparát", "Potrebný na nové merania", true); PermissionRow(Icons.Default.Photo, "Fotografie", "Snímky sa ukladajú iba v aplikácii", true); PermissionRow(Icons.Default.Notifications, "Pripomienky", "Pripomenutie ďalšieho merania", false)
    } }
}

@Composable private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, initial: Boolean) { var on by remember { mutableStateOf(initial) }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Column(Modifier.padding(start=12.dp).weight(1f)) { Text(title); Text(sub, style=MaterialTheme.typography.bodySmall) }; Switch(on, {on=it}) } }
@Composable private fun PrimaryBottomButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit, enabled: Boolean = true) { Surface(shadowElevation = 4.dp) { Button(click, Modifier.fillMaxWidth().padding(16.dp).height(52.dp), enabled = enabled) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text) } } }

private fun date(ms: Long) = SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(Date(ms))
private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun clockMillis(ms: Long) = SimpleDateFormat("HH:mm:ss,SSS", Locale.getDefault()).format(Date(ms))
private fun dialTime(m: Measurement) = String.format(Locale.getDefault(), "%02d:%02d:%02d", m.dialHour, m.dialMinute, m.dialSecond)
