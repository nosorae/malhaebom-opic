package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.coach.CoachEvent
import io.github.nosorae.aiopic.engine.coach.CoachPhase
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.CoachStore
import io.github.nosorae.aiopic.engine.coach.CoachTools
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.ScriptLength
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.coach.ScriptResult
import io.github.nosorae.aiopic.engine.coach.ScriptWriter
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CoachScriptViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val writer = FakeScriptWriter()
    private val grades = FakeTargetGradeStore("AL")
    private val now = Instant.fromEpochSeconds(100)
    private val clock = object : Clock {
        override fun now() = now
    }
    private val blast = Expression("진짜 좋았어", "It was an absolute blast", "IH")
    private val written = CoachScript("c1", ScriptParts("Intro.", "Body.", "Closing."), listOf(blast), edited = null, editedAt = null, promptVersion = "v1", model = "m")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(CoachStore) -> Unit) = runTest(dispatcher) {
        val db: OpicDatabase = inMemoryDatabase(dispatcher)
        val store = CoachStore(db)
        store.save(CoachState("c1", "q1", "IH", Instant.fromEpochSeconds(0)))
        val card = buildJsonObject {
            put("user_said", blast.userSaid)
            put("english", blast.english)
            put("grade", blast.grade)
        }
        store.append("c1", CoachPhase.Express, CoachEvent.ToolCalled(CoachTools.SAVE_EXPRESSION, card))
        try {
            block(store)
        } finally {
            db.close()
        }
    }

    private fun viewModel(store: CoachStore): CoachScriptViewModel {
        val save = SaveScriptEditUseCase(store, clock)
        return CoachScriptViewModel(
            sessionId = "c1",
            question = "Tell me about a trip.",
            openScriptUseCase = OpenScriptUseCase(store, writer),
            saveScriptEditUseCase = save,
            polishScriptUseCase = PolishScriptUseCase(writer, save),
            observeScriptLengthUseCase = ObserveScriptLengthUseCase(grades, mapOf("AL" to ScriptLength(2.minutes, 150.seconds, 16..20))),
        )
    }

    @Test
    fun `F14_1 스크립트가 없으면 만들어 저장하고 보인다`() = test { store ->
        val viewModel = viewModel(store)
        advanceUntilIdle()

        assertEquals(written, viewModel.uiState.value.script)
        assertEquals(written, store.script("c1").first())
        assertEquals("Tell me about a trip.", writer.questions.single())
    }

    @Test
    fun `F14_1 저장된 스크립트가 있으면 다시 만들지 않는다`() = test { store ->
        store.save(written)

        val viewModel = viewModel(store)
        advanceUntilIdle()

        assertEquals(written, viewModel.uiState.value.script)
        assertEquals(0, writer.questions.size)
    }

    @Test
    fun `F14_1 만들기에 실패하면 이유를 보이고 다시 시도하면 만든다`() = test { store ->
        writer.next = ScriptResult.Failed(AnalysisError.Offline)
        val viewModel = viewModel(store)
        advanceUntilIdle()
        assertEquals(AnalysisError.Offline, viewModel.uiState.value.error)

        viewModel.onIntent(CoachScriptIntent.Retry)
        advanceUntilIdle()

        assertEquals(written, viewModel.uiState.value.script)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `F14_3 한 칸을 고치면 고친 판과 고친 시각을 저장하고 원본은 남긴다`() = test { store ->
        val viewModel = viewModel(store)
        advanceUntilIdle()

        viewModel.onIntent(CoachScriptIntent.Edit(ScriptPart.Body, "Body, mine."))
        advanceUntilIdle()

        val saved = store.script("c1").first()
        assertEquals(written.copy(edited = ScriptParts("Intro.", "Body, mine.", "Closing."), editedAt = now), saved)
    }

    @Test
    fun `F14_3 원본으로 돌리면 고친 판을 지운다`() = test { store ->
        val viewModel = viewModel(store)
        advanceUntilIdle()
        viewModel.onIntent(CoachScriptIntent.Edit(ScriptPart.Intro, "Mine."))
        advanceUntilIdle()

        viewModel.onIntent(CoachScriptIntent.Revert)
        advanceUntilIdle()

        assertEquals(written, store.script("c1").first())
        assertEquals(written.original, viewModel.uiState.value.parts)
    }

    @Test
    fun `F14_4 다듬기는 고친 판과 지금 목표 등급을 보내고 결과를 고친 판으로 저장한다`() = test { store ->
        val viewModel = viewModel(store)
        advanceUntilIdle()
        viewModel.onIntent(CoachScriptIntent.Edit(ScriptPart.Closing, "Bye."))
        advanceUntilIdle()
        writer.polished = ScriptParts("Longer intro.", "Longer body.", "Bye.")

        viewModel.onIntent(CoachScriptIntent.Polish)
        advanceUntilIdle()

        assertEquals(ScriptParts("Intro.", "Body.", "Bye.") to "AL", writer.polishedFrom)
        assertEquals(writer.polished, store.script("c1").first()?.edited)
        assertEquals(writer.polished, viewModel.uiState.value.parts)
    }

    @Test
    fun `F14_4 다듬기에 실패하면 고친 판을 그대로 두고 이유를 보인다`() = test { store ->
        val viewModel = viewModel(store)
        advanceUntilIdle()
        writer.next = ScriptResult.Failed(AnalysisError.Retryable)

        viewModel.onIntent(CoachScriptIntent.Polish)
        advanceUntilIdle()

        assertEquals(written.original, viewModel.uiState.value.parts)
        assertEquals(AnalysisError.Retryable, viewModel.uiState.value.error)
    }

    @Test
    fun `F14_2 지금 목표 등급의 길이를 보인다`() = test { store ->
        val viewModel = viewModel(store)
        advanceUntilIdle()

        assertEquals("AL" to 16..20, viewModel.uiState.value.targetGrade to viewModel.uiState.value.length?.sentences)
    }
}

/** 만들기는 [next] 가 있으면 그 결과를 한 번 내고, 없으면 받은 세션의 표현으로 고정 스크립트를 낸다. */
class FakeScriptWriter : ScriptWriter {
    var next: ScriptResult<Nothing>? = null
    val questions = mutableListOf<String>()
    var polished = ScriptParts("p", "p", "p")
    var polishedFrom: Pair<ScriptParts, String>? = null

    override suspend fun write(session: CoachState, question: String): ScriptResult<CoachScript> {
        next?.let {
            next = null
            return it
        }
        questions += question
        return ScriptResult.Success(CoachScript(session.id, ScriptParts("Intro.", "Body.", "Closing."), session.expressions, edited = null, editedAt = null, promptVersion = "v1", model = "m"))
    }

    override suspend fun polish(edited: ScriptParts, expressions: List<Expression>, question: String, targetGrade: String): ScriptResult<ScriptParts> {
        next?.let {
            next = null
            return it
        }
        polishedFrom = edited to targetGrade
        return ScriptResult.Success(polished)
    }
}
