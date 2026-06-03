package com.aipay.listener

import android.app.Application
import androidx.work.Configuration

class AiPayApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
