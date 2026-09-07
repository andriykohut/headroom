package dev.andrii.headroom.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.credential.ImportedCredentialStore
import dev.andrii.headroom.data.DataStoreSnapshotCache
import dev.andrii.headroom.data.SnapshotCache
import dev.andrii.headroom.data.UsageApi
import dev.andrii.headroom.data.UsageRepository
import dev.andrii.headroom.domain.TriggerEvaluator
import dev.andrii.headroom.notify.AndroidNotifier
import dev.andrii.headroom.notify.DataStoreNotificationLog
import dev.andrii.headroom.notify.NotificationCoordinator
import dev.andrii.headroom.notify.NotificationLog
import dev.andrii.headroom.notify.Notifier
import dev.andrii.headroom.schedule.AlarmScheduler
import dev.andrii.headroom.schedule.AndroidAlarmScheduler
import dev.andrii.headroom.settings.DataStoreSettingsStore
import dev.andrii.headroom.settings.SettingsStore
import dev.andrii.headroom.store.KeystoreSecureStore
import dev.andrii.headroom.store.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import dev.andrii.headroom.ui.SettingsViewModel
import dev.andrii.headroom.ui.UsageViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("headroom")

val appModule = module {
    single<HttpClient> { HttpClient(OkHttp) }
    single<SecureStore> { KeystoreSecureStore(get()) }
    single<CredentialStore> { ImportedCredentialStore(get()) }
    single { UsageApi(get(), get()) }
    single<SnapshotCache> { DataStoreSnapshotCache(get<Context>().dataStore) }
    single { UsageRepository(fetch = { get<UsageApi>().fetch() }, cache = get()) }
    single<NotificationLog> { DataStoreNotificationLog(get<Context>().dataStore) }
    single<SettingsStore> { DataStoreSettingsStore(get<Context>().dataStore) }
    single<Notifier> { AndroidNotifier(get()) }
    single<AlarmScheduler> { AndroidAlarmScheduler(get()) }
    single { TriggerEvaluator() }
    viewModel { UsageViewModel(get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
    single {
        NotificationCoordinator(
            fetch = { get<UsageApi>().fetch() },
            evaluator = get(),
            log = get(),
            notifier = get(),
            alarmScheduler = get(),
            settings = { get<SettingsStore>().current() },
            cache = get(),
        )
    }
}
