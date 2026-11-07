package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.coach.LiveCoachTransport
import io.github.nosorae.aiopic.engine.coach.Modality
import io.github.nosorae.aiopic.engine.coach.TokenUsage
import io.github.nosorae.aiopic.engine.coach.TransportEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveConfig
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/**
 * 코치 연결을 Gemini Live 로 연다(#265). [apiKey] 는 사용자가 넣은 키이고 없으면 열지 않는다.
 * 연결마다 [newSession] 으로 새 [LiveSession] 을 만든다. 세션은 앞 연결에서 받은 핸들을 안에 들고 있어서,
 * 같은 세션으로 다시 열면 핸들을 넘기지 않아도 앞 단계 대화에 이어 붙는다(ADR 0036 은 단계마다 새 대화다).
 */
class GeminiLiveTransport(
    private val apiKey: suspend () -> String?,
    private val voice: String,
    private val model: String = GeminiModels.liveModel.id,
    private val newSession: () -> LiveSession = { LiveSession() },
) : LiveCoachTransport {
    @Volatile private var session: LiveSession? = null

    override fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<TransportEvent> = flow {
        val key = apiKey() ?: return@flow emit(TransportEvent.Disconnected(TransportEvent.Disconnected.NO_KEY))
        val live = newSession().also { session = it }
        var usage = TurnUsage()
        // 핸들 나이는 엔진이 이미 따졌다(RESUME_LIMIT). 받은 시각을 넘기지 않으면 세션이 지금 받은 것으로 본다.
        live.connect(key, LiveConfig(model, instruction, voice, tools), resumeHandle).collect { event ->
            when (event) {
                // GoAway 로 소켓을 바꿔 이어 붙이면 서버의 사용량 셈도 새로 시작할 수 있어 새로 센다.
                LiveEvent.Connected, LiveEvent.Resumed -> usage = TurnUsage()

                is LiveEvent.AudioChunk -> emit(TransportEvent.Audio(event.pcm))

                is LiveEvent.InputTranscript -> emit(TransportEvent.UserSaid(event.text))

                is LiveEvent.OutputTranscript -> emit(TransportEvent.CoachSaid(event.text))

                is LiveEvent.ToolCall -> emit(TransportEvent.ToolCall(event.id, event.name, event.args))

                LiveEvent.Interrupted -> emit(TransportEvent.Interrupted)

                is LiveEvent.ResumeHandle -> emit(TransportEvent.ResumeHandle(event.handle))

                is LiveEvent.Usage -> emit(TransportEvent.Usage(usage.next(TokenUsage(event.promptByModality.modalities(), event.responseByModality.modalities()))))

                is LiveEvent.Disconnected -> emit(TransportEvent.Disconnected(TransportEvent.Disconnected.NETWORK))

                is LiveEvent.Failed -> emit(TransportEvent.Disconnected(if (event.status == 429) TransportEvent.Disconnected.RATE_LIMITED else TransportEvent.Disconnected.NETWORK))

                // 핸들 만료는 엔진이 먼저 걸러 새 대화로 연다. 도구 취소, 턴 끝, GoAway 는 엔진이 할 일이 없다.
                LiveEvent.ResumeExpired, is LiveEvent.ToolCallCancelled, LiveEvent.TurnComplete, is LiveEvent.GoAway -> Unit
            }
        }
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        session?.sendAudio(pcm)
    }

    override suspend fun sendAudioEnd() {
        session?.sendAudioEnd()
    }

    override suspend fun sendToolResponse(id: String, name: String, result: JsonElement) {
        session?.sendToolResponse(id, name, result)
    }
}

/** 서버의 모달리티 이름(`AUDIO`, `TEXT`)을 엔진 값으로 바꾼다. 코치가 쓰지 않는 모달리티(영상 등)는 버린다. */
private fun Map<String, Int>.modalities(): Map<Modality, Int> = entries.mapNotNull { (name, count) -> Modality.entries.firstOrNull { it.name.uppercase() == name }?.let { it to count } }.toMap()

/**
 * 연결 하나의 usageMetadata 를 엔진이 더하는 턴별 값으로 바꾼다. Gemini Live 가 누적값을 주는지 턴별 값을 주는지 확인하지 못해서 둘 다 견딘다.
 * 받은 값이 어느 칸도 줄지 않으면 누적으로 보고 앞 값과의 차이를 넘긴다. 한 칸이라도 줄면 그때부터 턴별 값으로 보고,
 * 그 전에 차이로 덜 넘긴 만큼을 그 턴에 더해 합을 맞춘다.
 */
// ponytail: 턴별 값이 연결 끝까지 한 번도 줄지 않으면 누적으로 잘못 보고 덜 센다. 실기기 세션 기록으로 어느 쪽인지 확인되면 그쪽 하나로 줄인다.
internal class TurnUsage {
    private var last: TokenUsage? = null
    private var received = TokenUsage()
    private var sent = TokenUsage()
    private var perTurn = false

    fun next(usage: TokenUsage): TokenUsage {
        val previous = last
        val out = when {
            previous == null || perTurn -> usage

            usage.notBelow(previous) -> usage - previous

            else -> {
                perTurn = true
                usage + (received - sent)
            }
        }
        last = usage
        received += usage
        sent += out
        return out
    }
}

private fun TokenUsage.notBelow(other: TokenUsage) = input.notBelow(other.input) && output.notBelow(other.output)

private fun Map<Modality, Int>.notBelow(other: Map<Modality, Int>) = other.all { (modality, count) -> (this[modality] ?: 0) >= count }

private operator fun TokenUsage.minus(other: TokenUsage) = TokenUsage(input.minus(other.input), output.minus(other.output))

private fun Map<Modality, Int>.minus(other: Map<Modality, Int>) = mapValues { (modality, count) -> count - (other[modality] ?: 0) }.filterValues { it != 0 }
