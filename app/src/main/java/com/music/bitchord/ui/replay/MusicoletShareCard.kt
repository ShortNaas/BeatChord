package com.music.bitchord.ui.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.music.bitchord.data.stats.ReplaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

enum class MusicoletCardType {
    SONGS,
    ARTISTS,
}

/**
 * 1080 x 1920 (9:16) export share card reproducing Musicolet's clean, high-contrast,
 * dark theme statistics poster for Instagram/WhatsApp/Stories.
 */
suspend fun renderMusicoletCard(
    context: Context,
    summary: ReplaySummary,
    type: MusicoletCardType,
): Bitmap = coroutineScope {
    val topSongs = summary.songs.take(10)
    val topArtists = summary.artists.take(10)

    // Preload album artworks for the top 10 songs in parallel
    val covers = topSongs
        .mapNotNull { it.song.thumbnailUrl }
        .distinct()
        .associateWith { url -> async(Dispatchers.IO) { loadBitmap(context, url) } }
        .mapValues { it.value.await() }

    withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fonts = Fonts(context)

        // 1. Solid pitch black background
        canvas.drawColor(Color.BLACK)

        // 2. Header
        val marginX = 80f
        var currentY = 120f

        // App Logo & Brand name
        drawBrandHeader(canvas, fonts, marginX, currentY)
        currentY += 110f

        // "Most played ✶ <Period>"
        val headline = "Most played ✶ ${summary.label}"
        val headlinePaint = fonts.heading(58f, Color.WHITE)
        canvas.drawText(headline, marginX, currentY, headlinePaint)
        currentY += 60f

        // 3. Pill Chips (Listening hours & top artist shares)
        currentY = drawPillChips(canvas, fonts, summary, type, marginX, currentY)
        currentY += 40f

        // 4. Content Rows
        when (type) {
            MusicoletCardType.SONGS -> {
                drawSongsList(canvas, fonts, topSongs, covers, marginX, currentY)
            }
            MusicoletCardType.ARTISTS -> {
                drawArtistsList(canvas, fonts, topArtists, summary.totalMs, marginX, currentY)
            }
        }

        // 5. Footer Branding
        drawFooter(canvas, fonts, 1820f)

        bitmap
    }
}

private fun drawBrandHeader(canvas: Canvas, fonts: Fonts, x: Float, y: Float) {
    // Drawn musical note / player mark
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val path = Path().apply {
        moveTo(x, y - 45f)
        lineTo(x + 50f, y - 20f)
        lineTo(x, y + 5f)
        close()
    }
    canvas.drawPath(path, iconPaint)

    val brandPaint = fonts.body(44f, Color.WHITE, bold = true)
    canvas.drawText("BeatChord", x + 75f, y - 10f, brandPaint)
}

private fun drawPillChips(
    canvas: Canvas,
    fonts: Fonts,
    summary: ReplaySummary,
    type: MusicoletCardType,
    startX: Float,
    startY: Float,
): Float {
    val totalHours = maxOf(1L, summary.totalMs / (1000 * 60 * 60))
    val chips = mutableListOf<String>()
    chips.add("⏱ $totalHours hours+")

    if (type == MusicoletCardType.SONGS) {
        val totalMs = maxOf(1L, summary.totalMs).toFloat()
        summary.artists.take(3).forEach { artist ->
            val pct = ((artist.ms / totalMs) * 100f).toInt()
            if (pct > 0) {
                chips.add("$pct% ${artist.title}")
            }
        }
    }

    val chipTextPaint = fonts.body(30f, 0xFFE0E0E0.toInt(), bold = true)
    val chipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    var curX = startX
    var curY = startY + 20f
    val rowHeight = 64f
    val paddingH = 32f

    for (chip in chips) {
        val textWidth = chipTextPaint.measureText(chip)
        val chipWidth = textWidth + paddingH * 2
        if (curX + chipWidth > 1080 - startX) {
            curX = startX
            curY += rowHeight + 16f
        }
        val rect = RectF(curX, curY, curX + chipWidth, curY + rowHeight)
        canvas.drawRoundRect(rect, rowHeight / 2f, rowHeight / 2f, chipBorderPaint)
        canvas.drawText(chip, curX + paddingH, curY + 44f, chipTextPaint)
        curX += chipWidth + 16f
    }

    return curY + rowHeight
}

private fun drawSongsList(
    canvas: Canvas,
    fonts: Fonts,
    songs: List<com.music.bitchord.data.stats.RankedSong>,
    covers: Map<String, Bitmap?>,
    startX: Float,
    startY: Float,
) {
    var y = startY + 20f
    val rowH = 104f

    val rankPaint = fonts.body(32f, 0xFF888888.toInt(), bold = true)
    val titlePaint = fonts.body(34f, Color.WHITE, bold = true)
    val artistPaint = fonts.body(30f, 0xFFB0B0B0.toInt(), bold = false)
    val bulletPaint = fonts.body(28f, 0xFF777777.toInt(), bold = false)

    val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1E.toInt()
        style = Paint.Style.FILL
    }
    val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    val badgeTextPaint = fonts.body(30f, Color.WHITE, bold = true).apply {
        textAlign = Paint.Align.CENTER
    }

    val artSize = 78f
    val maxTextWidth = 1080f - startX - 280f

    songs.forEachIndexed { index, ranked ->
        val rankStr = (index + 1).toString()
        canvas.drawText(rankStr, startX, y + 50f, rankPaint)

        // Artwork
        val artX = startX + 56f
        val artRect = RectF(artX, y + 8f, artX + artSize, y + 8f + artSize)
        val cover = ranked.song.thumbnailUrl?.let { covers[it] }
        if (cover != null) {
            canvas.save()
            val clipPath = Path().apply { addRoundRect(artRect, 14f, 14f, Path.Direction.CW) }
            canvas.clipPath(clipPath)
            canvas.drawBitmap(cover, null, artRect, null)
            canvas.restore()
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
            canvas.drawRoundRect(artRect, 14f, 14f, placeholderPaint)
        }

        // Title • Artist
        val textX = artX + artSize + 28f
        val fullLine = "${ranked.song.title}  •  ${ranked.song.artist}"
        val ellipLine = truncateText(fullLine, titlePaint, maxTextWidth)
        canvas.drawText(ellipLine, textX, y + 52f, titlePaint)

        // Right side play count badge
        val badgeW = 60f
        val badgeH = 46f
        val badgeX = 1080f - startX - badgeW
        val badgeY = y + 24f
        val badgeRect = RectF(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH)
        canvas.drawRoundRect(badgeRect, 10f, 10f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 10f, 10f, badgeBorderPaint)
        canvas.drawText(ranked.plays.toString(), badgeX + badgeW / 2f, badgeY + 33f, badgeTextPaint)

        y += rowH
    }
}

private fun drawArtistsList(
    canvas: Canvas,
    fonts: Fonts,
    artists: List<com.music.bitchord.data.stats.RankedEntry>,
    totalMs: Long,
    startX: Float,
    startY: Float,
) {
    var y = startY + 20f
    val rowH = 108f

    val rankPaint = fonts.body(32f, 0xFF888888.toInt(), bold = true)
    val namePaint = fonts.body(38f, Color.WHITE, bold = true)
    val pctPaint = fonts.body(34f, Color.WHITE, bold = true).apply {
        textAlign = Paint.Align.RIGHT
    }

    val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2C2C2E.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    val barProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    val safeTotal = maxOf(1L, totalMs).toFloat()
    val rightX = 1080f - startX
    val maxBarWidth = rightX - startX - 220f

    artists.forEachIndexed { index, artist ->
        val rankStr = (index + 1).toString()
        canvas.drawText(rankStr, startX, y + 44f, rankPaint)

        val nameX = startX + 60f
        canvas.drawText(artist.title, nameX, y + 44f, namePaint)

        // Percentage text
        val pct = (artist.ms.toFloat() / safeTotal) * 100f
        val pctStr = if (pct >= 10f || pct == pct.toInt().toFloat()) {
            "%.1f%%".format(Locale.ROOT, pct)
        } else {
            "%.1f%%".format(Locale.ROOT, pct)
        }
        canvas.drawText(pctStr, rightX, y + 44f, pctPaint)

        // Horizontal underline proportion bar
        val barY = y + 68f
        canvas.drawLine(nameX, barY, nameX + maxBarWidth, barY, barBgPaint)
        val progressWidth = maxBarWidth * (pct / 100f).coerceIn(0.01f, 1f)
        canvas.drawLine(nameX, barY, nameX + progressWidth, barY, barProgressPaint)

        y += rowH
    }
}

private fun drawFooter(canvas: Canvas, fonts: Fonts, y: Float) {
    val pillW = 280f
    val pillH = 74f
    val pillX = (1080f - pillW) / 2f
    val rect = RectF(pillX, y, pillX + pillW, y + pillH)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, borderPaint)

    val textPaint = fonts.body(30f, 0xFFE0E0E0.toInt(), bold = true).apply {
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("🎵  BitChord", 540f, y + 48f, textPaint)
}

private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var truncated = text
    while (truncated.isNotEmpty() && paint.measureText("$truncated…") > maxWidth) {
        truncated = truncated.dropLast(1)
    }
    return "$truncated…"
}
