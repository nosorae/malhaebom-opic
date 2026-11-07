package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject

@Inject
class CheckLiveConsentUseCase(private val consent: LiveConsent) {
    suspend operator fun invoke(): Boolean = consent.isLiveGiven()
}
