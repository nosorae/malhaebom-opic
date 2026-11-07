package io.github.nosorae.aiopic.engine.coach

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

    /** 사용자가 끼어들어 코치가 말을 멈췄다. */
    data object Interrupted : TransportEvent

    /** 한 턴에 쓴 토큰. 엔진이 단계별로 더하므로 제공사가 누적값을 주면 어댑터가 차이로 바꿔 넘긴다. */
    data class Usage(val usage: TokenUsage) : TransportEvent

    /** 새 이어 붙이기 핸들. */
    data class ResumeHandle(val handle: String) : TransportEvent

    /** 네트워크 끊김, 429 등. [reason] 은 화면에 보일 이유다. */
    data class Disconnected(val reason: String) : TransportEvent
}

/** 세션 메모와 받아쓰기로 스크립트를 만든다. 구현과 실패를 돌려주는 모양은 #267 이 정한다. */
interface ScriptWriter {
    suspend fun write(session: CoachState): CoachScript
}
