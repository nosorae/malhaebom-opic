package io.github.nosorae.aiopic.libs.livegemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** 말 끝 판정과 끼어들기 설정을 setup 에 싣는지 본다(F16.17, F20.5). */
class LiveSetupTest {
    private val config = LiveConfig(model = "test-model", systemInstruction = "Coach the user.", voice = "Kore")
    private val session = LiveSession(open = { error("setup 만 만들고 연결은 열지 않는다") })

    @Test
    fun `말 끝 판정 값을 주지 않으면 지금과 글자 하나 다르지 않은 setup 을 보낸다`() {
        val sent = session.setup(config, handle = null).toString()

        assertEquals(
            """{"setup":{"model":"models/test-model","generationConfig":{"responseModalities":["AUDIO"],"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Kore"}}}},""" +
                """"systemInstruction":{"parts":[{"text":"Coach the user."}]},"inputAudioTranscription":{},"outputAudioTranscription":{},""" +
                """"contextWindowCompression":{"triggerTokens":12000,"slidingWindow":{"targetTokens":6000}},"sessionResumption":{}}}""",
            sent,
        )
    }

    @Test
    fun `침묵 판정 시간과 감도와 끼어들기 끔을 주면 realtimeInputConfig 로 보낸다 F16_17 F20_5`() {
        val withDetection = config.copy(
            silenceDuration = 700.milliseconds,
            startSensitivity = LiveSensitivity.LOW,
            endSensitivity = LiveSensitivity.LOW,
            interruptible = false,
        )

        val sent = session.setup(withDetection, handle = null)["setup"]!!.jsonObject["realtimeInputConfig"]

        assertEquals(
            Json.parseToJsonElement(
                """
                {"automaticActivityDetection":{"startOfSpeechSensitivity":"START_SENSITIVITY_LOW","endOfSpeechSensitivity":"END_SENSITIVITY_LOW","silenceDurationMs":700},
                 "activityHandling":"NO_INTERRUPTION"}
                """,
            ),
            sent,
        )
    }

    @Test
    fun `침묵 판정 시간만 주면 automaticActivityDetection 에 그것만 싣는다`() {
        val sent = session.setup(config.copy(silenceDuration = 1500.milliseconds), handle = null)["setup"]!!.jsonObject["realtimeInputConfig"]

        assertEquals(Json.parseToJsonElement("""{"automaticActivityDetection":{"silenceDurationMs":1500}}"""), sent)
    }
}
