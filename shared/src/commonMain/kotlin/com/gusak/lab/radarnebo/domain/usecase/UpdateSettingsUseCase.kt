package com.gusak.lab.radarnebo.domain.usecase

import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.SettingsRepository

/** Saves a settings change. Switching server or access key drops everything fetched from the old one. */
class UpdateSettingsUseCase(
    private val settings: SettingsRepository,
    private val alerts: AlertsRepository,
) {
    /** Returns true when the server changed, so polling should restart. */
    operator fun invoke(transform: (AppSettings) -> AppSettings): Boolean {
        val old = settings.settings.value
        val new = settings.update(transform)
        val sourceChanged = new.endpoint != old.endpoint
        if (sourceChanged) alerts.reset()
        return sourceChanged
    }
}
