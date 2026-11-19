package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.speaking.Cue
import io.github.nosorae.aiopic.engine.speaking.SpeakingProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 말하기 연습 코치 프로필(F16). 지시와 알림 글은 `files/prompts/speaking.md`, 도구 선언은 `speaking-tools.json` 이다.
 * 빈칸은 엔진이 연습을 시작할 때 채운다.
 */
suspend fun speakingProfile(): SpeakingProfile = speakingProfile(
    PromptFile.parse(Res.readBytes("files/prompts/speaking.md").decodeToString()),
    Res.readBytes("files/prompts/speaking-tools.json").decodeToString(),
)

/** 지시 파일은 `# instruction` 절과 알림 이름 절(`# wrap` 등)로 나뉜다. 알림 절이 하나라도 없으면 실패한다. */
internal fun speakingProfile(file: PromptFile, tools: String): SpeakingProfile {
    val sections = file.body.split(Regex("^# ", RegexOption.MULTILINE))
        .filter { it.isNotBlank() }
        .associate { it.substringBefore('\n').trim() to it.substringAfter('\n').trim() }
    return SpeakingProfile(
        instruction = sections.getValue("instruction"),
        tools = Json.parseToJsonElement(tools).jsonArray.map { it.jsonObject },
        cues = Cue.entries.associateWith { sections.getValue(it.name.lowercase()) },
    )
}
