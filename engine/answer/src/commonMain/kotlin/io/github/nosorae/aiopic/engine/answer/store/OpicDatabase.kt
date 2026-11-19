package io.github.nosorae.aiopic.engine.answer.store

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 회차 진행 상태, 답변, 틀림 표시, 본 표시, 코치 세션, 표현 카드, 말하기 연습을 기기에 저장하는 DB. 앱이 플랫폼 빌더(파일 경로, BundledSQLiteDriver)로 만들어 [OpicStore] 에 넘긴다.
 *
 * 행에는 찾을 때 쓰는 열만 두고 모델은 JSON 한 칸에 통째로 넣는다. 모델은 이미 직렬화 왕복이 테스트돼 있고(F3.1 통째 저장),
 * 필드가 늘어도 표를 고치지 않는다.
 */
// ponytail: exportSchema 를 끈다. 이전은 표를 더하는 것뿐이라 손으로 쓴 SQL 하나([OPIC_MIGRATIONS])와 iOS 이전 테스트로 확인한다.
// 표를 고치는 이전이 생기면 room3 Gradle 플러그인으로 스키마를 내보낸다.
@Database(
    entities = [
        SessionRow::class, AnswerRow::class, FlagRow::class, JobRow::class, ResponseRow::class, SeenRow::class,
        CoachSessionRow::class, CoachEventRow::class, CoachScriptRow::class,
        CardRow::class, ReviewDayRow::class, SpeakingSessionRow::class, SpeakingActiveRow::class,
    ],
    version = 5,
    exportSchema = false,
)
@ConstructedBy(OpicDatabaseConstructor::class)
abstract class OpicDatabase : RoomDatabase() {
    internal abstract fun dao(): OpicDao

    /** 코치 세션 표(#262). `engine/coach` 의 저장소만 쓴다. */
    abstract fun coachDao(): CoachDao

    /** 표현 카드와 복습한 날 표(#286). `engine/review` 의 저장소만 쓴다. */
    abstract fun reviewDao(): ReviewDao

    /** 연습 기록과 진행 중인 연습 표(#286). */
    abstract fun speakingDao(): SpeakingDao
}

@Suppress("KotlinNoActualForExpect")
expect object OpicDatabaseConstructor : RoomDatabaseConstructor<OpicDatabase> {
    override fun initialize(): OpicDatabase
}

@Entity(tableName = "exam_session")
internal data class SessionRow(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "answer", indices = [Index("examSessionId")])
internal data class AnswerRow(@PrimaryKey val id: String, val examSessionId: String?, val json: String)

/** 틀림 표시 하나(F8.1). 답변 id 와 교정 순서만 둔다. 교정 내용은 답변 JSON 에 있다. */
@Entity(tableName = "correction_flag", primaryKeys = ["answerId", "index"])
internal data class FlagRow(val answerId: String, val index: Int)

/** 분석 작업 하나(#205). 시각은 epoch 밀리초다. */
@Entity(tableName = "analysis_job", primaryKeys = ["kind", "targetId"])
internal data class JobRow(
    val kind: String,
    val targetId: String,
    val state: String,
    val attempts: Int,
    val nextAttemptAt: Long?,
    val error: String?,
    val updatedAt: Long,
)

/** 분석 작업이 받은 응답 원문, 단계마다 하나(#205). 해석하기 전에 저장해서 다시 부르지 않고 다시 해석한다. */
@Entity(tableName = "analysis_response", primaryKeys = ["kind", "targetId", "stage"])
internal data class ResponseRow(val kind: String, val targetId: String, val stage: String, val text: String)

/**
 * 사용자가 이미 본 것 하나(#198). 축하를 한 번만 하려고 앱을 다시 켜도 남게 둔다. [value] 는 본 값(약점 점수)이고 값이 없는 표시는 1 이다.
 */
@Entity(tableName = "seen_mark")
internal data class SeenRow(@PrimaryKey val key: String, val value: Int)

/**
 * 앱 DB 빌더가 넣는 이전. version 2 는 분석 작업 표와 응답 원문 표를 더한다(#205). version 3 은 본 표시 표를 더한다(#198).
 * version 4 는 코치 세션, 코치 이벤트, 코치 스크립트 표를 더한다(#262).
 * version 5 는 표현 카드, 복습한 날, 연습 기록, 진행 중인 연습 표를 더한다(#286).
 * 기다리던 답변을 작업 표로 옮기는 일은 앱을 켤 때마다 도는 `AnalysisJobs.recover` 가 한다. 이전 뒤 첫 실행도 그 길을 탄다.
 */
val OPIC_MIGRATIONS: Array<Migration> = arrayOf(
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `analysis_job` (`kind` TEXT NOT NULL, `targetId` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                    "`nextAttemptAt` INTEGER, `error` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `targetId`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `analysis_response` (`kind` TEXT NOT NULL, `targetId` TEXT NOT NULL, `stage` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "PRIMARY KEY(`kind`, `targetId`, `stage`))",
            )
        }
    },
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS `seen_mark` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        }
    },
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `coach_session` (`id` TEXT NOT NULL, `questionId` TEXT NOT NULL, `question` TEXT NOT NULL, `targetGrade` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                    "`phase` TEXT NOT NULL, `talkTimeMs` INTEGER NOT NULL, `resumeHandle` TEXT, `handleAt` INTEGER, `status` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `coach_event` (`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `seq`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `coach_script` (`sessionId` TEXT NOT NULL, `intro` TEXT NOT NULL, `body` TEXT NOT NULL, `closing` TEXT NOT NULL, " +
                    "`expressions` TEXT NOT NULL, `edited` TEXT, `editedAt` INTEGER, `promptVersion` TEXT NOT NULL, `model` TEXT NOT NULL, PRIMARY KEY(`sessionId`))",
            )
        }
    },
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `expression_card` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `said` TEXT NOT NULL, `better` TEXT NOT NULL, " +
                    "`meaning` TEXT NOT NULL, `errorType` TEXT, `context` TEXT NOT NULL, `practiceId` TEXT, `createdAt` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                    "`difficulty` REAL, " +
                    "`stability` REAL, `dueAt` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `reviewCount` INTEGER NOT NULL, `againCount` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE TABLE IF NOT EXISTS `review_day` (`day` INTEGER NOT NULL, PRIMARY KEY(`day`))")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `speaking_session` (`id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `talkTimeMs` INTEGER NOT NULL, `userTalkMs` INTEGER NOT NULL, " +
                    "`coachTalkMs` INTEGER NOT NULL, `topic` TEXT NOT NULL, `topicSwitches` INTEGER NOT NULL, `questions` TEXT NOT NULL, `transcript` TEXT NOT NULL, " +
                    "`oralReviews` TEXT NOT NULL, `expressionUses` INTEGER NOT NULL, `cardCount` INTEGER NOT NULL, `skippedErrors` INTEGER NOT NULL, `reportCount` INTEGER NOT NULL, " +
                    "`cardStatus` TEXT NOT NULL, `usage` TEXT NOT NULL, `costUsd` REAL NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `speaking_active` (`id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `plannedMinutes` INTEGER NOT NULL, `audioRoute` TEXT NOT NULL, " +
                    "`phase` TEXT NOT NULL, `status` TEXT NOT NULL, `pausedAt` INTEGER, `talkTimeMs` INTEGER NOT NULL, `userTalkMs` INTEGER NOT NULL, `coachTalkMs` INTEGER NOT NULL, " +
                    "`silenceCount` INTEGER NOT NULL, `reconnects` INTEGER NOT NULL, `topic` TEXT NOT NULL, `topicSwitches` INTEGER NOT NULL, `questions` TEXT NOT NULL, `currentQuestion` INTEGER, " +
                    "`transcript` TEXT NOT NULL, `savedCount` INTEGER NOT NULL, `resumeHandle` TEXT, `handleAt` INTEGER, `usage` TEXT NOT NULL, `reviewCardIds` TEXT NOT NULL, " +
                    "`oralReviews` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
        }
    },
)

/** 코치 세션 하나(#262). 시각은 epoch 밀리초, 대화 시간은 밀리초다. 받아쓰기와 메모는 [CoachEventRow] 에서 다시 계산한다. */
@Entity(tableName = "coach_session")
data class CoachSessionRow(
    @PrimaryKey val id: String,
    val questionId: String,
    val question: String,
    val targetGrade: String,
    val startedAt: Long,
    val phase: String,
    val talkTimeMs: Long,
    val resumeHandle: String?,
    val handleAt: Long?,
    val status: String,
)

/** 코치 세션에 일어난 일 하나(#262). [seq] 는 세션 안 순번이다. 단계와 종류는 [json] 을 다시 계산하면 나온다. */
@Entity(tableName = "coach_event", primaryKeys = ["sessionId", "seq"])
data class CoachEventRow(val sessionId: String, val seq: Int, val json: String)

/** 세션이 만든 스크립트(#262). 원본 세 칸과 표현 목록 JSON, [edited] 는 사용자가 고친 세 칸 JSON 이다. */
@Entity(tableName = "coach_script")
data class CoachScriptRow(
    @PrimaryKey val sessionId: String,
    val intro: String,
    val body: String,
    val closing: String,
    val expressions: String,
    val edited: String?,
    val editedAt: Long?,
    val promptVersion: String,
    val model: String,
)

@Dao
interface CoachDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: CoachSessionRow)

    @Query("SELECT * FROM coach_session WHERE id = :id")
    suspend fun session(id: String): CoachSessionRow?

    /** 세션의 다음 순번으로 붙인다. */
    @Query("INSERT INTO coach_event (sessionId, seq, json) SELECT :sessionId, COALESCE(MAX(seq) + 1, 0), :json FROM coach_event WHERE sessionId = :sessionId")
    suspend fun append(sessionId: String, json: String)

    @Query("SELECT * FROM coach_event WHERE sessionId = :sessionId ORDER BY seq")
    suspend fun events(sessionId: String): List<CoachEventRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: CoachScriptRow)

    @Query("SELECT * FROM coach_script WHERE sessionId = :sessionId")
    fun script(sessionId: String): Flow<CoachScriptRow?>

    /** 모든 세션, 최근 것부터(#267). 기록 탭이 끝난 세션은 스크립트로, 진행 중인 세션은 이어서 대화로 보인다(#280). */
    @Query("SELECT * FROM coach_session ORDER BY startedAt DESC")
    fun sessions(): Flow<List<CoachSessionRow>>

    @Query("SELECT sessionId FROM coach_script")
    fun scriptIds(): Flow<List<String>>
}

/**
 * 표현 카드 하나(#286, design.md 12.3). 시각은 epoch 밀리초다. 종류와 틀린 유형은 enum 이름이다.
 * 난이도와 안정도가 null 이면 FSRS 평가를 한 번도 받지 않은 카드다. [firstSeen] 은 처음 봄이다(F18.4).
 */
@Entity(tableName = "expression_card")
data class CardRow(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val kind: String,
    val said: String,
    val better: String,
    val meaning: String,
    val errorType: String?,
    val context: String,
    val practiceId: String?,
    val createdAt: Long,
    val firstSeen: Boolean,
    val archived: Boolean,
    val difficulty: Double?,
    val stability: Double?,
    val dueAt: Long,
    val lastReviewedAt: Long?,
    val reviewCount: Int,
    val againCount: Int,
)

/** 복습을 한 장 이상 끝낸 날 하나(#286). [day] 는 1970-01-01 을 0 으로 센 로컬 날짜 번호다. */
@Entity(tableName = "review_day")
data class ReviewDayRow(@PrimaryKey val day: Long)

/**
 * 끝난 연습 하나(#286, design.md 12.4). [questions], [transcript], [oralReviews], [usage] 는 JSON 이고
 * [cardStatus] 는 정리 전, 정리 중, 정리 끝, 정리 실패 가운데 하나다.
 */
@Entity(tableName = "speaking_session")
data class SpeakingSessionRow(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val talkTimeMs: Long,
    val userTalkMs: Long,
    val coachTalkMs: Long,
    val topic: String,
    val topicSwitches: Int,
    val questions: String,
    val transcript: String,
    val oralReviews: String,
    /** 표현을 쓴 횟수(F17.9). 정리 작업이 센다. */
    val expressionUses: Int,
    val cardCount: Int,
    /** 카드로 만들지 않고 넘긴 고칠 표현 수(F17.3). */
    val skippedErrors: Int,
    /** 틀린 지적 신고 수(F17.7). */
    val reportCount: Int,
    val cardStatus: String,
    val usage: String,
    val costUsd: Double,
)

/**
 * 진행 중인 연습(#286, F16.16). 표에는 많아야 한 줄이다. 응답마다 통째로 덮어써서 앱 프로세스가 끝나도 마지막 응답까지의 상태와
 * 이어 붙이기 핸들이 남는다. [questions], [transcript], [usage], [reviewCardIds], [oralReviews] 는 JSON 이다.
 */
@Entity(tableName = "speaking_active")
data class SpeakingActiveRow(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val plannedMinutes: Int,
    val audioRoute: String,
    val phase: String,
    val status: String,
    val pausedAt: Long?,
    val talkTimeMs: Long,
    val userTalkMs: Long,
    val coachTalkMs: Long,
    val silenceCount: Int,
    val reconnects: Int,
    val topic: String,
    val topicSwitches: Int,
    val questions: String,
    /** 마지막으로 물은 질문의 [questions] 위치. 없으면 null 이다. */
    val currentQuestion: Int?,
    val transcript: String,
    val savedCount: Int,
    val resumeHandle: String?,
    val handleAt: Long?,
    val usage: String,
    val reviewCardIds: String,
    val oralReviews: String,
)

/** 주제 하나의 연습 기록 요약(F16.5). [questions] 는 그 주제 마지막 연습의 다룬 질문 JSON 이다. */
data class TopicRow(val topic: String, val lastAt: Long, val count: Int, val questions: String)

@Dao
abstract class ReviewDao {
    /** 새 카드(id 0)는 넣고 id 를 돌려준다. 있는 카드는 덮어쓴다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun save(row: CardRow): Long

    @Query("SELECT * FROM expression_card WHERE id = :id")
    abstract suspend fun card(id: Long): CardRow?

    /** 만든 시각이 최근인 것부터. */
    @Query("SELECT * FROM expression_card ORDER BY createdAt DESC, id DESC")
    abstract fun cards(): Flow<List<CardRow>>

    @Query("UPDATE expression_card SET archived = :archived WHERE id = :id")
    abstract suspend fun archive(id: Long, archived: Boolean)

    @Query("DELETE FROM expression_card WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT day FROM review_day ORDER BY day")
    abstract fun days(): Flow<List<Long>>

    @Query("INSERT OR IGNORE INTO review_day (day) VALUES (:day)")
    abstract suspend fun addDay(day: Long)

    @Query("DELETE FROM review_day WHERE day < :day")
    abstract suspend fun deleteDaysBefore(day: Long)

    /** 평가한 카드를 쓰고 [day] 를 복습한 날로 남긴다. [keepFrom] 보다 이른 날은 지운다. */
    @Transaction
    open suspend fun rated(row: CardRow, day: Long, keepFrom: Long) {
        save(row)
        addDay(day)
        deleteDaysBefore(keepFrom)
    }
}

@Dao
abstract class SpeakingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(row: SpeakingActiveRow)

    @Query("DELETE FROM speaking_active")
    abstract suspend fun clearActive()

    /** 진행 중인 연습을 통째로 덮어쓴다. 다른 연습의 줄은 지운다. */
    @Transaction
    open suspend fun saveActive(row: SpeakingActiveRow) {
        clearActive()
        insert(row)
    }

    @Query("SELECT * FROM speaking_active LIMIT 1")
    abstract suspend fun active(): SpeakingActiveRow?

    /** 연습 기록을 넣거나 덮어쓴다(정리 상태, 카드 수가 바뀔 때). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun save(row: SpeakingSessionRow)

    /** 끝내기(F16.11). 진행 중인 줄을 지우고 연습 기록을 넣는 일을 한 트랜잭션으로 한다. */
    @Transaction
    open suspend fun finish(row: SpeakingSessionRow) {
        clearActive()
        save(row)
    }

    /** 최근 것부터. */
    @Query("SELECT * FROM speaking_session ORDER BY startedAt DESC")
    abstract fun sessions(): Flow<List<SpeakingSessionRow>>

    /** 주제마다 하나. SQLite 는 MAX 와 함께 고른 다른 열을 그 MAX 줄에서 가져와서 [TopicRow.questions] 가 마지막 연습의 것이다. */
    @Query("SELECT topic, MAX(startedAt) AS lastAt, COUNT(*) AS count, questions FROM speaking_session GROUP BY topic")
    abstract suspend fun topics(): List<TopicRow>
}

@Dao
internal interface OpicDao {
    // @Upsert 는 충돌 예외의 메시지로 갈래를 정해서, 메시지가 비는 호스트 테스트 환경에서 깨진다. SQL 한 줄로 덮어쓴다.
    @Query("INSERT INTO exam_session (id, json) VALUES (:id, :json) ON CONFLICT(id) DO UPDATE SET json = excluded.json")
    suspend fun saveSession(id: String, json: String)

    @Query("SELECT * FROM exam_session WHERE id = :id")
    fun session(id: String): Flow<SessionRow?>

    @Query("SELECT * FROM exam_session ORDER BY rowid")
    fun sessions(): Flow<List<SessionRow>>

    // 같은 행을 고치므로(REPLACE 처럼 지웠다 넣지 않으므로) rowid 순서가 처음 저장한 순서다.
    @Query(
        "INSERT INTO answer (id, examSessionId, json) VALUES (:id, :examSessionId, :json) " +
            "ON CONFLICT(id) DO UPDATE SET examSessionId = excluded.examSessionId, json = excluded.json",
    )
    suspend fun saveAnswer(id: String, examSessionId: String?, json: String)

    @Query("SELECT * FROM answer WHERE examSessionId = :examSessionId ORDER BY rowid")
    fun answers(examSessionId: String): Flow<List<AnswerRow>>

    @Query("SELECT * FROM answer ORDER BY rowid")
    fun answers(): Flow<List<AnswerRow>>

    @Query("INSERT OR IGNORE INTO correction_flag (answerId, `index`) VALUES (:answerId, :index)")
    suspend fun addFlag(answerId: String, index: Int)

    @Query("DELETE FROM correction_flag WHERE answerId = :answerId AND `index` = :index")
    suspend fun removeFlag(answerId: String, index: Int)

    @Query("SELECT * FROM correction_flag")
    fun flags(): Flow<List<FlagRow>>

    // 같은 행을 고쳐서 rowid 순서가 처음 넣은 순서로 남는다. 작업은 이 순서로 하나씩 보낸다.
    @Query(
        "INSERT INTO analysis_job (kind, targetId, state, attempts, nextAttemptAt, error, updatedAt) VALUES (:kind, :targetId, :state, :attempts, :nextAttemptAt, :error, :updatedAt) " +
            "ON CONFLICT(kind, targetId) DO UPDATE SET state = excluded.state, attempts = excluded.attempts, nextAttemptAt = excluded.nextAttemptAt, error = excluded.error, updatedAt = excluded.updatedAt",
    )
    suspend fun saveJob(kind: String, targetId: String, state: String, attempts: Int, nextAttemptAt: Long?, error: String?, updatedAt: Long)

    @Query("SELECT * FROM analysis_job ORDER BY rowid")
    fun jobs(): Flow<List<JobRow>>

    @Query("INSERT INTO analysis_response (kind, targetId, stage, text) VALUES (:kind, :targetId, :stage, :text) ON CONFLICT(kind, targetId, stage) DO UPDATE SET text = excluded.text")
    suspend fun saveResponse(kind: String, targetId: String, stage: String, text: String)

    @Query("INSERT INTO seen_mark (`key`, value) VALUES (:key, :value) ON CONFLICT(`key`) DO UPDATE SET value = excluded.value")
    suspend fun markSeen(key: String, value: Int)

    @Query("SELECT * FROM seen_mark")
    fun seen(): Flow<List<SeenRow>>

    @Query("SELECT text FROM analysis_response WHERE kind = :kind AND targetId = :targetId AND stage = :stage")
    suspend fun response(kind: String, targetId: String, stage: String): String?
}
