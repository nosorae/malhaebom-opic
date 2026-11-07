package io.github.nosorae.aiopic.tools.eval

import kotlinx.serialization.json.Json
import java.io.File

/**
 * `GEMINI_API_KEY=... ./gradlew :tools:eval:run` 으로 돌린다. `--args=coach` 를 주면 코치 대본을 돌린다([coachMain]). 모델은 `EVAL_MODEL`(기본 앱의 기본 모델)이다.
 * 한 번 돌리면 회차 등급 7번과 답변 분석 1번, 모두 8번 요청한다. 결과는 evals/reports/ 에 프롬프트 버전별 파일로 남는다.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "coach") return coachMain()
    val key = System.getenv("GEMINI_API_KEY") ?: error("GEMINI_API_KEY 환경 변수가 필요하다")
    val model = System.getenv("EVAL_MODEL") ?: "gemini-3.8-flash"
    val prompts = File("packs/opic-en/src/commonMain/composeResources/files/prompts")
    val answerPrompt = PromptFile.parse(File(prompts, "answer.md").readText())
    val gradePrompt = PromptFile.parse(File(prompts, "session-grade.md").readText())
    val input = Json.decodeFromString<EvalInput>(File("tools/eval/inputs.json").readText())
    val eval = Eval(speak = ::say, ask = { prompt, schema, audio -> askGemini(key, model, prompt, schema, audio) })

    val report = eval.report(input, answerPrompt, gradePrompt, model)

    val out = File("evals/reports/${gradePrompt.version}_${answerPrompt.version}_$model.md")
    out.parentFile.mkdirs()
    out.writeText(report)
    println(out.path)
}

// 대본을 macOS 기기 음성(영어)으로 읽어 AIFF 로 받는다. 사람 녹음 대신 같은 대본을 늘 같은 소리로 만들려고 쓴다.
internal fun say(text: String): ByteArray {
    val file = File.createTempFile("eval", ".aiff")
    try {
        val code = ProcessBuilder("say", "-v", "Samantha", "-o", file.path, text).inheritIO().start().waitFor()
        check(code == 0) { "say 가 $code 로 끝났다" }
        return file.readBytes()
    } finally {
        file.delete()
    }
}
