package com.aksell.netwatch

// Analytics Engine — the single place where measurements become scores,
// grades and statistics. Before v2.7 the scoring cutoffs lived in two places
// (Quality for the dashboard, Gaming for the game monitor) and the statistics
// in three (Lab.computeStats, Gaming.percentile, ChartData.stats). The cutoffs
// are now funnelled through here so the dashboard and the game monitor can
// never disagree about the same ping value.
//
// Honesty rules enforced here:
// - A score is only computed from valid components; missing data is N/A,
//   never zero, and a total below the 70% coverage gate is not shown.
// - A gaming total is null while bufferbloat is unmeasured — no fake points.
// - A bufferbloat grade is only valid when the link was really loaded
//   (throughput >= 1 Mbps) and enough loaded pings answered; otherwise the
//   result carries grade "?" and score null with the reason in `note`.
object AnalyticsEngine {

    fun qualityScore(
        avgMs: Double, jitterMs: Double, lossPct: Double, okPct: Int,
        pingValid: Boolean = true, jitterValid: Boolean = true,
        lossValid: Boolean = true, stabValid: Boolean = true
    ): Quality.ScoreResult = Quality.compute(avgMs, jitterMs, lossPct, okPct, pingValid, jitterValid, lossValid, stabValid)

    fun gamingScore(p: PingResult, bb: Gaming.Bufferbloat?): Gaming.Score =
        Gaming.score(p, bb)

    // Full statistics for a ping burst: avg/min/max, P50-P99, jitter, loss.
    // `sent` counts every packet sent, including the ones that never answered,
    // so loss is real.
    fun burstStats(times: List<Double>, sent: Int): Lab.Stats = Lab.computeStats(times, sent)

    // Chart statistics for a time-ordered series: failed points are counted
    // separately and ignored for min/avg/max — never drawn as 0ms.
    fun chartStats(points: List<Pair<Long, Double>>): ChartData.Stats = ChartData.stats(points)

    fun bufferbloatGrade(idleMs: Double, loadedMs: Double): Pair<String, Double> =
        Gaming.bufferbloatGradeForTest(idleMs, loadedMs)

    // Bufferbloat measurement (20-40s, up to 50 MB). Must run on a background
    // thread and only when the user asked for it — never automatic.
    fun bufferbloat(
        target: String = "1.1.1.1",
        idleCount: Int = 10,
        loadedCount: Int = 10,
        minLoadMbps: Double = 1.0,
        shouldStop: (() -> Boolean)? = null
    ): Gaming.Bufferbloat = Gaming.bufferbloat(target, idleCount, loadedCount, minLoadMbps, shouldStop)

    fun coverageGate(validWeight: Int, totalWeight: Int): Boolean =
        Quality.coverageGate(validWeight, totalWeight)

    fun widgetStatus(scoreTotal: Int?, scoreStatus: String): String =
        Quality.widgetStatus(scoreTotal, scoreStatus)
}
