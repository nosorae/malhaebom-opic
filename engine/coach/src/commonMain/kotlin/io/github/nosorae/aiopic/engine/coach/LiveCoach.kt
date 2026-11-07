package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * 코치 세션 하나를 돌린다. 일어난 일을 [reduce] 로 상태에 반영하고 저장한 뒤, 상태에 맞게 연결을 열고 닫는다.
 * 대화 단계(경험, 표현, 피드백)마다 메모를 담은 지시로 새로 연결하고, 리허설과 일시정지와 끝에서는 연결을 닫는다.
 * 대화 시간은 연결이 열린 동안만 [clock] 으로 잰다.
 */
class LiveCoach(
    private val profile: CoachProfile,
    private val transport: LiveCoachTransport,
    private val audio: DuplexAudio,
    private val store: CoachStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CoachState?>(null)

    /** 시작하거나 열기 전에는 null 이다. */
    val state: StateFlow<CoachState?> = _state.asStateFlow()

    private val lock = Mutex()
    private var connection: Job? = null
    private var connectedPhase: CoachPhase? = null

    suspend fun start(id: String, questionId: String, targetGrade: String) = lock.withLock { set(CoachState(id, questionId, targetGrade, clock.now())) }

    /** 저장된 세션을 되살려 이어 간다. 진행 중이던 세션이면 바로 다시 연결한다. 없으면 false. */
    suspend fun open(id: String): Boolean = lock.withLock {
        val saved = store.load(id) ?: return@withLock false
        set(saved)
        true
    }

    suspend fun nextPhase() = dispatch(CoachEvent.NextPhase)

    /** "잠깐 생각할게요" 나 앱이 뒤로 갈 때. 연결을 닫고 대화 시간을 멈춘다(F11.6, F11.8). */
    suspend fun pause() = dispatch(CoachEvent.Paused)

    /** 일시정지나 끊김에서 다시 연결한다. 핸들이 살아 있으면 이어 붙이고 아니면 메모로 새로 연다. */
    suspend fun resume() = dispatch(CoachEvent.Resumed)

    /** 리허설 녹음의 받아쓰기를 넣는다(F13). 피드백 단계 지시에 들어간다. */
    suspend fun rehearsed(transcript: String) = dispatch(CoachEvent.Rehearsed(transcript))

    private suspend fun dispatch(event: CoachEvent) = lock.withLock {
        val before = _state.value ?: return@withLock
        val after = reduce(before, event)
        store.append(before.id, before.phase, event)
        if (after != before) set(after)
    }

    private suspend fun set(state: CoachState) {
        store.save(state)
        _state.value = state
        sync(state)
    }

    /** 대화 단계에서 진행 중이고 끊기지 않았으면 그 단계 연결 하나를 둔다. 단계가 바뀌면 앞 연결이 닫힌 뒤 새로 연다. */
    private fun sync(state: CoachState) {
        val live = state.status == CoachStatus.Active && state.disconnected == null && state.phase in LIVE_PHASES
        if (live && connectedPhase == state.phase && connection?.isActive == true) return
        val old = connection
        old?.cancel()
        connection = if (live) {
            scope.launch {
                old?.join()
                talk(state)
            }
        } else {
            null
        }
        connectedPhase = state.phase.takeIf { live }
    }

    private suspend fun talk(state: CoachState) = coroutineScope {
        val handle = state.resumeHandle?.takeIf { state.handleAt != null && clock.now() - state.handleAt < RESUME_LIMIT }
        launch { forwardMic() }
        launch {
            // ponytail: 1초마다 잰다. 일시정지할 때 1초 미만은 버린다.
            while (true) {
                val from = clock.now()
                delay(1.seconds)
                dispatch(CoachEvent.TimePassed(clock.now() - from))
            }
        }
        transport.connect(instruction(profile, state), profile.tools, handle).collect { event ->
            when (event) {
                is TransportEvent.Audio -> audio.play(event.pcm)

                TransportEvent.Interrupted -> audio.flush()

                is TransportEvent.UserSaid -> dispatch(CoachEvent.Heard(Speaker.User, event.text))

                is TransportEvent.CoachSaid -> dispatch(CoachEvent.Heard(Speaker.Coach, event.text))

                is TransportEvent.ToolCall -> {
                    // 버린 호출에도 답해야 모델이 기다리지 않고 이어 말한다.
                    val error = toolError(event.name, event.args)
                    transport.sendToolResponse(event.id, event.name, buildJsonObject { if (error == null) put("ok", true) else put("error", error) })
                    dispatch(CoachEvent.ToolCalled(event.name, event.args))
                }

                is TransportEvent.Usage -> dispatch(CoachEvent.Used(event.usage))

                is TransportEvent.ResumeHandle -> dispatch(CoachEvent.HandleReceived(event.handle, clock.now()))

                is TransportEvent.Disconnected -> dispatch(CoachEvent.Dropped(event.reason))
            }
        }
    }

    /** 말소리 조각만 보낸다. 말하다 침묵이 시작되면 오디오 끝을 한 번 보낸다. */
    private suspend fun forwardMic() {
        var speaking = false
        audio.mic.collect { chunk ->
            if (chunk.speech) {
                transport.sendAudio(chunk.pcm)
                speaking = true
            } else if (speaking) {
                transport.sendAudioEnd()
                speaking = false
            }
        }
    }

    private companion object {
        val LIVE_PHASES = setOf(CoachPhase.Explore, CoachPhase.Express, CoachPhase.Feedback)
    }
}
