package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.aigemini.GeminiModels
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.CardStatus
import io.github.nosorae.aiopic.engine.speaking.Line
import io.github.nosorae.aiopic.engine.speaking.Speaker
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 끝낸 연습을 그래프와 같은 조립 함수로 만든 분석 작업 표에 넣으면, 저장한 키와 Gemini 응답으로 카드가 저장되고 연습 기록이 정리 끝이 된다(F17.4). */
class CardsWiringTest {
    private val db = inMemoryDatabase()

    @AfterTest fun close() = db.close()

    @Test
    fun `F17_4 연습 정리 작업을 비우면 저장한 키로 카드를 만들어 저장한다`() = runTest {
        val keyVault = FakeKeyVault().apply { put(GeminiModels.PROVIDER, "test-key") }
        val card = buildJsonObject {
            putJsonArray("cards") {
                addJsonObject {
                    put("kind", "Correction")
                    put("said", "I go there")
                    put("better", "I went there")
                    put("meaning", "거기 갔어")
                    put("errorType", "Tense")
                    put("context", "Last weekend")
                    put("repeats", 1)
                    put("blocksMeaning", false)
                }
            }
        }.toString()
        val body = buildJsonObject { putJsonArray("candidates") { addJsonObject { putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", card) } } } } } }.toString()
        val client = GeminiClient(HttpClient(mockEngine { respond(body, HttpStatusCode.OK) }))
        val settings = AppSettings.inMemory()
        val speaking = SpeakingStore(db)
        val start = Instant.parse("2026-11-19T10:00:00Z")
        speaking.finish(SpeakingState("p1", start, 10.minutes, AudioRoute.Earphones, "travel", transcript = listOf(Line(Speaker.User, "I go there last weekend."))))
        val jobs = testAnalysisJobs(OpicStore(db), FakeWorkQueue(), settings, client, keyVault, cards = cardJob(db, speaking, client, keyVault, settings, kotlin.time.Clock.System, TimeZone.UTC))

        jobs.enqueueCards("p1")
        jobs.drain()

        assertEquals(listOf("I went there"), CardStore(db, TimeZone.UTC).cards().first().map { it.better })
        assertEquals(CardStatus.Done.name to 1, db.speakingDao().sessions().first().single().let { it.cardStatus to it.cardCount })
    }
}
