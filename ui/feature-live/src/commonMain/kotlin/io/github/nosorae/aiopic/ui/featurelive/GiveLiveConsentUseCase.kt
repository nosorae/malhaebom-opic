package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject

@Inject
class GiveLiveConsentUseCase(private val consent: LiveConsent) {
    suspend operator fun invoke() = consent.giveLive()
}
