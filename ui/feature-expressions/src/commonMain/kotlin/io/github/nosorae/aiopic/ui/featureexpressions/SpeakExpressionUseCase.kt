package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject

/** 더 나은 표현을 기기 음성으로 읽는다(F18.3, F18.4). 다 읽을 때까지 돌아오지 않는다. */
@Inject
class SpeakExpressionUseCase(private val voice: ExpressionVoice) {
    suspend operator fun invoke(text: String) = voice.speak(text)
}
