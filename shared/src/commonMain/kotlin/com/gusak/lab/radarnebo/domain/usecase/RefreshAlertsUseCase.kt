package com.gusak.lab.radarnebo.domain.usecase

import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.AlertsUpdate
import com.gusak.lab.radarnebo.data.SettingsRepository

/** Polls the configured source (alerts.in.ua, a proxy or the demo simulation) once. */
class RefreshAlertsUseCase(
    private val alerts: AlertsRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(): AlertsUpdate = alerts.refresh(settings.settings.value)
}
