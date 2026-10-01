package com.hakim3691.bta.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hakim3691.bta.config.ConfigurationStore
import com.hakim3691.bta.core.ExecutionConfig
import com.hakim3691.bta.core.InvestmentSpec
import com.hakim3691.bta.scanner.TradingMode
import com.hakim3691.bta.ui.ArbViewModel
import com.hakim3691.bta.ui.theme.BtaYellow
import com.hakim3691.bta.ui.theme.CardBorder
import com.hakim3691.bta.ui.theme.LossRed
import com.hakim3691.bta.ui.theme.ProfitGreen
import com.hakim3691.bta.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val config by viewModel.config.collectAsState()
    val message by viewModel.actionMessage.collectAsState()
    val autoTuneInfo by viewModel.autoTuneInfo.collectAsState()

    // AUTO switches. True = AutoTuner owns the value, false = the field is editable.
    var investmentAuto by remember(config) { mutableStateOf(config.investmentAuto) }
    var feeAuto by remember(config) { mutableStateOf(config.feeAuto) }
    var profitAuto by remember(config) { mutableStateOf(config.profitThresholdAuto) }
    var ageAuto by remember(config) { mutableStateOf(config.ageThresholdAuto) }
    var capAuto by remember(config) { mutableStateOf(config.capAuto) }
    var depthAuto by remember(config) { mutableStateOf(config.depthAuto) }
    var strategyAuto by remember(config) { mutableStateOf(config.strategyAuto) }
    var dedupeMirrors by remember(config) { mutableStateOf(config.dedupeMirroredTriangles) }
    var preFlight by remember(config) { mutableStateOf(config.preFlightCheckEnabled) }
    var preFlightMargin by remember(config) { mutableStateOf(config.preFlightMarginPercent.toString()) }
    var deadline by remember(config) { mutableStateOf(config.executionDeadlineMs.toString()) }
    var deadlineAuto by remember(config) { mutableStateOf(config.executionDeadlineAuto) }
    var staged by remember(config) { mutableStateOf(config.stagedExecutionEnabled) }
    var microGates by remember(config) { mutableStateOf(config.microstructureGatesEnabled) }
    var armTtl by remember(config) { mutableStateOf(config.armTtlMs.toString()) }
    var armTtlAuto by remember(config) { mutableStateOf(config.armTtlAuto) }
    var fireMargin by remember(config) { mutableStateOf(config.armingMarginPercent.toString()) }
    var fireMarginAuto by remember(config) { mutableStateOf(config.armingMarginAuto) }
    var dropMargin by remember(config) { mutableStateOf(config.abandonMarginPercent.toString()) }
    var dropMarginAuto by remember(config) { mutableStateOf(config.abandonMarginAuto) }
    var spreadLimit by remember(config) { mutableStateOf(config.spreadTightMaxBps.toString()) }
    var spreadAuto by remember(config) { mutableStateOf(config.spreadAuto) }
    var imbalanceLimit by remember(config) { mutableStateOf(config.imbalanceMaxAbs.toString()) }
    var imbalanceAuto by remember(config) { mutableStateOf(config.imbalanceAuto) }
    var cadenceLimit by remember(config) { mutableStateOf(config.cadenceMaxInterArrivalMs.toString()) }
    var cadenceAuto by remember(config) { mutableStateOf(config.cadenceAuto) }
    var armSlots by remember(config) { mutableStateOf(config.maxArmedOpportunities.toString()) }
    var armSlotsAuto by remember(config) { mutableStateOf(config.maxArmedAuto) }
    var paperLatency by remember(config) { mutableStateOf(config.paperLatencyMs.toString()) }
    var paperLatencyAuto by remember(config) { mutableStateOf(config.paperLatencyAuto) }
    var paperSlippage by remember(config) { mutableStateOf(config.paperSlippagePercent.toString()) }
    var paperSlippageAuto by remember(config) { mutableStateOf(config.paperSlippageAuto) }

    val anyAuto = investmentAuto || feeAuto || profitAuto || capAuto || depthAuto || strategyAuto

    // What AutoTuner currently has in the engine; shown read-only while a field is AUTO.
    val derived = remember(autoTuneInfo, config.base) {
        InvestmentSpec.DEFAULTS[config.base.uppercase().trim()]
            ?: InvestmentSpec(config.base.uppercase().trim(), config.min, config.max, config.step)
    }

    var base by remember(config) { mutableStateOf(config.base) }
    var min by remember(config) { mutableStateOf(config.min.toString()) }
    var max by remember(config) { mutableStateOf(config.max.toString()) }
    var step by remember(config) { mutableStateOf(config.step.toString()) }
    var fee by remember(config) { mutableStateOf(config.fee.toString()) }
    var profitThreshold by remember(config) { mutableStateOf(config.profitThreshold.toString()) }
    var ageThreshold by remember(config) { mutableStateOf(config.ageThresholdMs.toString()) }
    var strategy by remember(config) { mutableStateOf(config.strategy) }
    var cap by remember(config) { mutableStateOf(config.cap.toString()) }
    var depth by remember(config) { mutableStateOf(config.depth.toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        ModeBanner(mode)
        Spacer(Modifier.height(10.dp))
        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        SectionTitle("Auto-derivation (AUTO = computed from live data)")
        Text(autoTuneInfo, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoBadge("INVEST", investmentAuto, { investmentAuto = it }, Modifier.weight(1f))
            AutoBadge("FEE", feeAuto, { feeAuto = it }, Modifier.weight(1f))
            AutoBadge("PROFIT", profitAuto, { profitAuto = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoBadge("DEPTH", depthAuto, { depthAuto = it }, Modifier.weight(1f))
            AutoBadge("CAP", capAuto, { capAuto = it }, Modifier.weight(1f))
            AutoBadge("STRATEGY", strategyAuto, { strategyAuto = it }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "De-dupe mirrored triangles (halves CPU, drops one direction of every triangle)",
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(
                checked = dedupeMirrors,
                onCheckedChange = {
                    dedupeMirrors = it
                    viewModel.setDedupeMirroredTriangles(it)
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pre-flight re-price before each leg",
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(
                checked = preFlight,
                onCheckedChange = { preFlight = it }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = preFlightMargin, onValueChange = { preFlightMargin = it },
                readOnly = !preFlight,
                label = { Text("MARGIN % above gate") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            AutoBadge("DEADLINE", deadlineAuto, { deadlineAuto = it })
            OutlinedTextField(
                value = if (deadlineAuto) ExecutionConfig.executionDeadlineMs.toString() else deadline,
                onValueChange = { deadline = it },
                readOnly = deadlineAuto,
                label = { Text("ms (0=off)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Staged execution (arm - wait - fire)")
        Text(
            "A qualifying triangle is armed instead of being sent, then re-checked on every update to its " +
                "own three books until the gates say go, the edge collapses, or the window closes. A round " +
                "trip is never sent below break-even - no gate can authorise a losing one. The imbalance " +
                "and cadence gates only ever delay a fire while the spread is also tight; on a wide spread " +
                "the triangle is sent or dropped immediately.",
            color = TextSecondary, fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Arm before firing", fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = staged, onCheckedChange = { staged = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoBadge("TTL", armTtlAuto, { armTtlAuto = it }, Modifier.weight(1f))
            AutoBadge("FIRE", fireMarginAuto, { fireMarginAuto = it }, Modifier.weight(1f))
            AutoBadge("DROP", dropMarginAuto, { dropMarginAuto = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (armTtlAuto) ExecutionConfig.armTtlMs.toString() else armTtl,
                onValueChange = { armTtl = it },
                readOnly = armTtlAuto,
                label = { Text("WINDOW ms") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (fireMarginAuto) fmt(ExecutionConfig.armingMarginPercent) else fireMargin,
                onValueChange = { fireMargin = it },
                readOnly = fireMarginAuto,
                label = { Text("FIRE MARGIN %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (dropMarginAuto) fmt(ExecutionConfig.abandonMarginPercent) else dropMargin,
                onValueChange = { dropMargin = it },
                readOnly = dropMarginAuto,
                label = { Text("DROP MARGIN %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Microstructure gates (spread / imbalance / tick cadence)",
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(checked = microGates, onCheckedChange = { microGates = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoBadge("SPREAD", spreadAuto, { spreadAuto = it }, Modifier.weight(1f))
            AutoBadge("IMBALANCE", imbalanceAuto, { imbalanceAuto = it }, Modifier.weight(1f))
            AutoBadge("CADENCE", cadenceAuto, { cadenceAuto = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (spreadAuto) fmt(ExecutionConfig.spreadTightMaxBps) else spreadLimit,
                onValueChange = { spreadLimit = it },
                readOnly = spreadAuto || !microGates,
                label = { Text("TIGHT SPREAD bps") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (imbalanceAuto) fmt(ExecutionConfig.imbalanceMaxAbs) else imbalanceLimit,
                onValueChange = { imbalanceLimit = it },
                readOnly = imbalanceAuto || !microGates,
                label = { Text("IMBALANCE 0-1") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (cadenceAuto) ExecutionConfig.cadenceMaxInterArrivalMs.toString() else cadenceLimit,
                onValueChange = { cadenceLimit = it },
                readOnly = cadenceAuto || !microGates,
                label = { Text("CADENCE ms") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoBadge("SLOTS", armSlotsAuto, { armSlotsAuto = it })
            OutlinedTextField(
                value = if (armSlotsAuto) ExecutionConfig.maxArmedOpportunities.toString() else armSlots,
                onValueChange = { armSlots = it },
                readOnly = armSlotsAuto,
                label = { Text("MAX ARMED") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("Paper execution realism")
        Text(
            "The simulator fills against live books. With zero latency and zero slippage it would fill " +
                "every order at exactly the projected price, so deadlines, pre-flight aborts and unwinds " +
                "could never happen and the staging behaviour above could not be measured at all. Both " +
                "values are derived from the measured round trip and the observed book spreads.",
            color = TextSecondary, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoBadge("LATENCY", paperLatencyAuto, { paperLatencyAuto = it })
            OutlinedTextField(
                value = if (paperLatencyAuto) ExecutionConfig.paperLatencyMs.toString() else paperLatency,
                onValueChange = { paperLatency = it },
                readOnly = paperLatencyAuto,
                label = { Text("ms / leg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoBadge("SLIPPAGE", paperSlippageAuto, { paperSlippageAuto = it })
            OutlinedTextField(
                value = if (paperSlippageAuto) fmt(ExecutionConfig.paperSlippagePercent) else paperSlippage,
                onValueChange = { paperSlippage = it },
                readOnly = paperSlippageAuto,
                label = { Text("% adverse fill") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("Investment (INVESTMENT.[BASE])")
        OutlinedTextField(
            value = base, onValueChange = { base = it },
            label = { Text("Base asset") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (investmentAuto) fmt(derived.min) else min,
                onValueChange = { min = it },
                readOnly = investmentAuto,
                label = { Text("MIN") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (investmentAuto) fmt(derived.max) else max,
                onValueChange = { max = it },
                readOnly = investmentAuto,
                label = { Text("MAX") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (investmentAuto) fmt(derived.step) else step,
                onValueChange = { step = it },
                readOnly = investmentAuto,
                label = { Text("STEP") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("Execution (EXECUTION.*)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (feeAuto) fmt(ExecutionConfig.feePercent) else fee,
                onValueChange = { fee = it },
                readOnly = feeAuto,
                label = { Text("FEE %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (profitAuto) fmt(ExecutionConfig.profitThreshold) else profitThreshold,
                onValueChange = { profitThreshold = it },
                readOnly = profitAuto,
                label = { Text("THRESHOLD.PROFIT %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AutoBadge("AGE", ageAuto, { ageAuto = it })
            if (!ageAuto) {
                OutlinedTextField(
                    value = ageThreshold, onValueChange = { ageThreshold = it },
                    label = { Text("AGE ms") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = if (capAuto) ExecutionConfig.cap.toString() else cap,
                onValueChange = { cap = it },
                readOnly = capAuto,
                label = { Text("CAP (0=inf)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (depthAuto) ExecutionConfig.scanningDepth.toString() else depth,
                onValueChange = { depth = it },
                readOnly = depthAuto,
                label = { Text("SCANNING.DEPTH") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (strategyAuto) ExecutionConfig.strategy else strategy,
                onValueChange = { strategy = it },
                readOnly = strategyAuto,
                label = { Text("STRATEGY linear/parallel") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    // Preserve Kelly settings: start from the currently stored config
                    val pending = viewModel.config.value.copy(
                        base = base,
                        min = min.toDoubleOrNull() ?: 0.0,
                        max = max.toDoubleOrNull() ?: 0.0,
                        step = step.toDoubleOrNull() ?: 0.0,
                        fee = fee.toDoubleOrNull() ?: 0.0,
                        profitThreshold = profitThreshold.toDoubleOrNull() ?: 0.0,
                        ageThresholdMs = ageThreshold.toIntOrNull() ?: 5000,
                        strategy = strategy.lowercase().trim(),
                        cap = cap.toIntOrNull() ?: 1,
                        depth = depth.toIntOrNull() ?: 50,
                        investmentAuto = investmentAuto,
                        feeAuto = feeAuto,
                        profitThresholdAuto = profitAuto,
                        ageThresholdAuto = ageAuto,
                        capAuto = capAuto,
                        depthAuto = depthAuto,
                        strategyAuto = strategyAuto,
                        preFlightCheckEnabled = preFlight,
                        preFlightMarginPercent = preFlightMargin.toDoubleOrNull() ?: 0.0,
                        executionDeadlineMs = deadline.toIntOrNull() ?: 0,
                        executionDeadlineAuto = deadlineAuto,
                        dedupeMirroredTriangles = dedupeMirrors,
                        stagedExecutionEnabled = staged,
                        microstructureGatesEnabled = microGates,
                        armTtlMs = armTtl.toIntOrNull() ?: 2000,
                        armTtlAuto = armTtlAuto,
                        armingMarginPercent = fireMargin.toDoubleOrNull() ?: 0.10,
                        armingMarginAuto = fireMarginAuto,
                        abandonMarginPercent = dropMargin.toDoubleOrNull() ?: 0.05,
                        abandonMarginAuto = dropMarginAuto,
                        spreadTightMaxBps = spreadLimit.toDoubleOrNull() ?: 8.0,
                        spreadAuto = spreadAuto,
                        imbalanceMaxAbs = imbalanceLimit.toDoubleOrNull() ?: 0.35,
                        imbalanceAuto = imbalanceAuto,
                        cadenceMaxInterArrivalMs = cadenceLimit.toIntOrNull() ?: 750,
                        cadenceAuto = cadenceAuto,
                        maxArmedOpportunities = armSlots.toIntOrNull() ?: 8,
                        maxArmedAuto = armSlotsAuto,
                        paperLatencyMs = paperLatency.toIntOrNull() ?: 0,
                        paperLatencyAuto = paperLatencyAuto,
                        paperSlippagePercent = paperSlippage.toDoubleOrNull() ?: 0.0,
                        paperSlippageAuto = paperSlippageAuto
                    )
                    viewModel.saveConfig(pending)
                    viewModel.setStagedExecution(pending)
                    viewModel.setExecutionGuards(
                        preFlight,
                        preFlightMargin.toDoubleOrNull() ?: 0.0,
                        if (deadlineAuto) ExecutionConfig.executionDeadlineMs else (deadline.toIntOrNull() ?: 0),
                        deadlineAuto
                    )
                    viewModel.setAgeGateMode(ageAuto)
                    if (anyAuto) viewModel.refreshAutoTune()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BtaYellow, contentColor = Color.Black),
                modifier = Modifier.weight(1f)
            ) { Text("SAVE CONFIG") }
            OutlinedButton(onClick = { navController.navigate("paper_live") }, modifier = Modifier.weight(1f)) {
                Text("PAPER / LIVE")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.refreshAutoTune() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("RE-DERIVE AUTO SETTINGS NOW", fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("Binance API Credentials")
        CredentialsPanel(viewModel)

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = BtaYellow, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Formats a derived double the way the engine stores it, without float noise. */
private fun fmt(v: Double): String {
    val s = v.toString()
    return if (s.contains("E")) "%.10f".format(v) else s
}

@Composable
private fun AutoBadge(
    label: String,
    auto: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = { onChange(!auto) },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (auto) BtaYellow.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (auto) BtaYellow else TextSecondary
        ),
        border = BorderStroke(1.dp, if (auto) BtaYellow else CardBorder)
    ) {
        Text(if (auto) "$label AUTO" else "$label MANUAL", fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun CredentialsPanel(viewModel: ArbViewModel) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    val keyStatus by viewModel.keyStatus.collectAsState()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Credentials are stored in Android Keystore-backed encrypted storage and are never logged.",
                color = TextSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            // Phase 3: one key, two usage tiers, enforced by the app. Paper
            // mode signs reads only (fees, permissions); live mode additionally
            // signs orders after the confirmation phrase on the Paper/Live tab.
            if (keyStatus.stored) {
                KeyValueRow("Stored key", keyStatus.maskedKey ?: "stored", valueColor = ProfitGreen)
                KeyValueRow(
                    "Read access",
                    when (keyStatus.canRead) {
                        true -> "granted"
                        false -> "DENIED by Binance"
                        null -> "not checked"
                    },
                    valueColor = when (keyStatus.canRead) {
                        true -> ProfitGreen
                        false -> LossRed
                        null -> TextSecondary
                    }
                )
                KeyValueRow(
                    "Spot trading",
                    when (keyStatus.canSpotTrade) {
                        true -> "enabled (live orders possible)"
                        false -> "DISABLED - paper fees only"
                        null -> "not checked"
                    },
                    valueColor = when (keyStatus.canSpotTrade) {
                        true -> ProfitGreen
                        false -> LossRed
                        null -> TextSecondary
                    }
                )
                KeyValueRow(
                    "Account taker fee",
                    keyStatus.feePercent?.let { "%.4f%%".format(it) } ?: "fallback",
                    valueColor = if (keyStatus.feePercent != null) ProfitGreen else TextSecondary
                )
            } else {
                Text(
                    "No key stored - paper mode runs on the default fee fallback. " +
                        "A stored key is used read-only while paper trading.",
                    color = TextSecondary, fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.verifyKey() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("VERIFY KEY & FEES") }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API Key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiSecret, onValueChange = { apiSecret = it },
                label = { Text("API Secret") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.saveCredentials(apiKey, apiSecret) { }
                        apiKey = ""
                        apiSecret = ""
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("SAVE SECURELY") }
                OutlinedButton(
                    onClick = { viewModel.clearCredentials() },
                    modifier = Modifier.weight(1f)
                ) { Text("REMOVE") }
            }
        }
    }
}

@Composable
fun PaperLiveScreen(viewModel: ArbViewModel, navController: NavController) {
    val mode by viewModel.mode.collectAsState()
    val message by viewModel.actionMessage.collectAsState()
    var confirmation by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        ModeBanner(mode)
        Spacer(Modifier.height(12.dp))
        Text("Paper / Live Trading", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        KellySettingsPanel(viewModel)

        Spacer(Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, ProfitGreen)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("PAPER TRADING", fontWeight = FontWeight.Bold, color = ProfitGreen, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Simulated execution against live Binance order books. No real orders are placed. " +
                        "Balances, fees, and fills are simulated. This is the default mode.",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, LossRed)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("LIVE TRADING", fontWeight = FontWeight.Bold, color = LossRed, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Places REAL market orders with REAL funds on Binance. Triangle arbitrage can lose " +
                        "money quickly through slippage, latency, and partial fills. Requires API credentials " +
                        "with spot trading enabled.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Reality check: dislocations on a 100ms public feed are usually consumed " +
                        "by faster participants first. Your paper results reflect the simulator's " +
                        "assumptions - verify your measured edge clears fees on YOUR latency " +
                        "before enabling this.",
                    color = LossRed, fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Type \"${ArbViewModel.CONFIRMATION_PHRASE}\" to unlock:",
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = confirmation, onValueChange = { confirmation = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                val unlocked = confirmation == ArbViewModel.CONFIRMATION_PHRASE
                // Phase 3: a key Binance reports as unable to spot-trade cannot
                // arm live trading - the button stays dead and says why.
                val keyStatus by viewModel.keyStatus.collectAsState()
                val keyCannotTrade = keyStatus.canSpotTrade == false
                Button(
                    onClick = { viewModel.enableLiveTrading(confirmation) },
                    enabled = unlocked && mode == TradingMode.PAPER && !keyCannotTrade,
                    colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (keyCannotTrade) "BLOCKED: KEY CANNOT SPOT-TRADE"
                        else "ENABLE LIVE TRADING"
                    )
                }
                if (keyCannotTrade) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The stored Binance key has spot trading disabled in its API restrictions. " +
                            "Paper mode continues with account fees; create a key with \"Enable Spot Trading\" to go live.",
                        color = LossRed, fontSize = 11.sp
                    )
                }
            }
        }

        if (mode == TradingMode.LIVE) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { viewModel.disableLiveTrading() },
                colors = ButtonDefaults.buttonColors(containerColor = BtaYellow, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("RETURN TO PAPER TRADING") }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = BtaYellow, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}


@Composable
fun KellySettingsPanel(viewModel: ArbViewModel) {
    val stats by viewModel.kellyStats.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val message by viewModel.actionMessage.collectAsState()

    var enabled by remember { mutableStateOf(false) }
    var budget by remember { mutableStateOf("100") }
    var prob by remember { mutableStateOf("80") }
    var invPct by remember { mutableStateOf("10") }
    var minTrade by remember { mutableStateOf("5") }
    var initialized by remember { mutableStateOf(false) }

    // Load persisted config once
    LaunchedEffect(Unit) {
        val cfg = viewModel.configurationStore.current()
        enabled = cfg.kellyEnabled
        budget = cfg.kellyBudgetUsdt.toString()
        prob = (cfg.kellyRequiredProbability * 100).toInt().toString()
        invPct = (cfg.kellyInvestmentFraction * 100).toInt().toString()
        minTrade = cfg.kellyMinTradeUsdt.toString()
        initialized = true
    }

    SectionTitle("Paper Auto-Trading (Kelly Criterion)")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) ProfitGreen else CardBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Executes paper trades automatically on opportunities passing the Kelly criterion: " +
                    "P(net profit) >= threshold and positive Kelly edge. Budget is USDT; triangles in other " +
                    "bases are funded USDT -> base and settled back to USDT.",
                color = TextSecondary, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = budget, onValueChange = { budget = it },
                    label = { Text("Budget USDT") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.2f)
                )
                OutlinedTextField(
                    value = prob, onValueChange = { prob = it },
                    label = { Text("P(win) %") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = invPct, onValueChange = { invPct = it },
                    label = { Text("Invest %Kelly") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minTrade, onValueChange = { minTrade = it },
                    label = { Text("Min trade USDT") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        enabled = mode == TradingMode.PAPER
                    )
                    Text("AUTO", fontSize = 10.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.updateKellySettings(
                        enabled = enabled,
                        budgetUsdt = budget.toDoubleOrNull() ?: 100.0,
                        requiredProbability = (prob.toDoubleOrNull() ?: 80.0) / 100.0,
                        investmentFraction = (invPct.toDoubleOrNull() ?: 10.0) / 100.0,
                        minTradeUsdt = minTrade.toDoubleOrNull() ?: 5.0
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BtaYellow, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("APPLY PAPER AUTO-TRADING") }

            stats?.let { s ->
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Equity", "%.2f USDT (start %.2f)".format(s.equityUsdt, s.startingUsdt))
                KeyValueRow("Paper trades", "${s.trades} (win rate ${(s.winRate * 100).toInt()}%)")
                KeyValueRow("Total P&L", "%+.4f USDT".format(s.totalPnlUsdt),
                    valueColor = if (s.totalPnlUsdt >= 0) ProfitGreen else LossRed)
                KeyValueRow("Realized volatility (sigma)", "%.4f%%".format(s.sigmaPercent))
                s.lastPWin?.let { KeyValueRow("Last P(win)", "%.1f%%".format(it * 100)) }
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = BtaYellow, fontSize = 12.sp)
            }
        }
    }
}
