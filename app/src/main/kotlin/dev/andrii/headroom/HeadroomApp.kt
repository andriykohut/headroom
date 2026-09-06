package dev.andrii.headroom

import android.app.Application
import dev.andrii.headroom.di.appModule
import dev.andrii.headroom.schedule.PollWorker
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HeadroomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@HeadroomApp)
            modules(appModule)
        }
        PollWorker.enqueuePeriodic(this)
    }
}
