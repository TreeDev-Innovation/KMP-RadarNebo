package com.gusak.lab.radarnebo.domain.usecase

import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.SettingsRepository

/** What a settings change means for the data already fetched. */
data class SettingsUpdate(
    /** Another server or access key: everything fetched from the old one was dropped. */
    val sourceChanged: Boolean,
)

/** Saves a settings change. Switching server or access key drops everything fetched from the old one. */
class UpdateSettingsUseCase(
    private val settings: SettingsRepository,
    private val alerts: AlertsRepository,
) {
    operator fun invoke(transform: (AppSettings) -> AppSettings): SettingsUpdate {
        val old = settings.settings.value
        val new = settings.update(transform)
        val sourceChanged = new.endpoint != old.endpoint
        if (sourceChanged) alerts.reset()
        return SettingsUpdate(sourceChanged)
    }
}
