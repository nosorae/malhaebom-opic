package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.answer.model.JobKind
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingLink
import io.github.nosorae.aiopic.engine.speaking.SpeakingProfile
import io.github.nosorae.aiopic.engine.speaking.SpeakingSetup
import io.github.nosorae.aiopic.engine.speaking.SpeakingStatus
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import io.github.nosorae.aiopic.libs.duplexaudio.api.AudioInterruption
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.ui.featurespeaking.PracticeCommand
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 화면, 알림, 이어폰 버튼, 오디오 끼어듦이 모두 앱 범위 연습 하나에 같은 명령으로 들어간다(ADR 0038, F16.10, F16.14). */
class RunningPracticeTest {
    private val db = inMemoryDatabase()

    @AfterTest fun close() = db.close()

    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-11-20T10:00:00Z")
    }
    private val store = OpicStore(db)
    private val setup = SpeakingSetup(planned = 20.minutes, route = AudioRoute.Earphones, topics = listOf("cooking"))

    private fun TestScope.practice(): RunningPractice {
        val jobs = testAnalysisJobs(store, FakeWorkQueue(), AppSettings.inMemory())
        return RunningPractice(
            newSession = { scope -> speakingSession(PROFILE, QuietLink, FakeDuplexAudio, SpeakingStore(db), CardStore(db, TimeZone.UTC), jobs, clock, TimeZone.UTC, scope) },
            audio = FakeDuplexAudio,
            clock = clock,
            scope = backgroundScope,
        )
    }

    @Test
    fun `F16_10 이어폰 한 번 누르기는 일시정지와 다시 시작을 번갈아 한다`() = runTest {
        val practice = practice()
        practice.start("p1", setup)

        practice.command(PracticeCommand.Toggle)
        val paused = practice.current?.status
        practice.command(PracticeCommand.Toggle)

        assertEquals(SpeakingStatus.Paused to SpeakingStatus.Listening, paused to practice.current?.status)
    }

    @Test
    fun `F16_14 끼어듦이 시작되면 일시정지하고 끝나면 다시 시작한다`() = runTest {
        val practice = practice()
        practice.start("p1", setup)

        practice.interrupted(AudioInterruption.Began)
        val paused = practice.current?.status
        practice.interrupted(AudioInterruption.Ended)

        assertEquals(SpeakingStatus.Paused to SpeakingStatus.Listening, paused to practice.current?.status)
    }

    @Test
    fun `F16_14 사용자가 멈춘 연습은 끼어듦이 끝나도 멈춘 채로 둔다`() = runTest {
        val practice = practice()
        practice.start("p1", setup)

        practice.command(PracticeCommand.Pause)
        practice.interrupted(AudioInterruption.Began)
        practice.interrupted(AudioInterruption.Ended)

        assertEquals(SpeakingStatus.Paused, practice.current?.status)
    }

    @Test
    fun `F16_14 이어폰을 빼서 멈춘 연습은 뒤에 전화가 왔다 끊겨도 멈춘 채로 둔다`() = runTest {
        val practice = practice()
        practice.start("p1", setup)

        practice.interrupted(AudioInterruption.Unplugged)
        practice.interrupted(AudioInterruption.Began)
        practice.interrupted(AudioInterruption.Ended)

        assertEquals(SpeakingStatus.Paused, practice.current?.status)
    }

    @Test
    fun `F16_11 F17_4 알림 버튼으로 끝내면 연습 정리 작업이 바로 들어간다`() = runTest {
        val practice = practice()
        practice.start("p1", setup)

        practice.command(PracticeCommand.Finish)

        assertEquals(listOf(JobKind.Cards to "p1"), store.jobs().first().map { it.kind to it.targetId })
    }

    @Test
    fun `F16_4 연습이 도는 동안에는 새 연습을 시작하지 않는다`() = runTest {
        val practice = practice()
        practice.start("p1", setup)

        practice.start("p2", setup)

        assertEquals("p1", practice.current?.id)
    }
}

private val PROFILE = SpeakingProfile("Talk about {{topic}}.", emptyList(), emptyMap())

/** 연결을 열어 두기만 한다. 끊기지 않아 다시 연결하지 않는다. */
private object QuietLink : SpeakingLink {
    override fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<LiveEvent> = flow { awaitCancellation() }

    override suspend fun sendAudio(pcm: ByteArray) = Unit

    override suspend fun sendAudioEnd() = Unit

    override suspend fun sendText(text: String) = Unit

    override suspend fun sendToolResponse(id: String, name: String, result: JsonElement) = Unit
}
