package io.github.nosorae.aiopic.engine.answer.store

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** 플랫폼 임시 폴더의 DB 파일 경로. 남아 있던 파일은 지우고 돌려준다. */
expect fun freshDbPath(name: String): String

/**
 * version 4 DB 를 연 사용자 기기에서 말하기 연습 표 넷이 더해지는지 본다(F16.16, F17.8).
 * version 4 의 표는 그때 Room 이 만든 SQL 그대로 쓴다. Android 호스트와 iOS 시뮬레이터에서 같이 돈다.
 */
class Migration4To5Test {
    private val path = freshDbPath("migration-4-5.db")

    private val answer = Answer("a1", Question("q1", "Tell me.", QuestionType("description"), "home"), null, listOf("a1/0.m4a"))

    private fun writeVersion4() {
        val connection = BundledSQLiteDriver().open(path)
        listOf(
            "CREATE TABLE IF NOT EXISTS `exam_session` (`id` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `answer` (`id` TEXT NOT NULL, `examSessionId` TEXT, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_answer_examSessionId` ON `answer` (`examSessionId`)",
            "CREATE TABLE IF NOT EXISTS `correction_flag` (`answerId` TEXT NOT NULL, `index` INTEGER NOT NULL, PRIMARY KEY(`answerId`, `index`))",
            "CREATE TABLE IF NOT EXISTS `analysis_job` (`kind` TEXT NOT NULL, `targetId` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                "`nextAttemptAt` INTEGER, `error` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `targetId`))",
            "CREATE TABLE IF NOT EXISTS `analysis_response` (`kind` TEXT NOT NULL, `targetId` TEXT NOT NULL, `stage` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "PRIMARY KEY(`kind`, `targetId`, `stage`))",
            "CREATE TABLE IF NOT EXISTS `seen_mark` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `coach_session` (`id` TEXT NOT NULL, `questionId` TEXT NOT NULL, `question` TEXT NOT NULL, `targetGrade` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `phase` TEXT NOT NULL, `talkTimeMs` INTEGER NOT NULL, `resumeHandle` TEXT, `handleAt` INTEGER, `status` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `coach_event` (`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `seq`))",
            "CREATE TABLE IF NOT EXISTS `coach_script` (`sessionId` TEXT NOT NULL, `intro` TEXT NOT NULL, `body` TEXT NOT NULL, `closing` TEXT NOT NULL, " +
                "`expressions` TEXT NOT NULL, `edited` TEXT, `editedAt` INTEGER, `promptVersion` TEXT NOT NULL, `model` TEXT NOT NULL, PRIMARY KEY(`sessionId`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ae5a6a68cd5a308061dd8cd5d362cd3a')",
            "INSERT INTO answer (id, examSessionId, json) VALUES ('a1', NULL, '${Json.encodeToString(answer)}')",
            "INSERT INTO coach_session VALUES ('c1', 'q1', 'Tell me.', 'IH', 0, 'Explore', 0, NULL, NULL, 'Active')",
            "PRAGMA user_version = 4",
        ).forEach(connection::execSQL)
        connection.close()
    }

    private fun open() = Room.databaseBuilder<OpicDatabase>(name = path, factory = OpicDatabaseConstructor::initialize)
        .setDriver(BundledSQLiteDriver()).addMigrations(*OPIC_MIGRATIONS).build()

    @Test
    fun `F17_8 version 4 DB 를 옮기면 답변과 코치 세션이 남는다`() = runTest {
        writeVersion4()

        val db = open()

        assertEquals(listOf("a1"), OpicStore(db).answers().first().map { it.id })
        assertEquals("Tell me.", db.coachDao().session("c1")?.question)
        db.close()
    }

    @Test
    fun `F17_8 version 4 DB 를 옮기면 새 표 넷에 새 열까지 쓰고 읽는다`() = runTest {
        writeVersion4()
        val db = open()
        val review = db.reviewDao()
        val speaking = db.speakingDao()

        val cardId = review.save(cardRow())
        review.rated(cardRow(id = cardId, reviewCount = 1), day = 20776, keepFrom = 20721)
        speaking.saveActive(activeRow("p1"))
        speaking.save(sessionRow("p0"))

        assertEquals(listOf(cardRow(id = cardId, reviewCount = 1)), review.cards().first())
        assertEquals(listOf(20776L), review.days().first())
        assertEquals(activeRow("p1"), speaking.active())
        assertEquals(listOf(sessionRow("p0")), speaking.sessions().first())
        db.close()
    }

    @Test
    fun `F16_16 앱 프로세스가 끝나도 진행 중인 연습의 상태와 이어 붙이기 핸들이 남는다`() = runTest {
        writeVersion4()
        open().apply { speakingDao().saveActive(activeRow("p1")) }.close()

        val db = open()

        assertEquals(activeRow("p1"), db.speakingDao().active())
        db.close()
    }
}

internal fun cardRow(id: Long = 0, reviewCount: Int = 0) = CardRow(
    id = id,
    kind = "Formulaic",
    said = "",
    better = "commute",
    meaning = "통근하다",
    errorType = null,
    context = "출근 얘기를 하다가",
    practiceId = "p0",
    createdAt = 1_000,
    firstSeen = false,
    archived = false,
    difficulty = null,
    stability = null,
    dueAt = 1_000,
    lastReviewedAt = null,
    reviewCount = reviewCount,
    againCount = 0,
)

internal fun activeRow(id: String, phase: String = "Talk") = SpeakingActiveRow(
    id = id,
    startedAt = 1_000,
    plannedMinutes = 20,
    audioRoute = "Earphones",
    phase = phase,
    status = "Paused",
    pausedAt = 5_000,
    talkTimeMs = 4_000,
    userTalkMs = 2_500,
    coachTalkMs = 1_200,
    silenceCount = 1,
    reconnects = 2,
    topic = "cooking",
    topicSwitches = 1,
    questions = "[{\"text\":\"What do you cook?\"}]",
    transcript = "[]",
    savedCount = 3,
    resumeHandle = "handle-1",
    handleAt = 4_500,
    usage = "{}",
    reviewCardIds = "[1,2,3]",
    oralReviews = "[{\"cardId\":1}]",
)

internal fun sessionRow(id: String, startedAt: Long = 1_000, topic: String = "home", questions: String = "[]") = SpeakingSessionRow(
    id = id,
    startedAt = startedAt,
    talkTimeMs = 600_000,
    userTalkMs = 400_000,
    coachTalkMs = 150_000,
    topic = topic,
    topicSwitches = 1,
    questions = questions,
    transcript = "[]",
    oralReviews = "[]",
    expressionUses = 2,
    cardCount = 0,
    skippedErrors = 3,
    reportCount = 1,
    cardStatus = "Pending",
    usage = "{}",
    costUsd = 0.12,
)
