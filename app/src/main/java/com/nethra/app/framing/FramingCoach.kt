package com.nethra.app.framing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A rectangle in normalised preview coordinates (0..1, origin top-left, as the viewer sees it). */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val area get() = max(0f, width) * max(0f, height)

    fun union(o: NormRect) = NormRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun clamp() = NormRect(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))

    fun intersect(o: NormRect): NormRect? {
        val l = max(left, o.left); val t = max(top, o.top)
        val r = min(right, o.right); val b = min(bottom, o.bottom)
        return if (r > l && b > t) NormRect(l, t, r, b) else null
    }

    /** Intersection over union, 0..1. 1 = identical boxes. */
    fun iou(o: NormRect): Float {
        val inter = intersect(o)?.area ?: return 0f
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Linear blend towards [o]; a = 1 returns [o]. Used for temporal smoothing. */
    fun lerp(o: NormRect, a: Float) = NormRect(
        left + (o.left - left) * a, top + (o.top - top) * a,
        right + (o.right - right) * a, bottom + (o.bottom - bottom) * a
    )

    companion object {
        /** Default target: a centred portrait box that suits a standing person at a few metres. */
        val DEFAULT = NormRect(0.2f, 0.12f, 0.8f, 0.9f)
    }
}

/** What the detector saw in one analysed frame. */
data class Detection(
    val subject: NormRect?,
    /** 0..1 — how sure the detector is that [subject] is a real, correctly outlined person. */
    val confidence: Float,
    val faceFound: Boolean,
    val timestampMs: Long
)

enum class Guidance(val spoken: String, val label: String) {
    NO_PERSON("I can't see anyone yet. Step into the frame.", "No person detected"),
    LOW_CONFIDENCE("I can't see you clearly. Face the camera.", "Detection not confident"),
    STEP_BACK("Take a step back.", "Step back"),
    COME_CLOSER("Come a little closer.", "Come closer"),
    MOVE_LEFT("Move to your left.", "Step to their left"),
    MOVE_RIGHT("Move to your right.", "Step to their right"),
    CAMERA_UP("Tilt the camera up a little.", "Camera up"),
    CAMERA_DOWN("Tilt the camera down a little.", "Camera down"),
    GOOD("Perfect. Hold still.", "In the box — hold still");

    val isConfident get() = this != NO_PERSON && this != LOW_CONFIDENCE
    val isDirectional get() = this == MOVE_LEFT || this == MOVE_RIGHT || this == STEP_BACK ||
        this == COME_CLOSER || this == CAMERA_UP || this == CAMERA_DOWN
}

// ---------------------------------------------------------------------------
// Precision: the numbers behind every decision, exposed for tests, the on-screen
// HUD and logcat evaluation.
// ---------------------------------------------------------------------------

/**
 * Every threshold the coach uses, in one place. Distances are fractions of the
 * target box's width/height (so they scale with the box), floored at [minAbs]
 * frame units. Change these to tune how strict "in the box" is.
 */
data class FramingTolerance(
    /** Below this detector confidence the coach refuses to guide. */
    val minConfidence: Float = 0.5f,
    /** Allowed horizontal centre error, as a fraction of target width. */
    val centerX: Float = 0.12f,
    /** Allowed vertical error, as a fraction of target height. */
    val centerY: Float = 0.12f,
    /** Minimum tolerance in frame units, so tiny boxes aren't impossible to satisfy. */
    val minAbs: Float = 0.03f,
    /** Subject size / target size above this ⇒ "step back". */
    val tooBig: Float = 1.15f,
    /** Subject width / target width above this ⇒ "step back" (arms out, sideways stance). */
    val widthTooBig: Float = 1.35f,
    /** Subject size / target size below this ⇒ "come closer". */
    val tooSmall: Float = 0.80f,
    /**
     * Once the subject is GOOD, every tolerance is widened by this factor until it
     * is clearly exceeded. Stops the coach flip-flopping at the boundary.
     */
    val hysteresis: Float = 1.35f,
    /** A box edge within this distance of the frame edge counts as cropped. */
    val edge: Float = 0.015f
) {
    fun widened(f: Float) = copy(
        centerX = centerX * f, centerY = centerY * f, minAbs = minAbs * f,
        tooBig = 1 + (tooBig - 1) * f, widthTooBig = 1 + (widthTooBig - 1) * f,
        tooSmall = 1 - (1 - tooSmall) * f
    )

    companion object {
        val RELAXED = FramingTolerance(centerX = 0.18f, centerY = 0.18f, tooBig = 1.25f, widthTooBig = 1.45f, tooSmall = 0.70f)
        val STANDARD = FramingTolerance()
        val PRECISE = FramingTolerance(centerX = 0.06f, centerY = 0.06f, minAbs = 0.015f, tooBig = 1.08f, widthTooBig = 1.25f, tooSmall = 0.90f)

        fun preset(name: String) = when (name.uppercase()) {
            "RELAXED" -> RELAXED; "PRECISE" -> PRECISE; else -> STANDARD
        }
    }
}

/**
 * Measured framing error for one frame. Signs: +dx = subject is right of the
 * target (on screen), +dy = subject is lower than the target.
 */
data class FramingMetrics(
    /** Horizontal centre error in frame units. */
    val dx: Float,
    /** Vertical error in frame units (top-edge error when the person continues below the frame). */
    val dy: Float,
    /** dx divided by the allowed tolerance: |value| ≤ 1 is inside tolerance. */
    val dxRel: Float,
    val dyRel: Float,
    /** Subject size / target size (height if the whole body is visible, else width). */
    val scale: Float,
    /** Intersection over union of subject and target, 0..1. */
    val iou: Float,
    /** Fraction of the subject's box that lies inside the target, 0..1. */
    val coverage: Float,
    /** Single 0..100 precision score: 100 = perfectly centred and sized. */
    val score: Float,
    val bottomCut: Boolean,
    val confidence: Float
) {
    /** Steps the subject should move sideways, in target-box widths (for "a little" vs "a step"). */
    fun horizontalMagnitude(target: NormRect) = abs(dx) / max(0.05f, target.width)

    fun toLogLine(g: Guidance) =
        "g=${g.name} score=${fmt(score)} iou=${fmt(iou)} dx=${fmt(dx)} dy=${fmt(dy)} " +
            "dxRel=${fmt(dxRel)} dyRel=${fmt(dyRel)} scale=${fmt(scale)} cov=${fmt(coverage)} conf=${fmt(confidence)}"

    companion object {
        private fun fmt(f: Float) = "%.3f".format(f)
    }
}

object FramingMath {
    /** Weight of position vs size in [FramingMetrics.score]. */
    const val POSITION_WEIGHT = 0.6f
    /** Position error (in target sizes) at which the position part of the score reaches 0. */
    const val POSITION_ZERO = 0.5f
    /** Size error (|ln scale|) at which the size part reaches 0: ln 2 ⇒ half or double size. */
    val SCALE_ZERO = ln(2f)

    fun measure(s: NormRect, t: NormRect, confidence: Float, tol: FramingTolerance = FramingTolerance.STANDARD): FramingMetrics {
        val bottomCut = s.bottom >= 1 - tol.edge
        val dx = s.centerX - t.centerX
        val dy = if (bottomCut) s.top - t.top else s.centerY - t.centerY
        val tolX = max(tol.minAbs, tol.centerX * t.width)
        val tolY = max(tol.minAbs, tol.centerY * t.height)
        val widthRatio = s.width / max(1e-4f, t.width)
        val scale = if (bottomCut) widthRatio else s.height / max(1e-4f, t.height)
        val inter = s.intersect(t)?.area ?: 0f
        val coverage = if (s.area <= 0f) 0f else inter / s.area

        val posErr = hypot(dx / max(1e-4f, t.width), dy / max(1e-4f, t.height))
        val posPart = min(1f, posErr / POSITION_ZERO)
        val scalePart = min(1f, abs(ln(max(1e-4f, scale))) / SCALE_ZERO)
        val score = 100f * (1f - (POSITION_WEIGHT * posPart + (1 - POSITION_WEIGHT) * scalePart))

        return FramingMetrics(
            dx = dx, dy = dy, dxRel = dx / tolX, dyRel = dy / tolY,
            scale = scale, iou = s.iou(t), coverage = coverage,
            score = score.coerceIn(0f, 100f), bottomCut = bottomCut, confidence = confidence
        )
    }
}

data class CoachState(
    val guidance: Guidance = Guidance.NO_PERSON,
    val subject: NormRect? = null,
    val confidence: Float = 0f,
    val fits: Boolean = false,
    /** Precision numbers for the (smoothed) subject, null when nobody is detected. */
    val metrics: FramingMetrics? = null
)

/**
 * Turns detections into stable, spoken framing guidance. Pure — unit tested.
 *
 * Directions are from the subject's point of view: the rear camera faces the
 * person, so a person who appears left of the box must step to *their* left.
 *
 * Stability comes from three layers:
 *  1. [smoothing] — exponential moving average of the subject box (detector jitter);
 *  2. [stableFrames] — a guidance must repeat this many frames before it is shown;
 *  3. hysteresis — once GOOD, tolerances widen so tiny sways don't break it.
 */
class FramingCoach(
    private val stableFrames: Int = 3,
    private val cooldownMs: Long = 2500,
    private val repeatAfterMs: Long = 5000,
    private val noPersonRepeatMs: Long = 8000,
    /** 0 = no smoothing, 0.9 = very smooth but slow. */
    private val smoothing: Float = 0.45f,
    var tolerance: FramingTolerance = FramingTolerance.STANDARD
) {
    private var candidate: Guidance? = null
    private var candidateCount = 0
    private var shown = CoachState()
    private var lastSpoken: Guidance? = null
    private var lastSpokenAt = Long.MIN_VALUE / 2
    private var repeatCount = 0
    private var smoothed: NormRect? = null
    private val voice = GuidanceVoice()

    val state: CoachState get() = shown

    fun reset() {
        candidate = null; candidateCount = 0; shown = CoachState()
        lastSpoken = null; lastSpokenAt = Long.MIN_VALUE / 2
        repeatCount = 0; smoothed = null
    }

    /** @return what to say now, or null to stay quiet. */
    fun update(detection: Detection, target: NormRect, nowMs: Long): String? {
        val raw = detection.subject
        smoothed = when {
            raw == null -> null
            smoothed == null -> raw
            else -> smoothed!!.lerp(raw, 1f - smoothing.coerceIn(0f, 0.95f))
        }
        val d = detection.copy(subject = smoothed)
        val wasGood = shown.guidance == Guidance.GOOD
        val g = evaluate(d, target, tolerance, wasGood)
        val metrics = smoothed?.let { FramingMath.measure(it, target, detection.confidence, tolerance) }

        if (g == candidate) candidateCount++ else { candidate = g; candidateCount = 1 }
        shown = if (candidateCount >= stableFrames) {
            CoachState(g, smoothed, detection.confidence, g == Guidance.GOOD, metrics)
        } else {
            shown.copy(subject = smoothed, confidence = detection.confidence, metrics = metrics)
        }
        return speechFor(shown.guidance, metrics, target, nowMs)
    }

    private fun speechFor(g: Guidance, m: FramingMetrics?, target: NormRect, now: Long): String? {
        if (candidateCount < stableFrames) return null
        val since = now - lastSpokenAt
        if (since < cooldownMs) return null
        val ok = when {
            g != lastSpoken -> true
            g == Guidance.GOOD -> false                         // said once until something changes
            g == Guidance.NO_PERSON || g == Guidance.LOW_CONFIDENCE -> since >= noPersonRepeatMs
            else -> since >= repeatAfterMs
        }
        if (!ok) return null
        repeatCount = if (g == lastSpoken) repeatCount + 1 else 0
        val improvedFrom = lastSpoken?.takeIf { it.isDirectional && g == Guidance.GOOD }
        lastSpoken = g; lastSpokenAt = now
        return voice.phrase(g, m, target, repeatCount, improvedFrom)
    }

    companion object {
        const val MIN_CONFIDENCE = 0.5f

        fun evaluate(d: Detection, t: NormRect): Guidance = evaluate(d, t, FramingTolerance.STANDARD, false)

        fun evaluate(d: Detection, t: NormRect, baseTol: FramingTolerance, wasGood: Boolean): Guidance {
            val s = d.subject ?: return Guidance.NO_PERSON
            if (d.confidence < baseTol.minConfidence) return Guidance.LOW_CONFIDENCE
            val tol = if (wasGood) baseTol.widened(baseTol.hysteresis) else baseTol
            val edge = baseTol.edge

            // Parts of the person outside the frame, where the box wants them inside.
            val cropL = s.left <= edge && t.left > edge * 2
            val cropR = s.right >= 1 - edge && t.right < 1 - edge * 2
            val cropT = s.top <= edge && t.top > edge * 2
            // People usually continue below the frame; that is only judged by the top edge.
            val bottomCut = s.bottom >= 1 - edge

            if (cropL && cropR) return Guidance.STEP_BACK
            if (cropT && bottomCut && t.top > 0.05f) return Guidance.STEP_BACK
            if (cropL && !cropR) return Guidance.MOVE_LEFT
            if (cropR && !cropL) return Guidance.MOVE_RIGHT

            val widthRatio = s.width / t.width
            val heightRatio = if (bottomCut) null else s.height / t.height
            val ratio = heightRatio ?: widthRatio
            if (ratio > tol.tooBig || widthRatio > tol.widthTooBig) return Guidance.STEP_BACK
            if (ratio < tol.tooSmall && widthRatio < 1.0f) return Guidance.COME_CLOSER

            val dx = s.centerX - t.centerX
            val tolX = max(tol.minAbs, tol.centerX * t.width)
            if (dx < -tolX) return Guidance.MOVE_LEFT
            if (dx > tolX) return Guidance.MOVE_RIGHT

            if (cropT) return Guidance.CAMERA_UP
            val dy = if (bottomCut) s.top - t.top else s.centerY - t.centerY
            val tolY = max(tol.minAbs, tol.centerY * t.height)
            if (dy < -tolY) return Guidance.CAMERA_UP
            if (dy > tolY) return Guidance.CAMERA_DOWN

            return if (abs(dx) <= tolX && abs(dy) <= tolY) Guidance.GOOD else Guidance.LOW_CONFIDENCE
        }
    }
}

/**
 * Natural spoken phrasing. Pure — unit tested.
 *
 * - The first time a guidance is spoken it uses [Guidance.spoken], scaled by how
 *   far off the subject is ("a tiny bit", "a step", "two steps").
 * - Repeats rotate through alternatives so the coach doesn't sound robotic.
 * - Reaching GOOD after a correction gets encouragement.
 */
class GuidanceVoice {

    fun phrase(g: Guidance, m: FramingMetrics?, target: NormRect, repeat: Int, improvedFrom: Guidance? = null): String {
        if (g == Guidance.GOOD && improvedFrom != null) return GOOD_AFTER_FIX[repeat % GOOD_AFTER_FIX.size]
        if (m == null || !g.isDirectional) return pick(g, repeat)
        val side = when (g) {
            Guidance.MOVE_LEFT -> "left"
            Guidance.MOVE_RIGHT -> "right"
            else -> null
        }
        if (side != null) {
            val mag = m.horizontalMagnitude(target)
            val amount = when {
                abs(m.dxRel) < 1.6f -> "just a tiny bit"
                mag < 0.35f -> "a small step"
                mag < 0.8f -> "one step"
                else -> "two steps"
            }
            return if (repeat > 0) "Keep going, $amount to your $side."
            else "${amount.replaceFirstChar { it.uppercase() }} to your $side."
        }
        val strong = when (g) {
            Guidance.STEP_BACK -> m.scale > 1.5f
            Guidance.COME_CLOSER -> m.scale < 0.55f
            Guidance.CAMERA_UP, Guidance.CAMERA_DOWN -> abs(m.dyRel) > 2.5f
            else -> false
        }
        return when (g) {
            Guidance.STEP_BACK -> if (strong) "Step back a couple of steps." else if (repeat > 0) "A little further back." else g.spoken
            Guidance.COME_CLOSER -> if (strong) "Come a few steps closer." else if (repeat > 0) "Just a bit closer." else g.spoken
            Guidance.CAMERA_UP -> if (strong) "Tilt the camera up." else g.spoken
            Guidance.CAMERA_DOWN -> if (strong) "Tilt the camera down." else g.spoken
            else -> g.spoken
        }
    }

    private fun pick(g: Guidance, repeat: Int): String {
        if (repeat == 0) return g.spoken
        val alts = when (g) {
            Guidance.NO_PERSON -> listOf("Still looking for you.", "Step into the frame when you're ready.")
            Guidance.LOW_CONFIDENCE -> listOf("I still can't see you clearly. Try facing the camera.", "More light would help me see you.")
            else -> listOf(g.spoken)
        }
        return alts[(repeat - 1) % alts.size]
    }

    companion object {
        val GOOD_AFTER_FIX = listOf("That's it. Perfect, hold still.", "Great, you're in the frame.", "Perfect. Stay right there.")
    }
}

/**
 * Re-expresses a rect measured on the (portrait-locked) screen in the frame the
 * scene is actually upright in, when the phone is held at [degrees]
 * (OrientationEventListener convention: 90 = left edge up). Lets the coach say
 * "left" and "tilt up" correctly when the phone is held sideways.
 */
fun NormRect.uprightFor(degrees: Int): NormRect = when (degrees) {
    90 -> NormRect(1 - bottom, left, 1 - top, right)
    180 -> NormRect(1 - right, 1 - bottom, 1 - left, 1 - top)
    270 -> NormRect(top, 1 - right, bottom, 1 - left)
    else -> this
}
