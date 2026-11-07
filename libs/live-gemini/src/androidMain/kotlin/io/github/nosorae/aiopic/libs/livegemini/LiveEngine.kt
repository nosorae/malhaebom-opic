package io.github.nosorae.aiopic.libs.livegemini

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual val liveEngine: HttpClientEngineFactory<*> = OkHttp
