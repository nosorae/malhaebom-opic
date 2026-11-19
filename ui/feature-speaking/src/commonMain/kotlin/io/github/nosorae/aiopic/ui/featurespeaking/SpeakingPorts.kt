package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingSetup
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * 앱 범위에서 도는 말하기 연습(ADR 0038). 화면은 세션을 만들거나 닫지 않고 상태를 지켜보며 버튼을 명령으로 넘긴다.
 * 앱 층의 `RunningPractice` 가 구현해서 화면 버튼, 알림 버튼, 이어폰 버튼이 같은 명령으로 들어간다(F16.10).
 */
interface PracticeControl {
    /** 시작 전에는 null 이고, 끝나면 끝난 마지막 상태가 다음 연습을 시작할 때까지 남는다. */
    val state: Flow<SpeakingState?>

    /** 지금 말하는 쪽의 목소리 크기(0~1). 목소리 띠에 쓴다. */
    val level: Flow<Float>

    /** 화면이 보일 때만 부른다(F16.4). */
    suspend fun start(id: String, setup: SpeakingSetup)

    suspend fun send(action: PracticeAction)
}

/** 연습 중 화면의 버튼. 끊김으로 멈췄을 때 다시 시작은 다시 연결이다(F16.15). */
enum class PracticeAction { Pause, Resume, Finish }

/** 시작 시트가 읽는 값. 설정 화면의 말하기 연습 묶음(F19.1)이 생기기 전까지 앱 층이 처음 값을 넣는다. */
interface SpeakingSettings {
    /** 기본 연습 시간(분). 처음 20분이다(F16.2). */
    suspend fun defaultMinutes(): Int

    /** Gemini 키가 있는지(F16.3). */
    suspend fun hasKey(): Boolean

    /** 시트에서 고른 시간과 소리 환경에 설정의 주제, 말로 복습, 문법 짚기, 복습 양을 더한다. */
    suspend fun setup(planned: Duration, route: AudioRoute): SpeakingSetup
}

/** 정리에 실패한 연습의 정리를 다시 넣는다(F17.4, F17.6). 앱 층이 분석 작업 표에 넣는다. */
fun interface RetryCards {
    suspend fun retry(practiceId: String)
}
