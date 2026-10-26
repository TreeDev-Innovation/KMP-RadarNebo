package com.gusak.lab.radarnebo.domain.usecase

import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.AlertsUpdate
import com.gusak.lab.radarnebo.data.SettingsRepository

/** Polls the RadarNebo server once. */
class RefreshAlertsUseCase(
    private val alerts: AlertsRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(): AlertsUpdate = settings.settings.value.let { alerts.refresh(it.endpoint, it.smartMapping) }
}
