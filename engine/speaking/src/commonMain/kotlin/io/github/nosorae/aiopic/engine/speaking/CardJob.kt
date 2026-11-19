package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.answer.analysis.JobWork
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.nextDayStart
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 연습 정리 상태(F17.4). 연습 기록의 `cardStatus` 열에 이름으로 둔다. */
enum class CardStatus { Pending, Running, Done, Failed }

/**
 * 모델이 낸 카드 하나(F17.1). 저장 전이라 복습 값이 없다. [repeats] 는 이 연습에서 같은 틀린 유형이 나온 횟수, [blocksMeaning] 은
 * 뜻 전달을 막았는지다. 둘은 고칠 표현을 고를 때만 쓴다(F17.3).
 */
data class CardDraft(
    val kind: CardKind,
    val said: String,
    val better: String,
    val meaning: String,
    val errorType: ErrorType?,
    val context: String,
    val repeats: Int = 1,
    val blocksMeaning: Boolean = false,
)

/** 검사와 상한을 거친 카드와, 상한에 밀려 카드로 만들지 않은 고칠 표현 수(F17.3). */
data class CardBatch(val cards: List<CardDraft>, val skippedErrors: Int)

/** 연습 하나의 대화 전문으로 표현 카드를 만든다(F17.1~F17.3). 구현은 `engine/ai-gemini` 의 Gemini 어댑터다. */
interface CardWriter {
    /** [existing] 은 이미 저장된 카드다. 같은 카드는 다시 만들지 않는다. */
    suspend fun write(transcript: List<Line>, existing: List<ExpressionCard>): AnalysisResult<CardBatch>
}

/**
 * 연습 정리(F17.4). 분석 작업 표가 [run] 을 부르고 다시 보내기와 작업 줄을 맡는다. 대화 전문을 [writer] 로 카드로 만들어 처음 봄 예,
 * 다음 복습 시각은 연습을 끝낸 날의 다음 날 새벽 4시로 저장하고(F17.5), 만든 카드 수, 넘긴 오류 수, 표현을 쓴 횟수를 연습 기록에 적는다(F17.9).
 */
class CardJob(
    private val store: SpeakingStore,
    private val cards: CardStore,
    private val writer: CardWriter,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : JobWork {
    override val backoff = listOf(30.seconds, 2.minutes, 10.minutes)

    override suspend fun run(targetId: String): AnalysisError? {
        val practice = store.practice(targetId) ?: return null
        if (practice.cardStatus == CardStatus.Done) return null
        store.finishCards(targetId, CardStatus.Running)
        val all = cards.cards().first()
        val batch = when (val result = writer.write(practice.transcript, all)) {
            is AnalysisResult.Success -> result.value
            is AnalysisResult.Failed -> return result.error
            is AnalysisResult.Unscorable -> return AnalysisError.Rejected
        }
        val now = clock.now()
        // ponytail: 끝낸 시각은 시작 시각에 대화 시간을 더해 어림한다. 일시정지한 동안 새벽 4시를 넘긴 연습은 하루 이르게 나온다. 연습 기록에 끝낸 시각 열을 더하면 고친다.
        val due = (practice.startedAt + practice.talkTime).nextDayStart(timeZone)
        val saved = batch.cards.map { ExpressionCard(kind = it.kind, said = it.said, better = it.better, meaning = it.meaning, errorType = it.errorType, context = it.context, createdAt = now, dueAt = due, practiceId = targetId) }
        // 연습을 시작할 때 있던 카드와, 연습 중 코치가 들려주고 몰랐던 표현으로 저장한 카드다(F17.9, F16.23).
        val phrases = all.filter { it.createdAt < practice.startedAt || (it.practiceId == targetId && it.kind == CardKind.UnknownExpression) }.map { it.better }
        store.finishCards(
            targetId,
            CardStatus.Done,
            cards = saved,
            cardCount = all.count { it.practiceId == targetId } + batch.cards.size,
            skippedErrors = batch.skippedErrors,
            expressionUses = expressionUses(practice.transcript, phrases),
        )
        return null
    }

    override suspend fun failed(targetId: String, error: AnalysisError) = store.finishCards(targetId, CardStatus.Failed)

    override suspend fun unfinished(): List<String> = store.unfinishedCards()
}

/** 고칠 표현 카드 상한(F17.3). 근거 없는 시작값이라 사용 기록을 보고 바꾼다(ADR 0041). */
private const val MAX_CORRECTIONS = 8
private const val MAX_MORE_NATURAL = 2

/**
 * 저장해도 되는 카드만 남긴다(F17.2). 내가 한 말이 사용자 말에 그대로 있어야 하고(F5.4 와 같은 검사), 이미 있는 카드나 앞 카드와
 * 더 나은 표현이 같으면 버린다. 틀린 유형은 고칠 표현에만 두고, 유형 없는 고칠 표현은 고르지 못하니 버린다(F17.1).
 */
fun checkCards(drafts: List<CardDraft>, transcript: List<Line>, existing: List<ExpressionCard>): List<CardDraft> {
    val said = transcript.filter { it.speaker == Speaker.User }.joinToString(" ") { it.text }.squash()
    val seen = existing.mapTo(mutableSetOf()) { it.better.words() }
    return drafts
        .map { if (it.kind == CardKind.Correction) it else it.copy(errorType = null) }
        .filter { it.said.isNotBlank() && it.better.isNotBlank() && it.said.squash() in said }
        .filter { it.kind != CardKind.Correction || it.errorType != null }
        .filter { seen.add(it.better.words()) }
}

/**
 * 고칠 표현은 같은 유형이 여러 번 나온 것과 뜻을 막은 것을 먼저 8장, 더 자연스럽게는 앞 2장만 남긴다(F17.3).
 * 고르지 못한 고칠 표현 수를 넘긴 오류 수로 돌려준다. 모델이 상한을 지킨다고 믿지 않는다.
 */
fun capCards(cards: List<CardDraft>): CardBatch {
    val (corrections, rest) = cards.partition { it.kind == CardKind.Correction }
    val picked = corrections.sortedWith(compareByDescending<CardDraft> { it.repeats > 1 || it.blocksMeaning }.thenByDescending { it.repeats }).take(MAX_CORRECTIONS)
    val natural = rest.filter { it.kind == CardKind.MoreNatural }.take(MAX_MORE_NATURAL)
    return CardBatch(picked + rest.filter { it.kind != CardKind.MoreNatural || it in natural }, skippedErrors = corrections.size - picked.size)
}

/**
 * 표현을 쓴 횟수(F17.9). 사용자 말에 [phrases] 가 나온 횟수를 낱말 경계로 세고 대소문자와 문장 부호는 보지 않는다.
 * 코치가 그 표현을 말한 바로 다음 사용자 말에서는 하나를 빼서 들려준 직후 따라 말한 것을 세지 않는다.
 */
fun expressionUses(transcript: List<Line>, phrases: Collection<String>): Int {
    val keys = phrases.map { it.words() }.filter { it.isNotEmpty() }.toSet()
    return transcript.withIndex().filter { it.value.speaker == Speaker.User }.sumOf { (i, line) ->
        val coach = transcript.getOrNull(i - 1)?.takeIf { it.speaker == Speaker.Coach }?.text?.words().orEmpty()
        keys.sumOf { key -> (line.text.words().occurrences(key) - if (coach.occurrences(key) > 0) 1 else 0).coerceAtLeast(0) }
    }
}

private val SPACES = Regex("\\s+")
private val NOT_WORD = Regex("[^\\p{L}\\p{N}']+")

private fun String.squash() = trim().replace(SPACES, " ")

/** 소문자 낱말을 빈칸 하나로 이은 글. 같은 카드와 표현을 쓴 횟수를 볼 때 문장 부호와 대소문자를 보지 않는다. */
private fun String.words() = lowercase().replace(NOT_WORD, " ").trim()

/** 낱말 경계에서 [key] 가 나온 횟수. 둘 다 [words] 를 거친 글이다. */
private fun String.occurrences(key: String): Int {
    val text = " $this "
    val target = " $key "
    var n = 0
    var at = text.indexOf(target)
    while (at >= 0) {
        n++
        at = text.indexOf(target, at + target.length - 1)
    }
    return n
}
