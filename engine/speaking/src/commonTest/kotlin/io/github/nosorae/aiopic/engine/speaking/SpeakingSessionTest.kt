package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.nextDayStart
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SpeakingSessionTest {
    private val profile = SpeakingProfile(
        instruction = "topic={{topic}} repeat={{repeat_question}} minutes={{minutes}} kinds={{kinds}} cards={{review_cards}} errors={{frequent_errors}} grammar={{grammar_nudge}}",
        tools = listOf(buildJsonObject { put("name", SpeakingTools.PAUSE) }),
        cues = Cue.entries.associateWith { "${it.name} {{question}}" },
    )
    private val link = FakeLink()
    private val audio = FakeAudio()
    private val seoul = TimeZone.of("Asia/Seoul")
    private val base = Instant.parse("2026-11-19T09:00:00Z")
    private val finished = mutableListOf<String>()
    private val setup = SpeakingSetup(planned = 20.minutes, route = AudioRoute.Earphones, topics = listOf("cooking", "music"), grammarNudge = true)

    /** DB 쿼리와 세션의 1초 시계를 같은 테스트 시계 위에서 돌린다. */
    private fun test(block: suspend TestScope.(OpicDatabase) -> Unit) = runTest {
        val db = inMemoryDatabase(StandardTestDispatcher(testScheduler))
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun TestScope.now() = base + testScheduler.currentTime.milliseconds

    private fun TestScope.session(db: OpicDatabase) = SpeakingSession(
        profile = profile,
        link = link,
        audio = audio,
        store = SpeakingStore(db),
        cards = CardStore(db, seoul),
        clock = object : Clock {
            override fun now() = this@session.now()
        },
        timeZone = seoul,
        scope = backgroundScope,
        onFinished = { finished += it },
    )

    private suspend fun TestScope.started(db: OpicDatabase, setup: SpeakingSetup = this@SpeakingSessionTest.setup) = session(db).also {
        it.start("p1", setup)
        runCurrent()
    }

    private fun tool(id: String, name: String, args: JsonObject = JsonObject(emptyMap())) = LiveEvent.ToolCall(id, name, args)

    private fun card(seen: Boolean, dueAt: Instant = base - 1.days) = ExpressionCard(
        kind = CardKind.Correction,
        said = "I go yesterday.",
        better = "I went yesterday.",
        meaning = "어제 갔다.",
        errorType = null,
        context = "주말 얘기",
        createdAt = base - 3.days,
        dueAt = dueAt,
        firstSeen = !seen,
    )

    private fun SpeakingSession.now() = state.value!!

    @Test
    fun `F16_5 시작하면 고른 주제와 유형과 문법 짚기 값을 지시에 넣어 연결한다`() = test { db ->
        started(db)

        assertEquals(
            FakeLink.Connect("topic=cooking repeat=none minutes=20 kinds=description, past_experience, future_plan, unexpected cards=none errors=none grammar=on", profile.tools, null),
            link.connects.single(),
        )
    }

    @Test
    fun `F16_8 처음 봄이 아닌 오늘 복습할 카드 앞 3장으로 말로 복습을 시작한다`() = test { db ->
        val cards = CardStore(db, seoul)
        val seen = List(4) { cards.save(card(seen = true)) }
        cards.save(card(seen = false))
        cards.save(card(seen = true, dueAt = base + 1.days))

        val session = started(db)

        assertEquals(SpeakingPhase.OralReview, session.now().phase)
        assertEquals(3, session.now().reviewCardIds.size)
        assertTrue(seen.containsAll(session.now().reviewCardIds))
        assertTrue("card ${session.now().reviewCardIds.first()}: 주말 얘기 | 어제 갔다. | I went yesterday." in link.connects.single().instruction)
    }

    @Test
    fun `F16_8 설정에서 말로 복습을 끄면 대화로 시작한다`() = test { db ->
        CardStore(db, seoul).save(card(seen = true))

        assertEquals(SpeakingPhase.Talk, started(db, setup.copy(oralReview = false)).now().phase)
    }

    @Test
    fun `F16_8 판정하면 FSRS 와 복습 횟수와 복습한 날을 세고 판정 전 복습 값을 남긴다`() = test { db ->
        val cards = CardStore(db, seoul)
        val id = cards.save(card(seen = true))
        val session = started(db)

        link.server(
            tool(
                "1",
                SpeakingTools.JUDGE_CARD,
                buildJsonObject {
                    put("card_id", id)
                    put("result", "wrong")
                },
            ),
        )
        runCurrent()

        val rated = cards.card(id)!!
        assertEquals(1 to 1, rated.reviewCount to rated.againCount)
        assertEquals(now(), rated.lastReviewedAt)
        assertEquals(1, cards.reviewDays().first().size)
        assertEquals(OralReview(id, "I went yesterday.", correct = false, judgedAtMs = now().toEpochMilliseconds(), before = ReviewValues(null, null, (base - 1.days).toEpochMilliseconds(), null)), session.now().oralReviews.single())
        assertEquals(SpeakingPhase.Talk, session.now().phase)
        assertEquals("Talk ", link.texts.single())
    }

    @Test
    fun `F16_23 몰랐던 표현은 처음 봄 예와 다음 날 새벽 4시로 카드에 저장하고 저장한 표현 수를 올린다`() = test { db ->
        val session = started(db)

        link.server(
            tool(
                "1",
                SpeakingTools.SAVE_EXPRESSION,
                buildJsonObject {
                    put("said", "I like 산책 after dinner.")
                    put("better", "go for a walk")
                    put("meaning", "산책하다")
                    put("context", "저녁 습관을 말하다가")
                },
            ),
        )
        runCurrent()

        val saved = CardStore(db, seoul).cards().first().single()
        assertEquals(
            ExpressionCard(
                id = saved.id, kind = CardKind.UnknownExpression, said = "I like 산책 after dinner.", better = "go for a walk", meaning = "산책하다", errorType = null,
                context = "저녁 습관을 말하다가", createdAt = now(), dueAt = now().nextDayStart(seoul), practiceId = "p1",
            ),
            saved,
        )
        assertEquals(1, session.now().savedCount)
    }

    @Test
    fun `F16_9 다른 주제는 방금 주제를 빼고 고르고 코치에게 새 주제를 알린다`() = test { db ->
        val session = started(db)

        link.server(tool("1", SpeakingTools.SWITCH_TOPIC))
        runCurrent()

        assertEquals("music" to 1, session.now().topic to session.now().topicSwitches)
        assertEquals("music", link.responses.single().second.jsonObject["topic"]?.jsonPrimitive?.content)
    }

    @Test
    fun `모르는 도구는 상태를 바꾸지 않고 에러로 답한다`() = test { db ->
        val session = started(db)
        val before = session.now()

        link.server(tool("1", "dance"))
        runCurrent()

        assertEquals(before, session.now())
        assertTrue("error" in link.responses.single().second.jsonObject)
    }

    @Test
    fun `F16_24 말소리 조각 길이를 내가 말한 시간에 더하고 말이 멈추면 오디오 끝을 보낸다`() = test { db ->
        val session = started(db)

        // 40ms 조각이라 1초는 25개다.
        audio.say(*Array(25) { LOUD }, *Array(30) { SILENT })
        runCurrent()

        assertEquals(1.seconds to 1, session.now().userTalk to link.audioEnds)
    }

    @Test
    fun `F16_9 일시정지 중에도 말소리를 보내 resume 도구로 다시 시작된다`() = test { db ->
        val session = started(db)

        session.send(SpeakingEvent.Pause(now()))
        audio.say(*Array(25) { LOUD }, *Array(30) { SILENT })
        runCurrent()
        assertEquals(1 to Duration.ZERO, link.audioEnds to session.now().userTalk)

        link.server(tool("1", SpeakingTools.RESUME))
        runCurrent()

        assertEquals(SpeakingStatus.Listening, session.now().status)
        assertEquals(listOf("Paused ", "Resumed "), link.texts)
    }

    @Test
    fun `F16_15 끊김으로 멈췄으면 마이크를 보내지 않는다`() = test { db ->
        val session = started(db)

        repeat(4) {
            link.server(LiveEvent.Failed(null, "down"))
            advanceTimeBy(5.seconds)
        }
        audio.say(LOUD, LOUD)
        runCurrent()

        assertTrue(session.now().disconnected)
        assertEquals(0, link.audio.size)
    }

    @Test
    fun `F16_20 같은 질문을 상한만큼 말했으면 ask_question 에 에러로 답하고 기록하지 않는다`() = test { db ->
        val session = started(db)
        val args = buildJsonObject {
            put("question", "What do you cook?")
            put("kind", "description")
        }

        link.server(*Array(4) { tool("$it", SpeakingTools.ASK_QUESTION, args) })
        runCurrent()

        assertEquals(3, session.now().questions.single().times)
        assertEquals("asked 3 times already", link.responses.last().second.jsonObject["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `F16_24 코치 음성은 재생한 길이를 코치가 말한 시간에 더하고 재생이 끝나면 듣는 중으로 간다`() = test { db ->
        val session = started(db)

        link.server(LiveEvent.AudioChunk(COACH_SECOND), LiveEvent.AudioChunk(COACH_SECOND), LiveEvent.TurnComplete)
        runCurrent()
        assertEquals(SpeakingStatus.CoachSpeaking, session.now().status)

        advanceTimeBy(2.seconds + 1.seconds / 10)
        assertEquals(2.seconds to SpeakingStatus.Listening, session.now().coachTalk to session.now().status)
    }

    @Test
    fun `F16_24 말로 끊긴 코치 음성은 실제로 재생한 만큼만 더한다`() = test { db ->
        val session = started(db)

        link.server(*Array(3) { LiveEvent.AudioChunk(COACH_SECOND) })
        runCurrent()
        advanceTimeBy(1.seconds)
        link.server(LiveEvent.Interrupted)
        runCurrent()

        assertEquals(1.seconds, session.now().coachTalk)
        assertTrue(audio.queued.isEmpty())
    }

    @Test
    fun `F16_12 코치 말 뒤에 대답이 없으면 짧게 다시 묻게 하고 두 번째면 일시정지한다`() = test { db ->
        val session = started(db)

        link.server(LiveEvent.AudioChunk(COACH_SECOND), LiveEvent.TurnComplete)
        advanceTimeBy(1.seconds + NO_ANSWER_WAIT + 1.seconds / 10)
        assertEquals(listOf("Rephrase "), link.texts)

        link.server(LiveEvent.AudioChunk(COACH_SECOND), LiveEvent.TurnComplete)
        advanceTimeBy(1.seconds + NO_ANSWER_WAIT + 1.seconds / 10)
        assertEquals(listOf("Rephrase ", "Paused "), link.texts)
        assertEquals(SpeakingStatus.Paused, session.now().status)
    }

    @Test
    fun `F16_12 코치 말 뒤에 사용자가 말하면 대답 없음으로 세지 않는다`() = test { db ->
        val session = started(db)

        link.server(LiveEvent.AudioChunk(COACH_SECOND), LiveEvent.TurnComplete)
        advanceTimeBy(2.seconds)
        audio.say(*Array(25) { LOUD })
        advanceTimeBy(NO_ANSWER_WAIT)

        assertEquals(0, session.now().silenceCount)
    }

    @Test
    fun `F16_15 연결이 끊기면 받은 이어 붙이기 핸들로 다시 연결하고 3번을 넘으면 끊김으로 일시정지한다`() = test { db ->
        val session = started(db)

        link.server(LiveEvent.ResumeHandle("h1"), LiveEvent.Disconnected(1006, "gone"))
        runCurrent()
        assertEquals(1, link.connects.size)
        advanceTimeBy(1.seconds + 1.seconds / 10)
        assertEquals("h1", link.connects[1].resumeHandle)

        repeat(3) {
            link.server(LiveEvent.Failed(null, "down"))
            advanceTimeBy(5.seconds)
        }
        assertEquals(4, link.connects.size)
        assertEquals(SpeakingStatus.Paused to true, session.now().status to session.now().disconnected)

        session.send(SpeakingEvent.Resume)
        runCurrent()
        assertEquals(5, link.connects.size)
    }

    @Test
    fun `F16_15 Connected 직후 Disconnected 가 반복되면 끊김으로 일시정지한다`() = test { db ->
        val session = started(db)

        repeat(4) {
            link.server(LiveEvent.Connected, LiveEvent.Disconnected(1011, "quota"))
            advanceTimeBy(5.seconds)
        }

        assertEquals(4, link.connects.size)
        assertEquals(SpeakingStatus.Paused to true, session.now().status to session.now().disconnected)
    }

    @Test
    fun `F16_15 다시 연결한 뒤 응답이 오면 다시 연결 횟수를 0 으로 돌린다`() = test { db ->
        val session = started(db)

        link.server(LiveEvent.Disconnected(1006, "gone"))
        advanceTimeBy(1.seconds + 1.seconds / 10)
        link.server(LiveEvent.Connected)
        runCurrent()
        assertEquals(1, session.now().reconnects)

        link.server(LiveEvent.AudioChunk(COACH_SECOND))
        runCurrent()
        assertEquals(0, session.now().reconnects)
    }

    @Test
    fun `지시에 엔진이 채우지 않는 빈칸이 있으면 코치 설정을 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> { SpeakingProfile("topic={{topic}} {{level}}", emptyList(), emptyMap()) }
    }

    @Test
    fun `F16_13 남은 5분과 1분에 코치에게 알리고 0 이 되면 끝내 연습 기록으로 옮긴다`() = test { db ->
        val session = started(db)
        link.server(
            tool(
                "1",
                SpeakingTools.ASK_QUESTION,
                buildJsonObject {
                    put("question", "What do you cook?")
                    put("kind", "description")
                },
            ),
        )

        advanceTimeBy(15.minutes + 1.seconds / 10)
        assertEquals(listOf("Wrap What do you cook?"), link.texts)
        advanceTimeBy(4.minutes)
        assertEquals("Closing What do you cook?", link.texts.last())
        advanceTimeBy(1.minutes)

        assertTrue(session.now().finished)
        assertNull(db.speakingDao().active())
        assertEquals(listOf("p1" to "Pending"), db.speakingDao().sessions().first().map { it.id to it.cardStatus })
    }

    @Test
    fun `F16_12 일시정지가 10분 이어지면 끝낸다`() = test { db ->
        val session = started(db)

        session.send(SpeakingEvent.Pause(now()))
        advanceTimeBy(10.minutes + 1.seconds)

        assertTrue(session.now().finished)
    }

    @Test
    fun `F16_11 끝내면 연결을 닫고 진행 중인 값을 연습 기록에 옮기고 정리를 넣는다`() = test { db ->
        val session = started(db)
        audio.say(*Array(25) { LOUD }, *Array(30) { SILENT })
        link.server(LiveEvent.InputTranscript("I cook pasta."), LiveEvent.ResumeHandle("h1"))
        runCurrent()

        session.send(SpeakingEvent.Finish)
        runCurrent()

        val row = db.speakingDao().sessions().first().single()
        assertEquals(listOf("p1"), finished)
        assertEquals(Triple("cooking", 1_000L, "[{\"speaker\":\"User\",\"text\":\"I cook pasta.\"}]"), Triple(row.topic, row.userTalkMs, row.transcript))
        link.server(LiveEvent.ResumeHandle("h2"))
        runCurrent()
        assertEquals("h1", session.now().resumeHandle)
    }

    @Test
    fun `F16_16 응답마다 진행 중인 연습을 저장하고 앱을 다시 켜면 그 줄과 핸들로 이어 간다`() = test { db ->
        val first = started(db)
        link.server(
            LiveEvent.ResumeHandle("h1"),
            tool(
                "1",
                SpeakingTools.ASK_QUESTION,
                buildJsonObject {
                    put("question", "Q?")
                    put("kind", "routine")
                },
            ),
        )
        runCurrent()
        val saved = first.now()

        val again = session(db)
        assertTrue(again.open(setup))
        runCurrent()

        assertEquals(saved, again.now())
        assertEquals("h1", link.connects.last().resumeHandle)
    }

    @Test
    fun `진행 중인 연습이 없으면 열지 않는다`() = test { db ->
        assertEquals(false, session(db).open(setup))
    }
}
