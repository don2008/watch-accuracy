package sk.watchaccuracy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object PhotoCropper {
    fun cropToTemplate(path: String, shape: DialShape): String {
        val source = BitmapFactory.decodeFile(path) ?: return path
        val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (degrees == 0f) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true).also { if (it !== source) source.recycle() }
        val widthFraction = if (shape == DialShape.RECTANGLE) .50 else .72
        val heightFraction = if (shape == DialShape.RECTANGLE) .75 else .72
        var cropWidth = (upright.width * widthFraction).toInt()
        var cropHeight = (upright.height * heightFraction).toInt()
        if (shape != DialShape.RECTANGLE) {
            val side = minOf(cropWidth, cropHeight)
            cropWidth = side; cropHeight = side
        } else {
            val targetRatio = 2.0 / 3.0
            if (cropWidth.toDouble() / cropHeight > targetRatio) cropWidth = (cropHeight * targetRatio).toInt()
            else cropHeight = (cropWidth / targetRatio).toInt()
        }
        val left = ((upright.width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((upright.height - cropHeight) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(upright, left, top, cropWidth.coerceAtMost(upright.width), cropHeight.coerceAtMost(upright.height))
        val output = File(path.substringBeforeLast('.') + "_dial.jpg")
        FileOutputStream(output).use { cropped.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        cropped.recycle(); upright.recycle(); File(path).delete()
        return output.absolutePath
    }
}
