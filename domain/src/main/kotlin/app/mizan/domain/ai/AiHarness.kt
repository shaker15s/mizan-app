package app.mizan.domain.ai

import app.mizan.domain.model.ConnectorCapabilities

/** One provider's result over the whole corpus. */
data class ProviderReport(
    val providerId: String,
    val promptVersion: String,
    val results: List<CaseResult>,
) {
    val cases: Int get() = results.size
    val passed: Int get() = results.count { it.passed }
    val accuracy: Double get() = if (cases == 0) 0.0 else passed.toDouble() / cases
    val costUsd: Double get() = results.sumOf { it.costUsd }
    val tokensIn: Int get() = results.sumOf { it.tokensIn }
    val tokensOut: Int get() = results.sumOf { it.tokensOut }

    /** Latency of the case in the middle: one slow case must not hide a fast median. */
    val latencyP50Millis: Long get() = percentile(0.50)

    val latencyP95Millis: Long get() = percentile(0.95)

    val failures: List<CaseResult> get() = results.filterNot { it.passed }

    fun scoreOf(category: AiCategory): CategoryScore {
        val inCategory = results.filter { it.case.category == category }
        return CategoryScore(
            category = category,
            cases = inCategory.size,
            passed = inCategory.count { it.passed },
            failedCaseIds = inCategory.filterNot { it.passed }.map { it.case.id },
        )
    }

    val byCategory: List<CategoryScore> = AiCategory.entries.map(::scoreOf).filter { it.cases > 0 }

    private fun percentile(fraction: Double): Long {
        if (results.isEmpty()) return 0L
        val sorted = results.map { it.latencyMillis }.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

data class CategoryScore(
    val category: AiCategory,
    val cases: Int,
    val passed: Int,
    val failedCaseIds: List<String>,
) {
    val accuracy: Double get() = if (cases == 0) 0.0 else passed.toDouble() / cases
}

/**
 * What changed between two runs.
 *
 * A harness that only prints a number is a dashboard. The value is in the
 * difference: which cases a new prompt fixed, which it broke, and what the
 * change cost in money and in milliseconds.
 */
data class RegressionReport(
    val baselineId: String,
    val candidateId: String,
    val fixed: List<String>,
    val broken: List<String>,
    val accuracyDelta: Double,
    val costDeltaUsd: Double,
    val latencyP95DeltaMillis: Long,
) {
    /** A candidate that breaks a case is not a promotion, whatever it gained. */
    val safeToPromote: Boolean get() = broken.isEmpty() && accuracyDelta >= 0.0
}

data class AiReport(val reports: List<ProviderReport>) {

    /**
     * Providers ordered by what this product actually needs: cases passed
     * first, then cost, then latency. A model that is one case better and
     * thirty times the price is reported as such, and not as a winner.
     */
    fun leaderboard(): List<ProviderReport> = reports.sortedWith(
        compareByDescending<ProviderReport> { it.passed }
            .thenBy { it.costUsd }
            .thenBy { it.latencyP50Millis },
    )

    fun regression(baselineId: String, candidateId: String): RegressionReport? {
        val baseline = reports.firstOrNull { it.providerId == baselineId } ?: return null
        val candidate = reports.firstOrNull { it.providerId == candidateId } ?: return null
        val baselineFailed = baseline.results.filterNot { it.passed }.map { it.case.id }.toSet()
        val candidateFailed = candidate.results.filterNot { it.passed }.map { it.case.id }.toSet()
        return RegressionReport(
            baselineId = baselineId,
            candidateId = candidateId,
            fixed = (baselineFailed - candidateFailed).sorted(),
            broken = (candidateFailed - baselineFailed).sorted(),
            accuracyDelta = candidate.accuracy - baseline.accuracy,
            costDeltaUsd = candidate.costUsd - baseline.costUsd,
            latencyP95DeltaMillis = candidate.latencyP95Millis - baseline.latencyP95Millis,
        )
    }

    /** A report a person can read, and the format the harness is reviewed in. */
    fun render(): String = buildString {
        appendLine("provider            prompt        passed  accuracy  p50ms  p95ms  costUsd")
        for (report in leaderboard()) {
            appendLine(
                "%-19s %-13s %3d/%-3d %8.3f %6d %6d %8.6f".format(
                    report.providerId,
                    report.promptVersion,
                    report.passed,
                    report.cases,
                    report.accuracy,
                    report.latencyP50Millis,
                    report.latencyP95Millis,
                    report.costUsd,
                ),
            )
            for (failure in report.failures) {
                appendLine("    ${failure.case.id} [${failure.case.category}]: ${failure.mismatches.joinToString("; ")}")
            }
        }
    }.trimEnd()
}

/**
 * The harness.
 *
 * It runs every provider over every case, scores each answer with
 * [AiEvaluator], and reports accuracy, latency and cost per provider and per
 * category. Latency is measured here rather than reported by the provider,
 * because the number that matters is the one the caller experiences, and a
 * provider that reports its own latency is grading its own homework.
 */
class AiHarness(
    private val capabilities: ConnectorCapabilities,
    private val nanoTime: () -> Long = { System.nanoTime() },
) {

    fun run(cases: List<AiCase> = AiCorpus.cases, providers: List<ModelProvider>): AiReport {
        val reports = providers.map { provider -> runOne(provider, cases) }
        return AiReport(reports)
    }

    fun runOne(provider: ModelProvider, cases: List<AiCase> = AiCorpus.cases): ProviderReport {
        val results = cases.map { case ->
            val started = nanoTime()
            val answer = provider.interpret(case.text, capabilities)
            val elapsed = (nanoTime() - started) / 1_000_000
            AiEvaluator.evaluate(
                case = case,
                answer = answer.interpretation,
                latencyMillis = elapsed,
                tokensIn = answer.tokensIn,
                tokensOut = answer.tokensOut,
                pricing = provider.pricing,
            )
        }
        return ProviderReport(
            providerId = provider.id,
            promptVersion = provider.promptVersion,
            results = results,
        )
    }
}
