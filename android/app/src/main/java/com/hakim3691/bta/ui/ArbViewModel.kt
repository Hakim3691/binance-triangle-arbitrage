package com.hakim3691.bta.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hakim3691.bta.ArbApplication
import com.hakim3691.bta.config.ConfigurationStore
import com.hakim3691.bta.core.ArbitrageExecution
import com.hakim3691.bta.core.ArmRecord
import com.hakim3691.bta.core.ArmingStats
import com.hakim3691.bta.core.ExecutionState
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.core.InvestmentSpec
import com.hakim3691.bta.kelly.KellyConfig
import com.hakim3691.bta.kelly.KellyPaperTrader
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.scanner.ConnectionStatus
import com.hakim3691.bta.scanner.OpportunityUi
import com.hakim3691.bta.scanner.ScanPerformance
import com.hakim3691.bta.scanner.ScannerController
import com.hakim3691.bta.scanner.ScannerState
import com.hakim3691.bta.scanner.TradingMode
import com.hakim3691.bta.research.ResearchRecorder
import com.hakim3691.bta.security.CredentialStore
import com.hakim3691.bta.security.KeyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single shared ViewModel exposing the ScannerController state reactively to
 * Compose. All engine work happens off the main thread; the ViewModel only
 * forwards StateFlows and issues user commands.
 */
class ArbViewModel(
    private val app: ArbApplication,
    val credentialStore: CredentialStore,
    val configurationStore: ConfigurationStore
) : ViewModel() {

    private val controller: ScannerController = app.scannerController

    val mode: StateFlow<TradingMode> = controller.mode
    val scannerState: StateFlow<ScannerState> = controller.state
    val connection: StateFlow<ConnectionStatus> = controller.connection
    val opportunities: StateFlow<List<OpportunityUi>> = controller.opportunities
    val currentOpportunity: StateFlow<OpportunityUi?> = controller.currentOpportunity
    val executions: StateFlow<List<ExecutionState>> = controller.executionStates
    val performance: StateFlow<ScanPerformance> = controller.performance
    val tradeHistory: StateFlow<List<OpportunityUi>> = controller.tradesFeed
    val kellyStats: StateFlow<KellyPaperTrader.Stats?> = controller.kellyStats
    val gateStatus: StateFlow<String> = controller.gateStatus
    val ageThresholdInfo: StateFlow<String> = controller.ageThresholdInfo
    val autoTuneInfo: StateFlow<String> = controller.autoTuneInfo
    val feedHealth: StateFlow<com.hakim3691.bta.scanner.FeedHealth> = controller.feedHealth
    val manualCloseRequired: StateFlow<List<String>> = controller.manualCloseRequired
    val wsMessages: StateFlow<Long> = controller.wsMessages
    val lastExecutedOpportunity: StateFlow<OpportunityUi?> = controller.lastExecutedOpportunity
    /** Armed / fired / abandoned telemetry for the staged executor. */
    val armingStats: StateFlow<ArmingStats> = controller.armingStats
    val armLog: StateFlow<List<ArmRecord>> = controller.armLog

    /** Stage 3 research collection status + file access. */
    val researchInfo: StateFlow<String> = controller.researchInfo

    val logs = LogRepository.entries

    val paperBalances: StateFlow<Map<String, Double>> = controller.paperBalances

    val config: StateFlow<ConfigurationStore.PublicConfig> = configurationStore.configFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, ConfigurationStore.PublicConfig())

    /** Phase 3: what the centrally stored Binance key is and may do. */
    val keyStatus: StateFlow<KeyStatus> = controller.keyStatus

    /** One-shot result message for user actions (save config, live switch, etc). */
    val actionMessage = MutableStateFlow<String?>(null)

    fun startScanner() {
        viewModelScope.launch {
            controller.start()
        }
    }

    fun stopScanner() {
        controller.stop()
    }

    fun updateKellySettings(enabled: Boolean, budgetUsdt: Double, requiredProbability: Double, investmentFraction: Double, minTradeUsdt: Double) {
        viewModelScope.launch {
            val errors = KellyConfig.validate()
            if (errors.isNotEmpty()) {
                actionMessage.value = errors.first()
                return@launch
            }
            controller.updateKellySettings(enabled, budgetUsdt, requiredProbability, investmentFraction, minTradeUsdt)
            // Persist alongside the rest of the public config
            val current = configurationStore.current()
            configurationStore.save(
                current.copy(
                    kellyEnabled = enabled,
                    kellyBudgetUsdt = budgetUsdt,
                    kellyRequiredProbability = requiredProbability,
                    kellyInvestmentFraction = investmentFraction,
                    kellyMinTradeUsdt = minTradeUsdt
                )
            )
            actionMessage.value = if (enabled)
                "Kelly auto-trading ON: $budgetUsdt USDT, P >= ${requiredProbability * 100}%"
            else "Kelly auto-trading disabled"
        }
    }

    /** Switches the execution age gate between self-tuning and manual mode. */
    fun setAgeGateMode(auto: Boolean) {
        ExecutionConfig.ageThresholdAuto = auto
        viewModelScope.launch {
            val current = configurationStore.current()
            configurationStore.save(current.copy(ageThresholdAuto = auto))
            LogRepository.info(
                "settings",
                "Age gate mode: " + if (auto) "AUTO (self-tuned from market data)" else "MANUAL"
            )
        }
    }

    /**
     * Persists the per-field AUTO switches and re-derives the owned values
     * immediately so the user sees the effect of flipping a switch without
     * waiting for the next scan cycle.
     */
    fun applyAutoTuneModes(config: ConfigurationStore.PublicConfig) {
        viewModelScope.launch {
            configurationStore.save(
                config.copy(
                    investmentAuto = config.investmentAuto,
                    feeAuto = config.feeAuto,
                    profitThresholdAuto = config.profitThresholdAuto,
                    ageThresholdAuto = config.ageThresholdAuto,
                    capAuto = config.capAuto,
                    depthAuto = config.depthAuto,
                    strategyAuto = config.strategyAuto
                )
            )
            controller.refreshAutoTune()
            actionMessage.value = "AUTO settings: " + ExecutionConfig.autoModeSummary()
        }
    }

    /**
     * Turns mirrored-triangle de-duplication on or off. Changing it requires a
     * scanner restart because the triangle universe is built during init.
     *
     * Off by default: the mirror crosses the opposite side of the same three
     * books, so at most one direction of a triangle is ever profitable and
     * which one it is changes with the market. De-duplicating trades half the
     * opportunity set for half the CPU.
     */
    fun setDedupeMirroredTriangles(enabled: Boolean) {
        ExecutionConfig.dedupeMirroredTriangles = enabled
        viewModelScope.launch {
            val current = configurationStore.current()
            configurationStore.save(current.copy(dedupeMirroredTriangles = enabled))
            LogRepository.info(
                "settings",
                "Mirrored triangle de-duplication " + if (enabled) "ON" else "OFF" +
                    " (restart the scanner to rebuild the universe)"
            )
            actionMessage.value =
                if (enabled) "De-duplication ON - one direction per triangle. Restart the scanner to apply."
                else "Both traversal directions active (default). Restart the scanner to apply."
        }
    }

    /** Applies and persists the execution guards from Settings. */
    fun setExecutionGuards(preFlight: Boolean, marginPercent: Double, deadlineMs: Int, deadlineAuto: Boolean) {
        ExecutionConfig.preFlightCheckEnabled = preFlight
        ExecutionConfig.preFlightMarginPercent = marginPercent
        ExecutionConfig.executionDeadlineAuto = deadlineAuto
        if (!deadlineAuto) ExecutionConfig.executionDeadlineMs = deadlineMs
        viewModelScope.launch {
            val current = configurationStore.current()
            configurationStore.save(
                current.copy(
                    preFlightCheckEnabled = preFlight,
                    preFlightMarginPercent = marginPercent,
                    executionDeadlineMs = ExecutionConfig.executionDeadlineMs,
                    executionDeadlineAuto = deadlineAuto
                )
            )
            LogRepository.info(
                "settings",
                "Guards: pre-flight " + if (preFlight) "ON (margin " + marginPercent + "%)" else "OFF" +
                    ", deadline " + if (ExecutionConfig.executionDeadlineMs == 0) "off"
                    else ExecutionConfig.executionDeadlineMs.toString() + "ms"
            )
        }
    }

    /**
     * Applies and persists the staged-execution settings from Settings: the
     * master switch, the arming window and bars, the microstructure gate
     * thresholds, and the paper engine's simulated friction.
     *
     * Every numeric field carries its own AUTO flag, so switching one to
     * MANUAL hands that single value back to the operator without affecting
     * the rest.
     */
    fun setStagedExecution(config: ConfigurationStore.PublicConfig) {
        configurationStore.applyToEngine(config)
        // A drop margin larger than the fire margin would make every armed
        // opportunity abandon on its first check, so the pair is validated
        // together rather than clamped silently behind the user's back.
        val errors = ExecutionConfig.validate()
        if (errors.isNotEmpty()) {
            viewModelScope.launch { actionMessage.value = errors.first() }
            return
        }
        viewModelScope.launch {
            configurationStore.save(config)
            controller.refreshAutoTune()
            LogRepository.info(
                "settings",
                "Staged execution " + (if (ExecutionConfig.stagedExecutionEnabled) "ON" else "OFF") +
                    ": fire at " + "%.4f".format(
                        ExecutionConfig.profitThreshold + ExecutionConfig.armingMarginPercent
                    ) + "%, abandon below " + "%.4f".format(
                        ExecutionConfig.profitThreshold - ExecutionConfig.abandonMarginPercent
                    ) + "%, window " + ExecutionConfig.armTtlMs + "ms, gates " +
                    (if (ExecutionConfig.microstructureGatesEnabled) "ON" else "OFF") +
                    " [spread " + "%.2f".format(ExecutionConfig.spreadTightMaxBps) + "bps, imbalance " +
                    "%.2f".format(ExecutionConfig.imbalanceMaxAbs) + ", cadence " +
                    ExecutionConfig.cadenceMaxInterArrivalMs + "ms]"
            )
            actionMessage.value = "Staged execution saved - no round trip is sent below break-even"
        }
    }

    /** Re-runs the auto-derivation now. */
    fun refreshAutoTune() {
        controller.refreshAutoTune()
        viewModelScope.launch {
            val current = configurationStore.current()
            configurationStore.applyToEngine(current)
            actionMessage.value = "Auto settings re-derived"
        }
    }

    /** Clears the manual-close warnings once the user has handled them. */
    fun clearManualCloseRequired() = controller.clearManualCloseRequired()

    /** The research CSVs written so far, newest first. */
    fun researchFiles(): List<java.io.File> = controller.researchRecorder?.allFiles() ?: emptyList()

    /** How many rows the current session has collected: (episodes, summaries). */
    fun researchRowCount(): Pair<Long, Long> {
        val r = controller.researchRecorder ?: return 0L to 0L
        return r.episodeCount to r.summaryCount
    }

    /**
     * Marks the start of a new collection session. Previous sessions' files
     * are kept on disk; this just makes the next rows land in fresh files.
     */
    fun startNewResearchSession() {
        controller.researchRecorder?.newSession()
        actionMessage.value = "New research session - next rows go to a fresh CSV"
    }

    fun resetCap() {
        controller.resetCapCounter()
    }

    fun saveCredentials(apiKey: String, apiSecret: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                credentialStore.saveCredentials(apiKey, apiSecret)
                LogRepository.info("settings", "Binance credentials updated (encrypted at rest)")
                // Phase 3: the key is usable immediately - verify its
                // capabilities and pull the account fee without a scanner start.
                controller.verifyKeyNow()
                actionMessage.value = "Credentials saved securely"
                onDone(true)
            } catch (e: Exception) {
                actionMessage.value = "Failed to save credentials: ${e.message}"
                onDone(false)
            }
        }
    }

    fun clearCredentials() {
        credentialStore.clear()
        viewModelScope.launch {
            controller.verifyKeyNow()
        }
        actionMessage.value = "Credentials removed from secure storage"
    }

    /** Phase 3: re-checks the stored key's permissions and account fee. */
    fun verifyKey() {
        viewModelScope.launch {
            controller.verifyKeyNow()
            val s = controller.keyStatus.value
            actionMessage.value = when {
                !s.stored -> "No key stored - paper mode runs without account fees"
                s.canSpotTrade == true -> "Key ${s.maskedKey}: read OK, spot trading enabled"
                s.canSpotTrade == false ->
                    "Key ${s.maskedKey}: read OK, but spot trading is DISABLED - paper fees only"
                else -> "Key ${s.maskedKey}: stored; permission check unavailable"
            }
        }
    }

    fun saveConfig(config: ConfigurationStore.PublicConfig) {
        viewModelScope.launch {
            // Validate first (port of Validation.configuration)
            configurationStore.applyToEngine(config)
            val errors = ExecutionConfig.validate()
            if (errors.isNotEmpty()) {
                actionMessage.value = errors.first()
                return@launch
            }
            configurationStore.save(config)
            actionMessage.value = "Configuration saved"
        }
    }

    /**
     * Enables live trading. Requires the user to have typed the confirmation
     * phrase and stored credentials, and refuses a key Binance reports as
     * unable to spot-trade. Never called automatically.
     */
    fun enableLiveTrading(confirmation: String) {
        viewModelScope.launch {
            if (confirmation != CONFIRMATION_PHRASE) {
                actionMessage.value = "Confirmation phrase incorrect"
                return@launch
            }
            if (!credentialStore.hasCredentials()) {
                actionMessage.value = "Save Binance API credentials first"
                return@launch
            }
            // Learn the key's server-side capabilities before arming orders:
            // an unchecked key gets checked now, so the refusal below can fire
            // before the first live triangle instead of at the exchange.
            if (controller.keyStatus.value.canSpotTrade == null) {
                controller.verifyKeyNow()
            }
            val enabled = controller.enableLiveTrading(
                credentialStore.getApiKey(),
                credentialStore.getApiSecret(),
                confirmed = true
            )
            actionMessage.value = if (enabled) "LIVE TRADING ENABLED"
            else "LIVE refused: the stored key has spot trading disabled on Binance"
        }
    }

    fun disableLiveTrading() {
        controller.disableLiveTrading()
        actionMessage.value = "Reverted to PAPER TRADING"
    }

    fun consumeActionMessage() {
        actionMessage.value = null
    }

    companion object {
        const val CONFIRMATION_PHRASE = "I UNDERSTAND REAL ORDERS WILL BE PLACED"
    }
}

/**
 * ViewModel factory wired from [com.hakim3691.bta.ArbApplication].
 */
class ArbViewModelFactory(private val app: ArbApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ArbViewModel(app, app.credentialStore, app.configurationStore) as T
}
