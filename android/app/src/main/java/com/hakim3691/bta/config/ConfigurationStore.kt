package com.hakim3691.bta.config

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hakim3691.bta.core.AutoTuner
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.kelly.KellyConfig
import com.hakim3691.bta.core.InvestmentSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bta_config")

/**
 * Persists public trading configuration (investment ranges, thresholds,
 * strategy) in DataStore and applies it to the engine's [ExecutionConfig] /
 * [InvestmentSpec.DEFAULTS], mirroring the original config.json mechanism.
 *
 * Secrets are NOT stored here (see [com.hakim3691.bta.security.CredentialStore]).
 */
class ConfigurationStore(private val context: Context) {

    private object Keys {
        val KELLY_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("kelly_enabled")
        val KELLY_BUDGET = doublePreferencesKey("kelly_budget_usdt")
        val KELLY_PROB = doublePreferencesKey("kelly_required_probability")
        val KELLY_MAX_FRACTION = doublePreferencesKey("kelly_max_fraction")
        val KELLY_MAX_ALLOC = doublePreferencesKey("kelly_max_allocation")
        val KELLY_INV_FRACTION = doublePreferencesKey("kelly_investment_fraction")
        val KELLY_MIN_TRADE = doublePreferencesKey("kelly_min_trade_usdt")
        val KELLY_SIGMA = doublePreferencesKey("kelly_default_sigma")
        val INVESTMENT_BASE = stringPreferencesKey("invest_base")
        val INVESTMENT_MIN = doublePreferencesKey("invest_min")
        val INVESTMENT_MAX = doublePreferencesKey("invest_max")
        val INVESTMENT_STEP = doublePreferencesKey("invest_step")
        val FEE = doublePreferencesKey("exec_fee")
        val PROFIT_THRESHOLD = doublePreferencesKey("exec_profit_threshold")
        val AGE_THRESHOLD = intPreferencesKey("exec_age_threshold")
        val STRATEGY = stringPreferencesKey("exec_strategy")
        val CAP = intPreferencesKey("exec_cap")
        val DEPTH = intPreferencesKey("scanning_depth")
        val INVESTMENT_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("invest_auto")
        val FEE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("fee_auto")
        val PROFIT_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("profit_auto")
        val AGE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("age_auto")
        val CAP_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("cap_auto")
        val DEPTH_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("depth_auto")
        val STRATEGY_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("strategy_auto")
        val DEDUPE_MIRRORS = androidx.datastore.preferences.core.booleanPreferencesKey("dedupe_mirrors")
        val PREFLIGHT = androidx.datastore.preferences.core.booleanPreferencesKey("preflight_enabled")
        val PREFLIGHT_MARGIN = doublePreferencesKey("preflight_margin_percent")
        val DEADLINE = intPreferencesKey("execution_deadline_ms")
        val DEADLINE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("execution_deadline_auto")
        val STAGED = androidx.datastore.preferences.core.booleanPreferencesKey("staged_execution")
        val ARM_TTL = intPreferencesKey("arm_ttl_ms")
        val ARM_TTL_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("arm_ttl_auto")
        val ARM_MARGIN = doublePreferencesKey("arming_margin_percent")
        val ARM_MARGIN_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("arming_margin_auto")
        val ABANDON_MARGIN = doublePreferencesKey("abandon_margin_percent")
        val ABANDON_MARGIN_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("abandon_margin_auto")
        val MICRO_GATES = androidx.datastore.preferences.core.booleanPreferencesKey("microstructure_gates")
        val SPREAD_TIGHT = doublePreferencesKey("spread_tight_max_bps")
        val SPREAD_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("spread_auto")
        val IMBALANCE = doublePreferencesKey("imbalance_max_abs")
        val IMBALANCE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("imbalance_auto")
        val CADENCE = intPreferencesKey("cadence_max_inter_arrival_ms")
        val CADENCE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("cadence_auto")
        val MAX_ARMED = intPreferencesKey("max_armed_opportunities")
        val MAX_ARMED_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("max_armed_auto")
        val PAPER_LATENCY = intPreferencesKey("paper_latency_ms")
        val PAPER_LATENCY_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("paper_latency_auto")
        val PAPER_SLIPPAGE = doublePreferencesKey("paper_slippage_percent")
        val PAPER_SLIPPAGE_AUTO = androidx.datastore.preferences.core.booleanPreferencesKey("paper_slippage_auto")
    }

    data class PublicConfig(
        // Paper auto-trading is on by default. An install that never touched the
        // toggle reads as ON; one that deliberately switched it off is stored
        // explicitly and keeps that choice.
        val kellyEnabled: Boolean = true,
        val kellyBudgetUsdt: Double = 100.0,
        val kellyRequiredProbability: Double = 0.80,
        val kellyMaxFraction: Double = 0.5,
        val kellyMaxAllocation: Double = 0.25,
        val kellyInvestmentFraction: Double = 0.10,
        val kellyMinTradeUsdt: Double = 5.0,
        val kellyDefaultSigma: Double = 0.35,
        val base: String = "BTC",
        val min: Double = 0.010,
        val max: Double = 0.015,
        val step: Double = 0.005,
        val fee: Double = 0.10,
        val profitThreshold: Double = 0.0,
        val ageThresholdMs: Int = 5000,
        val strategy: String = "linear",
        val cap: Int = 1,
        val depth: Int = 50,
        // AUTO switches: true = AutoTuner derives the value, false = use what is stored here
        val investmentAuto: Boolean = true,
        val feeAuto: Boolean = true,
        val profitThresholdAuto: Boolean = true,
        val ageThresholdAuto: Boolean = true,
        val capAuto: Boolean = true,
        val depthAuto: Boolean = true,
        val strategyAuto: Boolean = true,
        /**
         * Opt-in. The mirror trades the opposite side of the same books, so
         * dropping it drops real opportunities; off by default.
         */
        val dedupeMirroredTriangles: Boolean = false,
        /** Re-price the remaining legs against fresh books before each order. */
        val preFlightCheckEnabled: Boolean = true,
        /** Extra percent a pre-flight projection must clear by, above the profit gate. */
        val preFlightMarginPercent: Double = 0.0,
        /** Wall-clock budget for one round trip; 0 disables the deadline. */
        val executionDeadlineMs: Int = 0,
        /** Derive the deadline from the measured REST latency. */
        val executionDeadlineAuto: Boolean = true,
        // --- staged execution (arm -> wait -> fire) ---
        /** Arm a qualifying triangle and re-evaluate it instead of firing at once. */
        val stagedExecutionEnabled: Boolean = true,
        /** How long an armed opportunity stays eligible, counted from detection. */
        val armTtlMs: Int = 2000,
        val armTtlAuto: Boolean = true,
        /** Extra percent over the profit gate an armed opportunity must clear to fire. */
        val armingMarginPercent: Double = 0.10,
        val armingMarginAuto: Boolean = true,
        /** How far below the profit gate the edge may fall before abandoning. */
        val abandonMarginPercent: Double = 0.05,
        val abandonMarginAuto: Boolean = true,
        /** Microstructure gating heuristics (spread, imbalance, tick cadence). */
        val microstructureGatesEnabled: Boolean = true,
        val spreadTightMaxBps: Double = 8.0,
        val spreadAuto: Boolean = true,
        val imbalanceMaxAbs: Double = 0.35,
        val imbalanceAuto: Boolean = true,
        val cadenceMaxInterArrivalMs: Int = 750,
        val cadenceAuto: Boolean = true,
        val maxArmedOpportunities: Int = 8,
        val maxArmedAuto: Boolean = true,
        // --- paper execution realism ---
        /** Simulated per-leg order latency; 0 makes the simulator frictionless. */
        val paperLatencyMs: Int = 0,
        val paperLatencyAuto: Boolean = true,
        val paperSlippagePercent: Double = 0.0,
        val paperSlippageAuto: Boolean = true
    )

    val configFlow: Flow<PublicConfig> = context.dataStore.data.map { prefs ->
        PublicConfig(
            kellyEnabled = prefs[Keys.KELLY_ENABLED] ?: true,
            kellyBudgetUsdt = prefs[Keys.KELLY_BUDGET] ?: 100.0,
            kellyRequiredProbability = prefs[Keys.KELLY_PROB] ?: 0.80,
            kellyMaxFraction = prefs[Keys.KELLY_MAX_FRACTION] ?: 0.5,
            kellyMaxAllocation = prefs[Keys.KELLY_MAX_ALLOC] ?: 0.25,
            kellyInvestmentFraction = prefs[Keys.KELLY_INV_FRACTION] ?: 0.10,
            kellyMinTradeUsdt = prefs[Keys.KELLY_MIN_TRADE] ?: 5.0,
            kellyDefaultSigma = prefs[Keys.KELLY_SIGMA] ?: 0.35,
            base = prefs[Keys.INVESTMENT_BASE] ?: "BTC",
            min = prefs[Keys.INVESTMENT_MIN] ?: 0.010,
            max = prefs[Keys.INVESTMENT_MAX] ?: 0.015,
            step = prefs[Keys.INVESTMENT_STEP] ?: 0.005,
            fee = prefs[Keys.FEE] ?: 0.10,
            profitThreshold = prefs[Keys.PROFIT_THRESHOLD] ?: 0.0,
            ageThresholdMs = prefs[Keys.AGE_THRESHOLD] ?: 5000,
            strategy = prefs[Keys.STRATEGY] ?: "linear",
            cap = prefs[Keys.CAP] ?: 1,
            depth = prefs[Keys.DEPTH] ?: 50,
            investmentAuto = prefs[Keys.INVESTMENT_AUTO] ?: true,
            feeAuto = prefs[Keys.FEE_AUTO] ?: true,
            profitThresholdAuto = prefs[Keys.PROFIT_AUTO] ?: true,
            ageThresholdAuto = prefs[Keys.AGE_AUTO] ?: true,
            capAuto = prefs[Keys.CAP_AUTO] ?: true,
            depthAuto = prefs[Keys.DEPTH_AUTO] ?: true,
            strategyAuto = prefs[Keys.STRATEGY_AUTO] ?: true,
            dedupeMirroredTriangles = prefs[Keys.DEDUPE_MIRRORS] ?: false,
            preFlightCheckEnabled = prefs[Keys.PREFLIGHT] ?: true,
            preFlightMarginPercent = prefs[Keys.PREFLIGHT_MARGIN] ?: 0.0,
            executionDeadlineMs = prefs[Keys.DEADLINE] ?: 0,
            executionDeadlineAuto = prefs[Keys.DEADLINE_AUTO] ?: true,
            stagedExecutionEnabled = prefs[Keys.STAGED] ?: true,
            armTtlMs = prefs[Keys.ARM_TTL] ?: 2000,
            armTtlAuto = prefs[Keys.ARM_TTL_AUTO] ?: true,
            armingMarginPercent = prefs[Keys.ARM_MARGIN] ?: 0.10,
            armingMarginAuto = prefs[Keys.ARM_MARGIN_AUTO] ?: true,
            abandonMarginPercent = prefs[Keys.ABANDON_MARGIN] ?: 0.05,
            abandonMarginAuto = prefs[Keys.ABANDON_MARGIN_AUTO] ?: true,
            microstructureGatesEnabled = prefs[Keys.MICRO_GATES] ?: true,
            spreadTightMaxBps = prefs[Keys.SPREAD_TIGHT] ?: 8.0,
            spreadAuto = prefs[Keys.SPREAD_AUTO] ?: true,
            imbalanceMaxAbs = prefs[Keys.IMBALANCE] ?: 0.35,
            imbalanceAuto = prefs[Keys.IMBALANCE_AUTO] ?: true,
            cadenceMaxInterArrivalMs = prefs[Keys.CADENCE] ?: 750,
            cadenceAuto = prefs[Keys.CADENCE_AUTO] ?: true,
            maxArmedOpportunities = prefs[Keys.MAX_ARMED] ?: 8,
            maxArmedAuto = prefs[Keys.MAX_ARMED_AUTO] ?: true,
            paperLatencyMs = prefs[Keys.PAPER_LATENCY] ?: 0,
            paperLatencyAuto = prefs[Keys.PAPER_LATENCY_AUTO] ?: true,
            paperSlippagePercent = prefs[Keys.PAPER_SLIPPAGE] ?: 0.0,
            paperSlippageAuto = prefs[Keys.PAPER_SLIPPAGE_AUTO] ?: true
        )
    }

    suspend fun current(): PublicConfig = configFlow.first()

    suspend fun save(config: PublicConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KELLY_ENABLED] = config.kellyEnabled
            prefs[Keys.KELLY_BUDGET] = config.kellyBudgetUsdt
            prefs[Keys.KELLY_PROB] = config.kellyRequiredProbability
            prefs[Keys.KELLY_MAX_FRACTION] = config.kellyMaxFraction
            prefs[Keys.KELLY_MAX_ALLOC] = config.kellyMaxAllocation
            prefs[Keys.KELLY_INV_FRACTION] = config.kellyInvestmentFraction
            prefs[Keys.KELLY_MIN_TRADE] = config.kellyMinTradeUsdt
            prefs[Keys.KELLY_SIGMA] = config.kellyDefaultSigma
            prefs[Keys.INVESTMENT_BASE] = config.base.uppercase().trim()
            prefs[Keys.INVESTMENT_MIN] = config.min
            prefs[Keys.INVESTMENT_MAX] = config.max
            prefs[Keys.INVESTMENT_STEP] = config.step
            prefs[Keys.FEE] = config.fee
            prefs[Keys.PROFIT_THRESHOLD] = config.profitThreshold
            prefs[Keys.AGE_THRESHOLD] = config.ageThresholdMs
            prefs[Keys.STRATEGY] = config.strategy
            prefs[Keys.CAP] = config.cap
            prefs[Keys.DEPTH] = config.depth
            prefs[Keys.INVESTMENT_AUTO] = config.investmentAuto
            prefs[Keys.FEE_AUTO] = config.feeAuto
            prefs[Keys.PROFIT_AUTO] = config.profitThresholdAuto
            prefs[Keys.AGE_AUTO] = config.ageThresholdAuto
            prefs[Keys.CAP_AUTO] = config.capAuto
            prefs[Keys.DEPTH_AUTO] = config.depthAuto
            prefs[Keys.STRATEGY_AUTO] = config.strategyAuto
            prefs[Keys.DEDUPE_MIRRORS] = config.dedupeMirroredTriangles
            prefs[Keys.PREFLIGHT] = config.preFlightCheckEnabled
            prefs[Keys.PREFLIGHT_MARGIN] = config.preFlightMarginPercent
            prefs[Keys.DEADLINE] = config.executionDeadlineMs
            prefs[Keys.DEADLINE_AUTO] = config.executionDeadlineAuto
            prefs[Keys.STAGED] = config.stagedExecutionEnabled
            prefs[Keys.ARM_TTL] = config.armTtlMs
            prefs[Keys.ARM_TTL_AUTO] = config.armTtlAuto
            prefs[Keys.ARM_MARGIN] = config.armingMarginPercent
            prefs[Keys.ARM_MARGIN_AUTO] = config.armingMarginAuto
            prefs[Keys.ABANDON_MARGIN] = config.abandonMarginPercent
            prefs[Keys.ABANDON_MARGIN_AUTO] = config.abandonMarginAuto
            prefs[Keys.MICRO_GATES] = config.microstructureGatesEnabled
            prefs[Keys.SPREAD_TIGHT] = config.spreadTightMaxBps
            prefs[Keys.SPREAD_AUTO] = config.spreadAuto
            prefs[Keys.IMBALANCE] = config.imbalanceMaxAbs
            prefs[Keys.IMBALANCE_AUTO] = config.imbalanceAuto
            prefs[Keys.CADENCE] = config.cadenceMaxInterArrivalMs
            prefs[Keys.CADENCE_AUTO] = config.cadenceAuto
            prefs[Keys.MAX_ARMED] = config.maxArmedOpportunities
            prefs[Keys.MAX_ARMED_AUTO] = config.maxArmedAuto
            prefs[Keys.PAPER_LATENCY] = config.paperLatencyMs
            prefs[Keys.PAPER_LATENCY_AUTO] = config.paperLatencyAuto
            prefs[Keys.PAPER_SLIPPAGE] = config.paperSlippagePercent
            prefs[Keys.PAPER_SLIPPAGE_AUTO] = config.paperSlippageAuto
        }
        applyToEngine(config)
    }

    /**
     * Pushes the stored config into the engine's globals (CONFIG.* equivalent).
     *
     * Fields whose AUTO flag is set are deliberately *not* written here: those
     * belong to [AutoTuner], which owns them until the user switches them back
     * to MANUAL. Only the investment base is always applied, because
     * MarketCache needs to know which bases to build triangles from.
     */
    fun applyToEngine(config: PublicConfig) {
        KellyConfig.enabled = config.kellyEnabled
        KellyConfig.budgetUsdt = config.kellyBudgetUsdt
        KellyConfig.requiredProbability = config.kellyRequiredProbability
        KellyConfig.maxKellyFraction = config.kellyMaxFraction
        KellyConfig.maxAllocationPerTrade = config.kellyMaxAllocation
        KellyConfig.investmentFractionOfKelly = config.kellyInvestmentFraction
        KellyConfig.minTradeUsdt = config.kellyMinTradeUsdt
        KellyConfig.defaultSigmaPercent = config.kellyDefaultSigma
        InvestmentSpec.DEFAULTS.clear()
        InvestmentSpec.DEFAULTS[config.base.uppercase().trim()] = InvestmentSpec(
            base = config.base.uppercase().trim(),
            min = config.min,
            max = config.max,
            step = config.step
        )
        ExecutionConfig.investmentAuto = config.investmentAuto
        ExecutionConfig.feeAuto = config.feeAuto
        ExecutionConfig.profitThresholdAuto = config.profitThresholdAuto
        ExecutionConfig.ageThresholdAuto = config.ageThresholdAuto
        ExecutionConfig.capAuto = config.capAuto
        ExecutionConfig.depthAuto = config.depthAuto
        ExecutionConfig.strategyAuto = config.strategyAuto
        ExecutionConfig.dedupeMirroredTriangles = config.dedupeMirroredTriangles
        ExecutionConfig.preFlightCheckEnabled = config.preFlightCheckEnabled
        ExecutionConfig.preFlightMarginPercent = config.preFlightMarginPercent
        ExecutionConfig.executionDeadlineAuto = config.executionDeadlineAuto
        if (!config.executionDeadlineAuto) ExecutionConfig.executionDeadlineMs = config.executionDeadlineMs
        ExecutionConfig.stagedExecutionEnabled = config.stagedExecutionEnabled
        ExecutionConfig.armTtlAuto = config.armTtlAuto
        ExecutionConfig.armingMarginAuto = config.armingMarginAuto
        ExecutionConfig.abandonMarginAuto = config.abandonMarginAuto
        ExecutionConfig.spreadAuto = config.spreadAuto
        ExecutionConfig.imbalanceAuto = config.imbalanceAuto
        ExecutionConfig.cadenceAuto = config.cadenceAuto
        ExecutionConfig.maxArmedAuto = config.maxArmedAuto
        ExecutionConfig.paperLatencyAuto = config.paperLatencyAuto
        ExecutionConfig.paperSlippageAuto = config.paperSlippageAuto
        if (!config.armTtlAuto) ExecutionConfig.armTtlMs = config.armTtlMs
        if (!config.armingMarginAuto) ExecutionConfig.armingMarginPercent = config.armingMarginPercent
        if (!config.abandonMarginAuto) ExecutionConfig.abandonMarginPercent = config.abandonMarginPercent
        if (!config.spreadAuto) ExecutionConfig.spreadTightMaxBps = config.spreadTightMaxBps
        if (!config.imbalanceAuto) ExecutionConfig.imbalanceMaxAbs = config.imbalanceMaxAbs
        if (!config.cadenceAuto) ExecutionConfig.cadenceMaxInterArrivalMs = config.cadenceMaxInterArrivalMs
        if (!config.maxArmedAuto) ExecutionConfig.maxArmedOpportunities = config.maxArmedOpportunities
        if (!config.paperLatencyAuto) ExecutionConfig.paperLatencyMs = config.paperLatencyMs
        if (!config.paperSlippageAuto) ExecutionConfig.paperSlippagePercent = config.paperSlippagePercent
        ExecutionConfig.microstructureGatesEnabled = config.microstructureGatesEnabled

        if (!config.investmentAuto) {
            InvestmentSpec.DEFAULTS[config.base.uppercase().trim()] = InvestmentSpec(
                base = config.base.uppercase().trim(),
                min = config.min,
                max = config.max,
                step = config.step
            )
        }
        if (!config.feeAuto) ExecutionConfig.feePercent = config.fee
        if (!config.profitThresholdAuto) ExecutionConfig.profitThreshold = config.profitThreshold
        if (!config.ageThresholdAuto) ExecutionConfig.ageThresholdMs = config.ageThresholdMs
        if (!config.capAuto) ExecutionConfig.cap = config.cap
        if (!config.depthAuto) ExecutionConfig.scanningDepth = config.depth
        if (!config.strategyAuto) ExecutionConfig.strategy = config.strategy
    }
}
