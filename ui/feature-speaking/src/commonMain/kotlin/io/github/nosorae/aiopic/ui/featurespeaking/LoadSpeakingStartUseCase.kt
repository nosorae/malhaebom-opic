package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.ui.featurelive.LiveConsent

/** 시작 시트를 열 때 읽는 값(F16.2, F16.3). */
data class SpeakingStartDefaults(val minutes: Int, val hasKey: Boolean, val consented: Boolean)

@Inject
class LoadSpeakingStartUseCase(private val settings: SpeakingSettings, private val consent: LiveConsent) {
    suspend operator fun invoke() = SpeakingStartDefaults(settings.defaultMinutes(), settings.hasKey(), consent.isLiveGiven())
}
