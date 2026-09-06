package dev.andrii.headroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.data.UsageRepository
import dev.andrii.headroom.data.UsageState
import dev.andrii.headroom.domain.Credential
import dev.andrii.headroom.domain.TriggerSettings
import dev.andrii.headroom.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UsageViewModel(
    private val repository: UsageRepository,
    private val credentialStore: CredentialStore,
    settingsStore: SettingsStore,
) : ViewModel() {

    val state: StateFlow<UsageState> = repository.state

    /**
     * The screen needs the threshold, not just the notifier: the notch is drawn
     * at the user's own warning line, so the bar and the notification agree on
     * one number.
     */
    val settings: StateFlow<TriggerSettings> = settingsStore.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TriggerSettings(),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun link(credential: Credential) {
        viewModelScope.launch {
            credentialStore.save(credential)
            repository.refresh()
        }
    }

    fun unlink() {
        viewModelScope.launch {
            credentialStore.clear()
            repository.refresh()
        }
    }
}
