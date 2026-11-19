package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject

/** 소리 없이를 설정에 저장한다(F18.5). */
@Inject
class SetSilentUseCase(private val settings: ReviewSettings) {
    suspend operator fun invoke(on: Boolean) = settings.setSilent(on)
}
