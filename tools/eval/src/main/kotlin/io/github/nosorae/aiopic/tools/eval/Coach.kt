package io.github.nosorae.aiopic.tools.eval

import io.github.nosorae.aiopic.libs.livegemini.LiveConfig
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val PROMPTS = "packs/opic-en/src/commonMain/composeResources/files/prompts"
const val SCENES = "tools/eval/coach-scenes.json"

/**
 * 코치 대본 하나. [phase] 단계 지시로 새로 연결하고 [turns] 를 글로 차례로 보낸다.
 * [notes] 는 엔진 instruction() 이 지시 끝에 붙이는 메모 줄이다(피드백 단계의 리허설 받아쓰기 등).
 */
@Serializable
data class CoachScene(val name: String, val question: String, val grade: String, val phase: String, val turns: List<String>, val notes: List<String> = emptyList())

data class ToolUse(val name: String, val args: JsonObject, val error: String?)

data class CoachTurn(val user: String, val coach: String, val calls: List<ToolUse>)

data class CoachRun(val turns: List<CoachTurn>, val promptTokens: Int, val responseTokens: Int)

/**
 * `GEMINI_API_KEY=... ./gradlew :tools:eval:run --args=coach` 로 돌린다. 모델은 `EVAL_LIVE_MODEL`(기본 앱의 기본 Live 모델)이다.
 * 대본마다 연결 하나를 열고, 결과는 evals/reports/ 에 코치 프롬프트 버전별 파일로 남는다. 키가 없으면 이유를 찍고 건너뛴다.
 */
fun coachMain() {
    val key = System.getenv("GEMINI_API_KEY")
    if (key.isNullOrBlank()) return println("GEMINI_API_KEY 환경 변수가 없어 코치 eval 을 건너뛴다")
    val model = System.getenv("EVAL_LIVE_MODEL") ?: "gemini-3.8-live"
    val file = PromptFile.parse(File(PROMPTS, "coach.md").readText())
    val tools = coachTools(File(PROMPTS, "coach-tools.json").readText())
    val scenes = Json.decodeFromString<List<CoachScene>>(File(SCENES).readText())

    val runs = runBlocking { scenes.map { it to talk(LiveSession(), key, LiveConfig(model, coachInstruction(file, it), VOICE, tools), it.turns) } }

    val out = File("evals/reports/${file.version}_$model.md")
    out.parentFile.mkdirs()
    out.writeText(coachReport(file.version, model, runs))
    println(out.path)
}

fun coachTools(json: String): List<JsonObject> = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

/** 시험 팩 coachProfile 과 같은 규칙으로 공통 절 뒤에 단계 절을 붙이고, 엔진 instruction() 처럼 메모를 끝에 붙인다. */
fun coachInstruction(file: PromptFile, scene: CoachScene): String {
    val sections = file.fill(mapOf("question" to scene.question, "grade" to scene.grade))
        .split(Regex("^# ", RegexOption.MULTILINE))
        .filter { it.isNotBlank() }
        .associate { it.substringBefore('\n').trim() to it.substringAfter('\n').trim() }
    val base = "${sections.getValue("common")}\n\n${sections.getValue(scene.phase)}"
    return if (scene.notes.isEmpty()) base else "$base\n\nNotes so far:\n${scene.notes.joinToString("\n")}"
}

/** 선언의 required 와 enum 으로 도구 인자를 본다. 맞으면 null. 선언과 엔진 toolError 가 맞는지는 시험 팩 테스트가 본다. */
fun argError(declarations: List<JsonObject>, name: String, args: JsonObject): String? {
    val parameters = declarations.firstOrNull { it["name"]?.jsonPrimitive?.content == name }?.let { it["parameters"]?.jsonObject ?: JsonObject(emptyMap()) }
        ?: return "unknown tool $name"
    val properties = parameters["properties"]?.jsonObject.orEmpty()
    val missing = parameters["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filter { args.text(it).isNullOrBlank() }
    val outside = properties.filter { (key, schema) ->
        val enum = schema.jsonObject["enum"]?.jsonArray
        val value = args.text(key)
        enum != null && value != null && JsonPrimitive(value) !in enum
    }.keys
    return when {
        missing.isNotEmpty() -> "missing $missing"
        outside.isNotEmpty() -> "not in enum $outside"
        else -> null
    }
}

fun isEnglishOnly(text: String) = !HANGUL.containsMatchIn(text)

/**
 * 연결을 열고 [turns] 를 하나씩 보내 턴이 끝날 때까지 코치 받아쓰기, 도구 호출, 사용량을 모은다. 도구 응답은 엔진처럼 ok 나 error 로 준다.
 * ponytail: 턴 끝(turnComplete) 뒤에 온 사용량과 말은 다음 턴에 붙고 마지막 턴 뒤의 것은 버린다. 턴별 값이 필요해지면 끝난 뒤 잠깐 더 읽는다.
 */
suspend fun talk(session: LiveSession, apiKey: String, config: LiveConfig, turns: List<String>, turnTimeout: Duration = 60.seconds): CoachRun = coroutineScope {
    val events = Channel<LiveEvent>(Channel.UNLIMITED)
    val connection = launch { session.connect(apiKey, config).collect { events.send(it) } }
    var prompt = 0
    var response = 0

    suspend fun next(): LiveEvent = when (val event = events.receive()) {
        // 실패 글에는 키가 든 주소가 섞일 수 있어 상태만 찍는다.
        is LiveEvent.Failed -> error("Live 연결 실패: 상태 ${event.status}")

        is LiveEvent.Disconnected -> error("Live 연결이 끊겼다: ${event.code} ${event.reason}")

        is LiveEvent.Usage -> event.also {
            prompt += it.promptTokens
            response += it.responseTokens
        }

        else -> event
    }

    withTimeout(turnTimeout) { while (next() != LiveEvent.Connected) Unit }
    val done = turns.map { user ->
        session.sendText(user)
        val coach = StringBuilder()
        val calls = mutableListOf<ToolUse>()
        // 도구 응답을 받은 코치는 이어서 말한다. 그 말을 듣기 전에 다음 말을 보내면 끼어들기가 되어 말이 끊긴다.
        // 단계를 넘기는 호출 뒤에는 앱이 연결을 닫으므로 기다리지 않는다.
        var awaitingReply = false
        withTimeoutOrNull(turnTimeout) {
            while (true) {
                when (val event = next()) {
                    is LiveEvent.OutputTranscript -> {
                        coach.append(event.text)
                        awaitingReply = false
                    }

                    is LiveEvent.ToolCall -> {
                        val error = argError(config.tools, event.name, event.args)
                        calls += ToolUse(event.name, event.args, error)
                        session.sendToolResponse(event.id, event.name, buildJsonObject { if (error == null) put("ok", true) else put("error", error) })
                        awaitingReply = event.name != ADVANCE_PHASE
                    }

                    LiveEvent.TurnComplete -> if (!awaitingReply) break

                    else -> Unit
                }
            }
        }
        CoachTurn(user, coach.toString().trim(), calls)
    }
    connection.cancel()
    CoachRun(done, prompt, response)
}

/** 대본마다 턴 표 하나와 영어만인 코치 턴 수, 통과한 도구 호출 수, 토큰 합을 남긴다. */
fun coachReport(version: String, model: String, runs: List<Pair<CoachScene, CoachRun>>): String = buildList {
    add("# 코치 확인")
    add("")
    add("- 모델: $model")
    add("- 코치 프롬프트: $version")
    runs.forEach { (scene, run) ->
        val calls = run.turns.flatMap { it.calls }
        add("")
        add("## ${scene.name} (${scene.phase}, ${scene.grade})")
        add("")
        add("| 사용자 | 코치 | 영어만 | 도구 호출 |")
        add("|---|---|---|---|")
        run.turns.forEach { turn ->
            val tools = turn.calls.joinToString("<br>") { "${it.name} ${it.args}" + (it.error?.let { e -> " 실패: $e" } ?: "") }
            add("| ${turn.user.cell()} | ${turn.coach.cell()} | ${if (isEnglishOnly(turn.coach)) "예" else "아니오"} | ${tools.cell()} |")
        }
        add("")
        add("영어만: ${run.turns.count { isEnglishOnly(it.coach) }}/${run.turns.size}")
        add("도구 인자 통과: ${calls.count { it.error == null }}/${calls.size}")
        add("토큰: 입력 ${run.promptTokens}, 출력 ${run.responseTokens}")
    }
    add("")
}.joinToString("\n")

// 앱 코치와 같은 목소리(질문 음성과 같은 Kore)로 잰다.
private const val VOICE = "Kore"

private const val ADVANCE_PHASE = "advance_phase"

private val HANGUL = Regex("[\\u1100-\\u11FF\\u3130-\\u318F\\uAC00-\\uD7A3]")

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun String.cell() = replace("|", "\\|").replace("\n", " ")
