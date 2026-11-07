package io.github.nosorae.aiopic.libs.livegemini

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual val liveEngine: HttpClientEngineFactory<*> = Darwin
