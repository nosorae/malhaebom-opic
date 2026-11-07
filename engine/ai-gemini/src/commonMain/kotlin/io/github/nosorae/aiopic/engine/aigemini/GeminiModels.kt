package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.Stability
import io.github.nosorae.aiopic.engine.answer.port.ModelCatalog

/**
 * 앱이 들고 있는 Gemini 분석 모델 목록(F5.8, ADR 0019). 녹음을 넣고 구조화 출력으로 실제 응답을 받아 본 모델만 둔다.
 * 기본은 미리 확인(`s1`)에서 멈춤과 말 속도를 재 본 모델이다.
 */
object GeminiModels : ModelCatalog {
    /** 키 보관과 답변 결과의 제공사 이름. */
    const val PROVIDER = "gemini"

    override val defaultModel = ModelInfo(PROVIDER, "gemini-3.8-flash", "Gemini 3.8 Flash", hearsAudio = true, stability = Stability.Stable)

    private val flashLite = ModelInfo(PROVIDER, "gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", hearsAudio = true, stability = Stability.Stable)

    // ponytail: 두 개만 둔다. 품질 확인 도구(n-eval)로 출력 검사 통과를 본 모델이 생기면 여기에 더한다.
    override val models = listOf(defaultModel, flashLite)

    /**
     * 출제(F6.1)는 설정에서 고른 분석 모델과 상관없이 이 모델로 보낸다. 같은 출제 프롬프트(8문항)를 두 번씩 재 보니
     * 기본 모델은 13.6초와 16.7초, Flash Lite 는 2.8초와 2.9초였고 두 모델 모두 유형 순서, 서베이 주제, 재조정 반영을 지켰다(#150).
     * 출제는 녹음을 듣지 않아서 분석 모델 고르기와 묶을 이유가 없다.
     */
    val questionModel: ModelInfo = flashLite

    /**
     * 라이브 코치(F11)의 실시간 음성 모델. 분석 모델 설정과 상관없이 이 모델로 연다. 코치는 음성으로 듣고 음성으로 답해서 분석 모델 목록과 따로 둔다.
     */
    // ponytail: 하나만 둔다. 설정에서 고르게 하려면 분석 모델 목록처럼 목록과 고르기를 더한다.
    val liveModel: ModelInfo = ModelInfo(PROVIDER, "gemini-3.8-live", "Gemini 3.8 Live", hearsAudio = true, stability = Stability.Stable)
}
