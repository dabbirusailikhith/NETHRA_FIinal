package com.nethra.app.framing

/**
 * Measures how well the framing coach performs over a sequence of frames.
 * Pure — used by unit tests on synthetic/recorded data, and on the phone
 * (FramingViewModel logs [report] to logcat under the tag "NethraFraming").
 *
 * Feed it every frame with [add]. If you know the right answer for a frame
 * (a labelled test clip), pass it as `expected` to get accuracy and a
 * per-guidance precision/recall table.
 */
class FramingEvaluator(
    private val coach: FramingCoach = FramingCoach()
) {
    private var frames = 0
    private var labelled = 0
    private var correct = 0
    private var flips = 0
    private var spoken = 0
    private var lastShown: Guidance? = null
    private var firstMs: Long? = null
    private var lastMs: Long = 0
    private var firstGoodMs: Long? = null
    private var scoreSum = 0.0
    private var iouSum = 0.0
    private var measured = 0
    private var goodFrames = 0
    private val confusion = HashMap<Pair<Guidance, Guidance>, Int>() // (expected, shown) -> count

    /** Runs the evaluator's own coach on a frame. @return what the coach would say (or null). */
    fun add(detection: Detection, target: NormRect, nowMs: Long, expected: Guidance? = null): String? {
        val say = coach.update(detection, target, nowMs)
        observe(coach.state, say, nowMs, expected)
        return say
    }

    /** Records a frame from a coach that runs elsewhere (the live screen). */
    fun observe(st: CoachState, say: String?, nowMs: Long, expected: Guidance? = null) {
        frames++
        if (firstMs == null) firstMs = nowMs
        lastMs = nowMs
        if (say != null) spoken++
        if (lastShown != null && st.guidance != lastShown) flips++
        lastShown = st.guidance
        if (st.guidance == Guidance.GOOD) {
            goodFrames++
            if (firstGoodMs == null) firstGoodMs = nowMs
        }
        st.metrics?.let { scoreSum += it.score; iouSum += it.iou; measured++ }
        if (expected != null) {
            labelled++
            if (expected == st.guidance) correct++
            val k = expected to st.guidance
            confusion[k] = (confusion[k] ?: 0) + 1
        }
    }

    fun reset() {
        frames = 0; labelled = 0; correct = 0; flips = 0; spoken = 0
        lastShown = null; firstMs = null; lastMs = 0; firstGoodMs = null
        scoreSum = 0.0; iouSum = 0.0; measured = 0; goodFrames = 0
        confusion.clear()
    }

    fun report(): FramingReport {
        val durMs = (lastMs - (firstMs ?: lastMs)).coerceAtLeast(1)
        val classes = Guidance.entries.mapNotNull { g ->
            val tp = confusion[g to g] ?: 0
            val predicted = confusion.filterKeys { it.second == g }.values.sum()
            val actual = confusion.filterKeys { it.first == g }.values.sum()
            if (predicted == 0 && actual == 0) null
            else ClassScore(
                g,
                precision = if (predicted == 0) Float.NaN else tp.toFloat() / predicted,
                recall = if (actual == 0) Float.NaN else tp.toFloat() / actual,
                support = actual
            )
        }
        return FramingReport(
            frames = frames,
            accuracy = if (labelled == 0) Float.NaN else correct.toFloat() / labelled,
            meanScore = if (measured == 0) 0f else (scoreSum / measured).toFloat(),
            meanIou = if (measured == 0) 0f else (iouSum / measured).toFloat(),
            goodRatio = if (frames == 0) 0f else goodFrames.toFloat() / frames,
            flipsPerMinute = flips * 60_000f / durMs,
            utterancesPerMinute = spoken * 60_000f / durMs,
            timeToGoodMs = firstGoodMs?.let { it - (firstMs ?: it) },
            perClass = classes
        )
    }
}

data class ClassScore(val guidance: Guidance, val precision: Float, val recall: Float, val support: Int)

data class FramingReport(
    val frames: Int,
    /** Fraction of labelled frames where the shown guidance matched the label (NaN if none labelled). */
    val accuracy: Float,
    /** Mean 0..100 precision score across frames with a person. */
    val meanScore: Float,
    val meanIou: Float,
    /** Fraction of frames shown as GOOD. */
    val goodRatio: Float,
    /** How often the shown guidance changed — lower is steadier. */
    val flipsPerMinute: Float,
    /** How much the coach talks. */
    val utterancesPerMinute: Float,
    /** Time from the first frame until the first GOOD, or null if never. */
    val timeToGoodMs: Long?,
    val perClass: List<ClassScore>
) {
    override fun toString(): String = buildString {
        appendLine("Framing report: $frames frames")
        appendLine("  accuracy        ${pct(accuracy)}")
        appendLine("  mean score      ${"%.1f".format(meanScore)} / 100")
        appendLine("  mean IoU        ${"%.3f".format(meanIou)}")
        appendLine("  in-box ratio    ${pct(goodRatio)}")
        appendLine("  flips/min       ${"%.1f".format(flipsPerMinute)}")
        appendLine("  utterances/min  ${"%.1f".format(utterancesPerMinute)}")
        appendLine("  time to GOOD    ${timeToGoodMs?.let { "$it ms" } ?: "never"}")
        if (perClass.isNotEmpty()) {
            appendLine("  guidance        precision  recall  support")
            perClass.forEach {
                appendLine("  %-15s %9s %7s %8d".format(it.guidance.name, pct(it.precision), pct(it.recall), it.support))
            }
        }
    }

    private fun pct(f: Float) = if (f.isNaN()) "—" else "%.1f%%".format(f * 100)
}
