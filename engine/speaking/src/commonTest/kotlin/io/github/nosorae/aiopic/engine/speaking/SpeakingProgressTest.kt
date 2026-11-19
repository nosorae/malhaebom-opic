package io.github.nosorae.aiopic.engine.speaking

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SpeakingProgressTest {
    private val t0 = Instant.parse("2026-11-19T09:00:00Z")

    private fun state(planned: Duration = 20.minutes, reviewCardIds: List<Long> = emptyList()) = SpeakingState(id = "p1", startedAt = t0, planned = planned, route = AudioRoute.Earphones, topic = "cooking", reviewCardIds = reviewCardIds)

    private fun tool(name: String, args: JsonObject = JsonObject(emptyMap())) = SpeakingEvent.ToolCalled(name, args, t0)

    private fun ask(text: String, kind: String = "description") = tool(
        SpeakingTools.ASK_QUESTION,
        buildJsonObject {
            put("question", text)
            put("kind", kind)
        },
    )

    private fun SpeakingState.after(vararg events: SpeakingEvent) = events.fold(this, ::reduce)

    private fun SpeakingState.talked(duration: Duration) = reduce(this, SpeakingEvent.TimePassed(duration, t0))

    private fun review(cardId: Long) = OralReview(cardId, "better", correct = true, judgedAtMs = 0, before = ReviewValues(null, null, 0, null))

    @Test
    fun `F16_8 복습할 카드가 있으면 말로 복습으로 시작하고 없으면 대화로 시작한다`() {
        assertEquals(SpeakingPhase.OralReview to SpeakingPhase.Talk, state(reviewCardIds = listOf(1, 2)).phase to state().phase)
    }

    @Test
    fun `F16_8 고른 카드를 다 판정하면 대화로 넘어가고 판정은 말로 복습 결과에 남는다`() {
        val done = state(reviewCardIds = listOf(1, 2)).after(SpeakingEvent.Judged(review(1)), SpeakingEvent.Judged(review(2)))

        assertEquals(SpeakingPhase.Talk to listOf(1L, 2L), done.phase to done.oralReviews.map { it.cardId })
    }

    @Test
    fun `F16_8 고르지 않은 카드나 이미 판정한 카드의 판정은 버린다`() {
        val after = state(reviewCardIds = listOf(1, 2)).after(SpeakingEvent.Judged(review(9)), SpeakingEvent.Judged(review(1)), SpeakingEvent.Judged(review(1)))

        assertEquals(listOf(1L) to SpeakingPhase.OralReview, after.oralReviews.map { it.cardId } to after.phase)
    }

    @Test
    fun `F16_13 남은 시간은 정한 시간에서 대화 시간을 뺀 값이고 일시정지 동안 줄지 않는다`() {
        val after = state().talked(3.minutes).after(SpeakingEvent.Pause(t0)).talked(2.minutes)

        assertEquals(17.minutes, after.remaining)
    }

    @Test
    fun `F16_13 남은 시간이 5분이면 마무리 단계로 가고 0 이면 끝낸다`() {
        val wrap = state().talked(15.minutes)

        assertEquals(SpeakingPhase.Wrap to true, wrap.phase to wrap.talked(5.minutes).finished)
    }

    @Test
    fun `F16_12 대답 없음 두 번이면 일시정지하고 사용자가 말하면 0 으로 돌린다`() {
        val once = state().after(SpeakingEvent.NoAnswer(t0))

        assertEquals(SpeakingStatus.Listening, once.status)
        assertEquals(0, once.after(SpeakingEvent.UserTalked(2.seconds)).silenceCount)
        assertEquals(SpeakingStatus.Paused to t0, once.after(SpeakingEvent.NoAnswer(t0)).let { it.status to it.pausedAt })
    }

    @Test
    fun `F16_21 확인 질문 뒤의 대답 없음은 세지 않는다`() {
        val after = state().after(tool(SpeakingTools.CLARIFY), SpeakingEvent.NoAnswer(t0), SpeakingEvent.NoAnswer(t0))

        assertEquals(1 to SpeakingStatus.Listening, after.silenceCount to after.status)
    }

    @Test
    fun `F16_21 clarify 뒤 NoAnswer 면 silenceCount 는 그대로이고 코치에게 다시 묻게 한다`() {
        val clarifying = state().after(tool(SpeakingTools.CLARIFY))
        val after = clarifying.after(SpeakingEvent.NoAnswer(t0))

        assertEquals(0, after.silenceCount)
        assertEquals(listOf(Cue.Rephrase), cues(clarifying, after))
        assertEquals(emptyList(), cues(clarifying, clarifying.after(SpeakingEvent.UserTalked(2.seconds))))
    }

    @Test
    fun `F16_12 일시정지 중 사용자 말은 명령만 듣고 내가 말한 시간과 대답 없음에 세지 않는다`() {
        val paused = state().after(SpeakingEvent.NoAnswer(t0), SpeakingEvent.Pause(t0))

        assertEquals(paused, paused.after(SpeakingEvent.UserTalked(3.seconds)))
    }

    @Test
    fun `F16_9 일시정지가 아닐 때 다시 시작은 아무것도 바꾸지 않는다`() {
        val dropped = state().after(SpeakingEvent.Dropped(t0))

        assertEquals(dropped, dropped.after(SpeakingEvent.Resume))
    }

    @Test
    fun `F16_12 일시정지가 10분 이어지면 끝낸다`() {
        val paused = state().after(SpeakingEvent.Pause(t0))

        assertFalse(reduce(paused, SpeakingEvent.TimePassed(1.seconds, t0 + 9.minutes)).finished)
        assertTrue(reduce(paused, SpeakingEvent.TimePassed(1.seconds, t0 + 10.minutes)).finished)
    }

    @Test
    fun `F16_9 명령 잠깐과 다시 시작과 그만할래를 도구 호출로 받는다`() {
        val paused = state().after(tool(SpeakingTools.PAUSE))

        assertEquals(SpeakingStatus.Paused, paused.status)
        assertEquals(SpeakingStatus.Listening to null, paused.after(tool(SpeakingTools.RESUME)).let { it.status to it.pausedAt })
        assertTrue(state().after(tool(SpeakingTools.STOP)).finished)
    }

    @Test
    fun `F16_9 명령 일곱은 모두 받는 도구다`() {
        val commands = listOf(
            SpeakingTools.PAUSE,
            SpeakingTools.RESUME,
            SpeakingTools.HINT,
            SpeakingTools.WHAT_IN_ENGLISH,
            SpeakingTools.NEXT_QUESTION,
            SpeakingTools.SWITCH_TOPIC,
            SpeakingTools.STOP,
        )

        assertEquals(List(7) { null }, commands.map { toolError(it, JsonObject(emptyMap())) })
    }

    @Test
    fun `F16_9 다른 주제는 주제를 바꾸고 주제 바꾼 횟수를 올린다`() {
        val after = state().after(SpeakingEvent.TopicChanged("music"))

        assertEquals("music" to 1, after.topic to after.topicSwitches)
    }

    @Test
    fun `F16_6 질문할 때마다 질문 문장과 유형을 지금 주제와 함께 다룬 질문에 적는다`() {
        val after = state().after(ask("What do you cook?"), ask("When did you last cook?", "past_experience"))

        assertEquals(
            listOf(Asked("What do you cook?", QuestionKind.Description, "cooking"), Asked("When did you last cook?", QuestionKind.PastExperience, "cooking")),
            after.questions,
        )
    }

    @Test
    fun `F16_20 같은 질문은 말한 횟수를 올리고 3회를 넘지 않는다`() {
        val after = state().after(*Array(4) { ask("What do you cook?") })

        assertEquals(3, after.questions.single().times)
    }

    @Test
    fun `F16_20 정한 시간이 10분이면 같은 질문은 2회까지다`() {
        val after = state(planned = 10.minutes).after(*Array(3) { ask("What do you cook?") })

        assertEquals(2, after.questions.single().times)
    }

    @Test
    fun `F16_6 문단이 끊기거나 시제가 무너지면 지금 질문에 표시한다`() {
        val after = state().after(ask("Q1"), ask("Q2"), tool(SpeakingTools.FLAG_BROKEN))

        assertEquals(listOf(false, true), after.questions.map { it.broken })
    }

    @Test
    fun `F16_6 Q1, Q2, Q1 다시 물음 뒤 flag_broken 이면 Q1 이 broken 이다`() {
        val after = state().after(ask("Q1"), ask("Q2"), ask("Q1"), tool(SpeakingTools.FLAG_BROKEN))

        assertEquals(listOf(true, false), after.questions.map { it.broken })
        assertEquals(2, after.questions[after.current!!].times)
    }

    @Test
    fun `F16_6 주제를 바꾼 뒤 새 질문 전의 무너짐 표시는 앞 주제 질문에 붙이지 않는다`() {
        val after = state().after(ask("Q1"), SpeakingEvent.TopicChanged("music"), tool(SpeakingTools.FLAG_BROKEN))

        assertEquals(listOf(false), after.questions.map { it.broken })
    }

    @Test
    fun `F16_24 내가 말한 시간과 코치가 말한 시간을 더하고 코치 말이 끝나면 듣는 중으로 간다`() {
        val speaking = state().after(SpeakingEvent.UserTalked(4.seconds), SpeakingEvent.CoachStarted)
        val after = speaking.after(SpeakingEvent.CoachTalked(2.seconds))

        assertEquals(SpeakingStatus.CoachSpeaking, speaking.status)
        assertEquals(Triple(4.seconds, 2.seconds, SpeakingStatus.Listening), Triple(after.userTalk, after.coachTalk, after.status))
    }

    @Test
    fun `F16_15 연결이 끊기면 다시 연결 횟수를 올리고 3번을 넘으면 일시정지하고 끊김으로 둔다`() {
        val three = state().after(*Array(3) { SpeakingEvent.Dropped(t0) })
        val four = three.after(SpeakingEvent.Dropped(t0))

        assertEquals(SpeakingStatus.Listening to false, three.status to three.disconnected)
        assertEquals(SpeakingStatus.Paused to true, four.status to four.disconnected)
        assertEquals(0 to false, four.after(SpeakingEvent.Resume).let { it.reconnects to it.disconnected })
    }

    @Test
    fun `F16_15 연결에 성공하면 다시 연결 횟수를 0 으로 돌린다`() {
        assertEquals(0, state().after(SpeakingEvent.Dropped(t0), SpeakingEvent.Connected).reconnects)
    }

    @Test
    fun `F16_19 표현을 저장할 때마다 저장한 표현 수가 오른다`() {
        assertEquals(2, state().after(SpeakingEvent.ExpressionSaved, SpeakingEvent.ExpressionSaved).savedCount)
    }

    @Test
    fun `F16_16 받아쓰기 조각은 같은 사람이 이어 말하면 한 줄로 잇는다`() {
        val after = state().after(SpeakingEvent.Heard(Speaker.User, "I "), SpeakingEvent.Heard(Speaker.User, "cook."), SpeakingEvent.Heard(Speaker.Coach, "Why?"))

        assertEquals(listOf(Line(Speaker.User, "I cook."), Line(Speaker.Coach, "Why?")), after.transcript)
    }

    @Test
    fun `F16_16 사용량을 모달리티마다 더하고 비용을 낸다`() {
        val after = state().after(SpeakingEvent.Used(Usage(mapOf("AUDIO" to 1_000_000))), SpeakingEvent.Used(Usage(output = mapOf("AUDIO" to 500_000))))

        assertEquals(3.0 + 6.0, after.usage.costUsd)
    }

    @Test
    fun `F16_11 끝난 연습은 더 바뀌지 않는다`() {
        val done = state().after(SpeakingEvent.Finish)

        assertEquals(done, done.after(SpeakingEvent.UserTalked(3.seconds)))
    }

    @Test
    fun `틀린 도구 인자는 상태를 바꾸지 않고 에러 글을 돌려준다`() {
        val bad = tool(SpeakingTools.ASK_QUESTION, buildJsonObject { put("question", "Q") })

        assertEquals(state(), state().after(bad))
        assertTrue(toolError(bad.name, bad.args)!!.isNotEmpty())
    }

    // 코치에게 글로 알리는 일(F16.12, F16.13, F16.20, F16.8)

    @Test
    fun `F16_12 대답 없음 첫 번째면 질문을 짧게 바꿔 묻고 두 번째면 일시정지를 알린다`() {
        val once = state().after(SpeakingEvent.NoAnswer(t0))

        assertEquals(listOf(Cue.Rephrase), cues(state(), once))
        assertEquals(listOf(Cue.Paused), cues(once, once.after(SpeakingEvent.NoAnswer(t0))))
    }

    @Test
    fun `F16_20 남은 5분이 되면 첫 질문을 다시 묻게 한다`() {
        val before = state().after(ask("Q1")).talked(14.minutes)

        assertEquals(listOf(Cue.Wrap), cues(before, before.talked(1.minutes)))
    }

    @Test
    fun `F16_13 남은 1분이 되면 마무리 문장 하나를 말하게 한다`() {
        val before = state().talked(18.minutes)

        assertEquals(listOf(Cue.Closing), cues(before, before.talked(1.minutes)))
    }

    /** 코치가 한 턴을 말하고 끝냈다. */
    private fun SpeakingState.coachTurn() = after(SpeakingEvent.CoachStarted, SpeakingEvent.CoachTalked(2.seconds))

    @Test
    fun `F16_20 2회째 대답이 끝나고 코치 턴이 끝났을 때 2분 넘게 남았으면 한 번 더 묻게 한다`() {
        val answered = state().after(ask("Q1")).talked(15.minutes).after(ask("Q1")).coachTurn().after(SpeakingEvent.UserTalked(30.seconds))

        assertEquals(listOf(Cue.Again), cues(answered, answered.coachTurn()))
    }

    @Test
    fun `F16_20 2회째 대답 중 UserTalked 두 번이면 Again 은 한 번이고 대답이 끝난 뒤다`() {
        val second = state().after(ask("Q1")).talked(15.minutes).after(ask("Q1")).coachTurn()
        val pause = second.after(SpeakingEvent.UserTalked(5.seconds))
        val answer = pause.after(SpeakingEvent.UserTalked(5.seconds))
        val again = answer.coachTurn()

        assertEquals(emptyList(), cues(second, pause) + cues(pause, answer))
        assertEquals(listOf(Cue.Again), cues(answer, again))
        assertEquals(emptyList(), cues(again, again.coachTurn()))
    }

    @Test
    fun `F16_20 2분 이하로 남았거나 정한 시간이 10분이면 3회째를 묻지 않는다`() {
        val late = state().after(ask("Q1")).talked(18.minutes).after(ask("Q1"), SpeakingEvent.UserTalked(5.seconds))
        val short = state(planned = 10.minutes).after(ask("Q1")).talked(5.minutes).after(ask("Q1"), SpeakingEvent.UserTalked(5.seconds))

        assertEquals(emptyList(), cues(late, late.coachTurn()))
        assertEquals(emptyList(), cues(short, short.coachTurn()))
    }

    @Test
    fun `F16_20 상한을 넘는 질문은 에러 글로 돌려준다`() {
        val three = state().after(*Array(3) { ask("Q1") })
        val two = state(planned = 10.minutes).after(*Array(2) { ask("Q1") })

        assertEquals("asked 3 times already", askError(three, ask("Q1").args))
        assertEquals("asked 2 times already", askError(two, ask("Q1").args))
        assertEquals(null, askError(three, ask("Q2").args))
    }

    @Test
    fun `F16_20 말로 복습 중 남은 5분이면 대화를 시작하게 하고 빈 질문 Wrap 글을 보내지 않는다`() {
        val before = state(reviewCardIds = listOf(1, 2, 3)).talked(14.minutes)
        val wrap = before.talked(1.minutes)

        assertEquals(SpeakingPhase.Wrap, wrap.phase)
        assertEquals(listOf(Cue.Talk), cues(before, wrap))
        assertEquals(listOf(1L), wrap.after(SpeakingEvent.Judged(review(1))).oralReviews.map { it.cardId })
    }

    @Test
    fun `F16_20 주제를 막 바꿔 첫 질문이 없으면 남은 5분에 Wrap 글을 보내지 않는다`() {
        val before = state().after(ask("Q1"), SpeakingEvent.TopicChanged("music")).talked(14.minutes)

        assertEquals(emptyList(), cues(before, before.talked(1.minutes)))
    }

    @Test
    fun `F16_12 F16_14 일시정지하면 다시 시작 말을 안내하게 하고 돌아오면 이어서 할지 묻게 한다`() {
        val paused = state().after(SpeakingEvent.Pause(t0))

        assertEquals(listOf(Cue.Paused), cues(state(), paused))
        assertEquals(listOf(Cue.Resumed), cues(paused, paused.after(SpeakingEvent.Resume)))
    }

    @Test
    fun `F16_15 끊김으로 멈추거나 다시 연결할 때는 코치에게 알리지 않는다`() {
        val three = state().after(*Array(3) { SpeakingEvent.Dropped(t0) })
        val four = three.after(SpeakingEvent.Dropped(t0))

        assertEquals(emptyList(), cues(three, four) + cues(four, four.after(SpeakingEvent.Resume)))
    }

    @Test
    fun `F16_8 말로 복습이 끝나면 대화를 시작하게 한다`() {
        val before = state(reviewCardIds = listOf(1))

        assertEquals(listOf(Cue.Talk), cues(before, before.after(SpeakingEvent.Judged(review(1)))))
    }

    @Test
    fun `F16_20 마무리에서 다시 물을 첫 질문은 지금 주제의 첫 질문이다`() {
        val after = state().after(ask("Old topic Q"), SpeakingEvent.TopicChanged("music"), ask("Music Q"), ask("Music Q2"))

        assertEquals("Music Q", after.firstQuestion?.text)
    }

    // 유형 고르기(F16.6)

    @Test
    fun `F16_6 연습 시간이 10분이면 2개 20분이면 돌발 상황을 넣어 4개 30분이면 5개 유형을 묻는다`() {
        assertEquals(listOf(QuestionKind.Description, QuestionKind.PastExperience), questionKinds(10.minutes, weak = null))
        assertEquals(listOf(QuestionKind.Description, QuestionKind.PastExperience, QuestionKind.FuturePlan, QuestionKind.Unexpected), questionKinds(20.minutes, weak = null))
        assertEquals(5, questionKinds(30.minutes, weak = null).size)
        assertTrue(QuestionKind.Unexpected in questionKinds(30.minutes, weak = null))
    }

    @Test
    fun `F16_6 무너진 기록이 많은 유형을 두 번째로 묻는다`() {
        assertEquals(QuestionKind.Comparison, questionKinds(10.minutes, weak = QuestionKind.Comparison)[1])
    }

    @Test
    fun `F16_6 무너진 기록이 많은 유형은 무너진 질문 수로 고르고 없으면 null 이다`() {
        val asked = listOf(
            Asked("a", QuestionKind.Routine, "t", broken = true),
            Asked("b", QuestionKind.Comparison, "t", broken = true),
            Asked("c", QuestionKind.Comparison, "t", broken = true),
            Asked("d", QuestionKind.Description, "t"),
        )

        assertEquals(QuestionKind.Comparison to null, weakKind(asked) to weakKind(asked.map { it.copy(broken = false) }))
    }
}
