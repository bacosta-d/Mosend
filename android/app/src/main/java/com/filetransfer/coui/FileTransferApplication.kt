package com.filetransfer.coui

import android.app.Application
import com.filetransfer.coui.network.DiscoveryService
import com.filetransfer.coui.settings.SettingsStore
import com.filetransfer.coui.transfer.TransferEngine
import com.filetransfer.coui.transfer.TransferStore
import timber.log.Timber

class FileTransferApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        // 初始化顺序：App → Settings → TransferStore → MessageStore → Engine
        SettingsStore.init(this)
        TransferStore.init(this)
        com.filetransfer.coui.transfer.MessageStore.init(this)
        TransferEngine.init(this)

        DiscoveryService.get(this).start()
        TransferEngine.get().start()
    }

    override fun onTerminate() {
        super.onTerminate()
        runCatching { DiscoveryService.get(this).stop() }
        runCatching { TransferEngine.get().stop() }
    }

    companion object {
        @JvmStatic
        lateinit var instance: FileTransferApplication
            private set
    }
}
