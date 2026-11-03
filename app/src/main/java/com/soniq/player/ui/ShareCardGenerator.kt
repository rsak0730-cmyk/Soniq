package com.soniq.player.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.soniq.player.data.MusicRepository
import com.soniq.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object ShareCardGenerator {

    suspend fun shareStoryCard(context: Context, song: Song) = withContext(Dispatchers.IO) {
        try {
            val width = 1080
            val height = 1920
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 1. OLED dark background
            val bgPaint = Paint().apply { color = 0xFF080612.toInt() }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // 2. Ambient background radial glow
            val glowPaint = Paint().apply {
                shader = RadialGradient(
                    width / 2f, height / 2.6f, 750f,
                    intArrayOf(0x559B6CFF.toInt(), 0x3353F6FF.toInt(), 0x00000000),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(width / 2f, height / 2.6f, 750f, glowPaint)

            // 3. Draw Brand Watermark
            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF53F6FF.toInt()
                textSize = 54f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.15f
            }
            canvas.drawText("S O N I Q", width / 2f, 220f, brandPaint)

            // 4. Load & Draw Album Cover
            var coverBmp: Bitmap? = null
            if (!song.albumArtUri.isNullOrBlank() && song.albumArtUri.startsWith("http")) {
                coverBmp = runCatching {
                    val conn = URL(song.albumArtUri).openConnection()
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    BitmapFactory.decodeStream(conn.getInputStream())
                }.getOrNull()
            }
            if (coverBmp == null) {
                runCatching {
                    val bytes = MusicRepository.extractEmbeddedPicture(context, song.uri)
                    if (bytes != null) {
                        coverBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }

            val artSize = 640f
            val artLeft = (width - artSize) / 2f
            val artTop = 420f
            val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)

            if (coverBmp != null) {
                val scaledCover = Bitmap.createScaledBitmap(coverBmp!!, artSize.toInt(), artSize.toInt(), true)
                val shader = BitmapShader(scaledCover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val matrix = Matrix().apply { setTranslate(artLeft, artTop) }
                shader.setLocalMatrix(matrix)

                val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
                canvas.drawRoundRect(artRect, 48f, 48f, artPaint)
            } else {
                val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(artLeft, artTop, artLeft + artSize, artTop + artSize, 0xFF9B6CFF.toInt(), 0xFF53F6FF.toInt(), Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(artRect, 48f, 48f, placeholderPaint)
            }

            // Draw Cover Border
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = 0x8853F6FF.toInt()
            }
            canvas.drawRoundRect(artRect, 48f, 48f, borderPaint)

            // 5. Song Title & Artist
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 58f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val safeTitle = if (song.title.length > 24) song.title.take(22) + "..." else song.title
            canvas.drawText(safeTitle, width / 2f, 1200f, titlePaint)

            val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFC7BCE0.toInt()
                textSize = 40f
                textAlign = Paint.Align.CENTER
            }
            val safeArtist = if (song.artist.length > 30) song.artist.take(28) + "..." else song.artist
            canvas.drawText("$safeArtist • ${song.album}", width / 2f, 1280f, artistPaint)

            // 6. Neon Soundwave Decoration
            val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 1400f, 0f, 1540f, 0xFF53F6FF.toInt(), 0xFF9B6CFF.toInt(), Shader.TileMode.CLAMP)
            }
            val numBars = 26
            val barWidth = 16f
            val gap = (width * 0.75f - (numBars * barWidth)) / (numBars - 1)
            val startX = (width * 0.125f)
            val heights = listOf(40f, 75f, 120f, 90f, 140f, 60f, 110f, 130f, 85f, 45f, 95f, 145f, 120f, 70f, 130f, 85f, 45f, 100f, 140f, 65f, 90f, 135f, 75f, 115f, 50f, 80f)

            for (i in 0 until numBars) {
                val bx = startX + i * (barWidth + gap)
                val bh = heights[i % heights.size]
                val by = 1460f - (bh / 2f)
                canvas.drawRoundRect(RectF(bx, by, bx + barWidth, by + bh), 8f, 8f, wavePaint)
            }

            // 7. Footer Text
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x88C7BCE0.toInt()
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Listening on SONIQ Audio Player", width / 2f, 1720f, footerPaint)

            // 8. Safe File Saving & Intent Creation
            val cacheImagesDir = File(context.cacheDir, "images")
            if (!cacheImagesDir.exists()) cacheImagesDir.mkdirs()

            val imageFile = File(cacheImagesDir, "story_card.png")
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, imageFile)

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    clipData = ClipData.newRawUri("Story Card", contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Share Track to Story").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Could not generate share card: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
