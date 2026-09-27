package com.nethra.app.captions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.media3.effect.BitmapOverlay
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws the caption for each video frame: bold white words on a rounded dark
 * pill, the word being spoken in NETHRA yellow (karaoke style), in the lower
 * middle of the frame (clear of the Shorts/Reels buttons at the very bottom).
 *
 * A new bitmap is rendered only when the card or the highlighted word changes;
 * between changes the same bitmap is returned, so the GPU texture isn't re-uploaded.
 */
class CaptionOverlay(
    private val cards: List<CaptionCard>,
    private val frameWidth: Int,
    private val frameHeight: Int
) : BitmapOverlay() {

    private val empty: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private var lastKey = Long.MIN_VALUE
    private var last: Bitmap = empty

    private val portrait = frameHeight >= frameWidth
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = (if (portrait) frameHeight * 0.036f else frameHeight * 0.060f).coerceAtLeast(24f)
        setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, 0x99000000.toInt())
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() }
    private val maxTextWidth = (frameWidth * 0.84f).toInt()
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        // Normalised device coordinates: (0, -0.52) = centred, about a quarter up from the bottom.
        .setBackgroundFrameAnchor(0f, if (portrait) -0.52f else -0.72f)
        .build()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val hit = CaptionBuilder.at(cards, presentationTimeUs / 1000) ?: return empty
        val key = hit.first.toLong() * 1000 + hit.second
        if (key != lastKey) {
            last = render(cards[hit.first], hit.second)
            lastKey = key
        }
        return last
    }

    private fun render(card: CaptionCard, active: Int): Bitmap {
        val text = SpannableString(card.text)
        var pos = 0
        card.words.forEachIndexed { i, w ->
            val start = card.text.indexOf(w.text, pos).coerceAtLeast(pos)
            val end = (start + w.text.length).coerceAtMost(text.length)
            if (i == active) text.setSpan(ForegroundColorSpan(HIGHLIGHT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = end
        }
        val width = ceil(textPaint.measureText(card.text)).toInt().coerceAtMost(maxTextWidth).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(2)
            .build()
        val padX = textPaint.textSize * 0.55f
        val padY = textPaint.textSize * 0.30f
        val bw = (layout.width + padX * 2).toInt()
        val bh = (layout.height + padY * 2).toInt()
        val bmp = Bitmap.createBitmap(max(bw, 4), max(bh, 4), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = textPaint.textSize * 0.45f
        c.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), radius, radius, pillPaint)
        c.translate(padX, padY)
        layout.draw(c)
        return bmp
    }

    companion object {
        /** iOS camera yellow, like the rest of NETHRA's accents. */
        private val HIGHLIGHT = 0xFFFFD60A.toInt()
    }
}
