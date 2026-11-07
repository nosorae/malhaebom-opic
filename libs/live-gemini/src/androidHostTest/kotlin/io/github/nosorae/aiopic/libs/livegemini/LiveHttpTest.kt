package io.github.nosorae.aiopic.libs.livegemini

import io.ktor.client.engine.okhttp.OkHttpEngine
import kotlin.test.Test
import kotlin.test.assertIs

class LiveHttpTest {
    @Test
    fun `Android 는 서비스 파일 순서와 상관없이 WebSocket 을 여는 OkHttp 엔진으로 연다`() {
        assertIs<OkHttpEngine>(liveHttp.engine)
    }
}
