package io.github.nosorae.aiopic.libs.livegemini

import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/** [LiveSession.connect] 가 내는 일. */
public sealed interface LiveEvent {
    /** setupComplete 를 받았다. 이제 보낸 오디오와 글이 서버로 간다. */
    public data object Connected : LiveEvent

    /** 받아 둔 핸들로 새 연결을 열어 앞의 대화를 이어 붙였다. */
    public data object Resumed : LiveEvent

    /** 핸들을 받은 지 2시간이 지나 이어 붙이지 못하고 새 대화로 연다. 앞의 맥락은 모델이 모른다. */
    public data object ResumeExpired : LiveEvent

    /** 모델 음성 조각. 24kHz 모노 16비트 리틀엔디언 PCM 이다. */
    public class AudioChunk(public val pcm: ByteArray) : LiveEvent

    public data class InputTranscript(val text: String) : LiveEvent

    public data class OutputTranscript(val text: String) : LiveEvent

    /** 답은 [LiveSession.sendToolResponse] 에 같은 [id] 로 보낸다. */
    public data class ToolCall(val id: String, val name: String, val args: JsonObject) : LiveEvent

    /** 사용자가 끼어들어 아직 답하지 않은 도구 호출이 취소됐다. */
    public data class ToolCallCancelled(val ids: List<String>) : LiveEvent

    public data object TurnComplete : LiveEvent

    /** 사용자가 말해 모델이 말을 멈췄다. 재생 중인 음성을 버린다. */
    public data object Interrupted : LiveEvent

    /** 서버가 [timeLeft] 뒤에 연결을 끊는다. 핸들이 있으면 세션이 알아서 새 연결을 연다. */
    public data class GoAway(val timeLeft: Duration) : LiveEvent

    /** 모달리티 맵의 키는 서버가 준 이름 그대로다(`AUDIO`, `TEXT`). */
    public data class Usage(
        val promptTokens: Int,
        val responseTokens: Int,
        val promptByModality: Map<String, Int>,
        val responseByModality: Map<String, Int>,
    ) : LiveEvent

    /** 연결이 끝났다. 흐름도 여기서 끝난다. 다시 [LiveSession.connect] 하면 2시간 안이면 이어 붙인다. */
    public data class Disconnected(val code: Int?, val reason: String) : LiveEvent

    /** 연결을 열지 못했다. [status] 는 핸드셰이크 HTTP 상태이고(429 등), 알 수 없으면 null 이다. */
    public data class Failed(val status: Int?, val message: String) : LiveEvent
}
