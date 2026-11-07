package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 코치와의 실시간 음성 연결. 제공사 구현은 엔진 층의 제공사 모듈에 둔다(#265). */
interface LiveCoachTransport {
    /**
     * 모으는 동안 연결을 유지하고 모으기를 멈추면 닫는다. [resumeHandle] 이 있으면 앞 대화를 이어 붙인다.
     * 연결이 끝나면 마지막에 [TransportEvent.Disconnected] 를 내고 흐름을 끝낸다.
     */
    fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<TransportEvent>

    /** 16kHz 모노 16비트 PCM. */
    suspend fun sendAudio(pcm: ByteArray)

    /** 말이 끝나 침묵이 시작됐다. 서버 음성 감지가 말 끝을 알게 한다. */
    suspend fun sendAudioEnd()

    suspend fun sendToolResponse(id: String, name: String, result: JsonElement)
}

sealed interface TransportEvent {
    /** 코치 음성 조각. 24kHz 모노 16비트 PCM. */
    class Audio(val pcm: ByteArray) : TransportEvent

    data class UserSaid(val text: String) : TransportEvent

    data class CoachSaid(val text: String) : TransportEvent

    data class ToolCall(val id: String, val name: String, val args: JsonObject) : TransportEvent

    /** 코치의 한 턴(음성, 받아쓰기, 사용량)이 끝났다. */
    data object TurnComplete : TransportEvent

    /** 사용자가 끼어들어 코치가 말을 멈췄다. */
    data object Interrupted : TransportEvent

    /** 한 턴에 쓴 토큰. 엔진이 단계별로 더하므로 제공사가 누적값을 주면 어댑터가 차이로 바꿔 넘긴다. */
    data class Usage(val usage: TokenUsage) : TransportEvent

    /** 새 이어 붙이기 핸들. */
    data class ResumeHandle(val handle: String) : TransportEvent

    /** 네트워크 끊김, 429 등. [reason] 은 화면이 문구를 고르는 값이다. 아래 상수가 아니면 네트워크 끊김으로 보인다. */
    data class Disconnected(val reason: String) : TransportEvent {
        companion object {
            /** 키의 요청 한도에 닿았다(429). */
            const val RATE_LIMITED = "rate_limited"

            /** 넣어 둔 키가 없다. */
            const val NO_KEY = "no_key"

            /** 서버가 키를 받지 않았다. */
            const val INVALID_KEY = "invalid_key"

            /** 서버가 Live 모델을 찾지 못했다. */
            const val MODEL_UNAVAILABLE = "model_unavailable"

            const val NETWORK = "network"

            /** 마이크를 열지 못했다(권한, 다른 앱이 쓰는 중). 엔진이 낸다. */
            const val MIC = "mic"
        }
    }
}

/**
 * 글 모델로 스크립트를 만들고 다듬는다(F14.1, F14.4). 제공사 구현은 엔진 층의 제공사 모듈에 둔다(#267).
 * 실패는 예외 대신 [ScriptResult.Failed] 로 돌려줘 화면이 이유와 다시 시도를 보인다.
 */
interface ScriptWriter {
    /** 받아쓰기 전체, 메모, 표현 카드, 목표 등급을 한 번에 보낸다. [question] 은 질문 글이다. */
    suspend fun write(session: CoachState, question: String): ScriptResult<CoachScript>

    /** 사용자가 고친 판을 [targetGrade] 길이에 맞춰 다시 쓴다. 고친 문장은 되도록 둔다. */
    suspend fun polish(edited: ScriptParts, expressions: List<Expression>, question: String, targetGrade: String): ScriptResult<ScriptParts>
}

sealed interface ScriptResult<out T> {
    data class Success<T>(val value: T) : ScriptResult<T>

    /** 키 없음, 연결 없음, 한도, 빈 칸 같은 못 쓰는 응답. 답변 분석과 같은 이유 값을 쓴다. */
    data class Failed(val error: AnalysisError) : ScriptResult<Nothing>
}
