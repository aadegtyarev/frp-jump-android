package ru.adsrv.frpjump

import android.app.Application
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.store.CredentialStore

class FrpJumpApp : Application() {
    lateinit var store: CredentialStore
        private set
    val api: FrpJumpApi by lazy { FrpJumpApi() }

    override fun onCreate() {
        super.onCreate()
        store = CredentialStore(filesDir)
    }
}
