package dev.redflag.core.gemma

import dev.redflag.core.ContractBundle
import dev.redflag.core.model.Features

/**
 * One interpretation of a Gemma answer, shared by LiteEngine, DebugBenchmarkActivity and (Python twin
 * gemma_repair.interpret) the Mac eval:
 *   strict parse -> salvage (canonical keys) -> [one follow-up for missing keys] -> FeatureRepair -> evidence policy.
 */
object GemmaPipeline {
    data class FollowUpPlan(val prompt: String, val missing: List<String>)

    data class Result(
        val features: Features?,
        val strictOk: Boolean,
        val salvageNotes: List<String>,
        val missingBefore: List<String>,
        val asked: List<String>,
        val missingAfter: List<String>,
        val repairs: List<String>,
        val coreRepaired: Boolean,
    ) {
        /** FeatureRepair invented something -> features_repaired (D2 floor if core / dirty signals). */
        val repaired: Boolean get() = repairs.isNotEmpty()
    }

    /** null when the first answer is already valid or nothing is missing after salvage. */
    fun followUpFor(bundle: ContractBundle, first: String): FollowUpPlan? {
        if (GemmaOutputParser.parse(first).features != null) return null
        val missing = GemmaSalvage.missingKeys(GemmaSalvage.salvage(first).obj)
        if (missing.isEmpty()) return null
        return FollowUpPlan(GemmaSalvage.followupPrompt(bundle, missing), missing)
    }

    fun interpret(first: String, followup: String?, asked: List<String>): Result {
        val strict = GemmaOutputParser.parse(first)
        if (strict.features != null) {
            return Result(GemmaSalvage.enforceEvidence(strict.features), true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
        }
        val sal = GemmaSalvage.salvage(first)
        val before = GemmaSalvage.missingKeys(sal.obj)
        val obj = if (followup != null && asked.isNotEmpty()) GemmaSalvage.merge(sal.obj, GemmaSalvage.salvage(followup).obj, asked) else sal.obj
        if (obj == null || obj.isEmpty()) {
            return Result(null, false, sal.notes, before, asked, GemmaSalvage.KEYS, emptyList(), false)
        }
        val after = GemmaSalvage.missingKeys(obj)
        val fixed = FeatureRepair.repair(obj)
        return Result(
            GemmaSalvage.enforceEvidence(fixed.features), false, sal.notes, before, asked, after,
            fixed.repairs, GemmaSalvage.isCoreRepair(fixed.repairs),
        )
    }
}
