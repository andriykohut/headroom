package dev.andrii.headroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.domain.TriggerSettings
import dev.andrii.headroom.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keeps the warning threshold usable: below 50 it would fire constantly, and
 * at 100 it would only fire once the wall had already been hit.
 */
fun clampThreshold(value: Double): Double =
    Math.round(value).toDouble().coerceIn(50.0, 99.0)

class SettingsViewModel(
    private val store: SettingsStore,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    val settings: StateFlow<TriggerSettings> = store.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TriggerSettings(),
    )

    fun update(transform: (TriggerSettings) -> TriggerSettings) {
        viewModelScope.launch {
            val next = transform(store.current())
            store.update(next.copy(thresholdPercent = clampThreshold(next.thresholdPercent)))
        }
    }

    /**
     * Forget the linked account.
     *
     * The store has had this since the credential layer was built, but nothing
     * could reach it — so a user who linked the wrong account had no way back
     * short of clearing app data.
     */
    fun unlink() {
        viewModelScope.launch { credentialStore.clear() }
    }
}
