package com.hakim3691.bta

import android.app.Application
import com.hakim3691.bta.config.ConfigurationStore
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
