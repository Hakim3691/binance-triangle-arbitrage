package com.hakim3691.bta

import android.app.Application
import com.hakim3691.bta.config.ConfigurationStore
import com.hakim3691.bta.log.LogRepository
import com.hakim3691.bta.scanner.ScannerController
import com.hakim3691.bta.security.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArbApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var credentialStore: CredentialStore
        private set
    lateinit var configurationStore: ConfigurationStore
        private set
    lateinit var scannerController: ScannerController
        private set

    override fun onCreate() {
        super.onCreate()
        // Before anything else logs, so the very first line of this process is
        // already on disk. The scanner is designed to be killed and resumed by
        // the OS; without a file sink each resume started with an empty log and
        // the evidence of the run that was interrupted was simply gone.
        LogRepository.attach(java.io.File(filesDir, "logs/bta.log"))
        credentialStore = CredentialStore.getInstance(this)
        configurationStore = ConfigurationStore(this)

        // Apply persisted public configuration before any engine use (CONFIG.* equivalent)
        applicationScope.launch {
            configurationStore.applyToEngine(configurationStore.current())
        }

        val creds: suspend () -> Pair<String, String> = {
            credentialStore.getApiKey() to credentialStore.getApiSecret()
        }
        scannerController = ScannerController(creds, appContext = applicationContext)
    }
}
