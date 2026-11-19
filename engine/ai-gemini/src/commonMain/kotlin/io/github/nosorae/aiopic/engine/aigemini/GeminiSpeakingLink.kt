package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.coach.TokenUsage
import io.github.nosorae.aiopic.engine.speaking.SpeakingLink
import io.github.nosorae.aiopic.libs.livegemini.LiveConfig
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 말하기 연습 연결을 Gemini Live 로 연다(F16). [apiKey] 는 사용자가 넣은 키이고 없으면 열지 않고 흐름을 끝낸다.
 * 연습 하나가 [session] 하나를 끝까지 쓴다. 사용량은 코치와 같은 [TurnUsage] 로 턴별 값으로 맞춰 넘긴다(F16.24).
 */
class GeminiSpeakingLink(
    private val apiKey: suspend () -> String?,
    private val voice: String,
    private val session: LiveSession = LiveSession(),
) : SpeakingLink {
    override fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<LiveEvent> = flow {
        val key = apiKey() ?: return@flow
        var usage = TurnUsage()
        session.connect(key, LiveConfig(GeminiModels.liveModel.id, instruction, voice, tools), resumeHandle).collect { event ->
            when (event) {
                // GoAway 로 소켓을 바꿔 이어 붙이면 서버의 사용량 셈도 새로 시작할 수 있어 새로 센다.
                LiveEvent.Connected, LiveEvent.Resumed -> usage = TurnUsage()

                is LiveEvent.Usage -> {
                    val turn = usage.next(TokenUsage(event.promptByModality.modalities(), event.responseByModality.modalities()))
                    val input = turn.input.mapKeys { it.key.name.uppercase() }
                    val output = turn.output.mapKeys { it.key.name.uppercase() }
                    return@collect emit(LiveEvent.Usage(input.values.sum(), output.values.sum(), input, output))
                }

                else -> Unit
            }
            emit(event)
        }
    }

    override suspend fun sendAudio(pcm: ByteArray) = session.sendAudio(pcm)

    override suspend fun sendAudioEnd() = session.sendAudioEnd()

    override suspend fun sendText(text: String) = session.sendText(text)

    override suspend fun sendToolResponse(id: String, name: String, result: JsonElement) = session.sendToolResponse(id, name, result)
}
