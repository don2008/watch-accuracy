package sk.watchaccuracy

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WatchRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("watch_accuracy", Context.MODE_PRIVATE)

    fun load(): List<Watch> = runCatching {
        parse(prefs.getString("watches", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun save(watches: List<Watch>) {
        prefs.edit().putString("watches", toJson(watches).toString()).apply()
    }

    fun exportArchive(uri: Uri, watches: List<Watch>): Result<Unit> = runCatching {
        val photoEntries = buildMap {
            watches.flatMap { it.measurements }.forEach { measurement ->
                val photo = File(measurement.photoPath)
                if (photo.isFile) {
                    val extension = photo.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
                    put(measurement.id, "photos/${measurement.id}.$extension")
                }
            }
        }
        val output = requireNotNull(appContext.contentResolver.openOutputStream(uri))
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("watch-accuracy.json"))
            zip.write(JSONObject().apply {
                put("format", "watch-accuracy-backup")
                put("version", 1)
                put("watches", toJson(watches) { photoEntries[it.id] })
            }.toString(2).toByteArray())
            zip.closeEntry()
            watches.flatMap { it.measurements }.forEach { measurement ->
                val entryName = photoEntries[measurement.id] ?: return@forEach
                zip.putNextEntry(ZipEntry(entryName))
                File(measurement.photoPath).inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun importArchive(uri: Uri): Result<List<Watch>> = runCatching {
        val importDir = File(appContext.filesDir, "import_${UUID.randomUUID()}").apply { mkdirs() }
        var manifest: String? = null
        val importedPhotos = mutableMapOf<String, String>()
        try {
            val input = requireNotNull(appContext.contentResolver.openInputStream(uri))
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == "watch-accuracy.json") {
                        manifest = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (!entry.isDirectory && entry.name.startsWith("photos/")) {
                        val safeName = File(entry.name).name
                        require(safeName.isNotBlank())
                        val destination = File(importDir, safeName)
                        destination.outputStream().buffered().use { zip.copyTo(it) }
                        importedPhotos[entry.name] = destination.absolutePath
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val root = JSONObject(requireNotNull(manifest) { "Missing watch-accuracy.json" })
            require(root.optString("format") == "watch-accuracy-backup") { "Unsupported backup" }
            parse(root.getJSONArray("watches").toString()) { entry -> importedPhotos[entry].orEmpty() }
        } catch (error: Throwable) {
            importDir.deleteRecursively()
            throw error
        }
    }

    fun merge(existing: List<Watch>, imported: List<Watch>): List<Watch> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id }
            if (index < 0) result += incoming else {
                val current = result[index]
                val measurements = (current.measurements + incoming.measurements)
                    .distinctBy { it.id }.sortedBy { it.capturedAtMillis }
                result[index] = current.copy(
                    brand = incoming.brand.ifBlank { current.brand },
                    model = incoming.model.ifBlank { current.model },
                    measurements = measurements
                )
            }
        }
        return result
    }

    private fun parse(source: String, photoResolver: (String) -> String = { it }): List<Watch> {
        val array = JSONArray(source)
        return List(array.length()) { i ->
            val w = array.getJSONObject(i)
            val values = w.optJSONArray("measurements") ?: JSONArray()
            Watch(
                id = w.getString("id"), brand = w.getString("brand"), model = w.getString("model"),
                measurements = List(values.length()) { j ->
                    val m = values.getJSONObject(j)
                    val photoEntry = m.optString("photoEntry")
                    Measurement(
                        id = m.getString("id"), capturedAtMillis = m.getLong("capturedAtMillis"),
                        dialHour = m.getInt("dialHour"), dialMinute = m.getInt("dialMinute"),
                        dialSecond = m.getInt("dialSecond"),
                        photoPath = if (photoEntry.isNotBlank()) photoResolver(photoEntry) else m.optString("photoPath"),
                        shape = DialShape.valueOf(m.getString("shape")),
                        layout = runCatching { DialLayout.valueOf(m.optString("layout", "CLASSIC")) }.getOrDefault(DialLayout.CLASSIC)
                    )
                }
            )
        }
    }

    private fun toJson(watches: List<Watch>, photoEntry: (Measurement) -> String? = { null }): JSONArray = JSONArray().apply {
        watches.forEach { watch ->
            val measurements = JSONArray()
            watch.measurements.forEach { m -> measurements.put(JSONObject().apply {
                put("id", m.id); put("capturedAtMillis", m.capturedAtMillis); put("dialHour", m.dialHour)
                put("dialMinute", m.dialMinute); put("dialSecond", m.dialSecond); put("photoPath", m.photoPath)
                put("shape", m.shape.name); put("layout", m.layout.name)
                photoEntry(m)?.let { put("photoEntry", it) }
            }) }
            put(JSONObject().apply {
                put("id", watch.id); put("brand", watch.brand); put("model", watch.model); put("measurements", measurements)
            })
        }
    }

    fun palette(): AppPalette = runCatching { AppPalette.valueOf(prefs.getString("palette", "CLASSIC")!!) }.getOrDefault(AppPalette.CLASSIC)
    fun savePalette(value: AppPalette) = prefs.edit().putString("palette", value.name).apply()
    fun language(): String = prefs.getString("language", "sk") ?: "sk"
    fun saveLanguage(value: String) = prefs.edit().putString("language", value).apply()
    fun shutterPosition(): ShutterPosition = runCatching { ShutterPosition.valueOf(prefs.getString("shutter_position", "CENTER")!!) }.getOrDefault(ShutterPosition.CENTER)
    fun saveShutterPosition(value: ShutterPosition) = prefs.edit().putString("shutter_position", value.name).apply()
}
