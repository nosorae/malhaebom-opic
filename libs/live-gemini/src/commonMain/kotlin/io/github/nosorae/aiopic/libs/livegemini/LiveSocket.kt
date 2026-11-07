package io.github.nosorae.aiopic.libs.livegemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException

/** 글 프레임을 주고받는 WebSocket 하나. 테스트는 가짜를 넣어 서버 없이 [LiveSession] 을 돌린다. */
public interface LiveSocket {
    public suspend fun send(text: String)

    /** 다음 프레임 글. 연결이 끝나면 null 이고, 그때 [closeReason] 이 채워진다. */
    public suspend fun receive(): String?

    public val closeReason: LiveEvent.Disconnected?

    public suspend fun close()
}

/** 핸드셰이크가 실패했다. [status] 는 서버가 준 HTTP 상태이고 모르면 null 이다. */
public class LiveConnectException(public val status: Int?, message: String) : Exception(message)

/** Ktor 로 연다. Android 는 OkHttp, iOS 는 NSURLSession 엔진이다. */
public fun ktorLiveSocket(http: HttpClient = HttpClient { install(WebSockets) }): suspend (String) -> LiveSocket = { url ->
    val session = try {
        http.webSocketSession(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LiveConnectException(handshakeStatus(e), e.message.orEmpty())
    }
    KtorLiveSocket(session)
}

/**
 * Ktor 는 "expected status code 101 but was 429", OkHttp 는 "Expected HTTP 101 response but was '429 ...'" 로 상태를 글에만 싣는다.
 * ponytail: 예외 글에서 읽는다. 엔진이 상태를 값으로 주면 그 값을 쓴다.
 */
internal fun handshakeStatus(e: Throwable): Int? = generateSequence(e) { it.cause }
    .firstNotNullOfOrNull { Regex("""but was '?(\d{3})""").find(it.message.orEmpty())?.groupValues?.get(1)?.toInt() }

private class KtorLiveSocket(private val session: DefaultClientWebSocketSession) : LiveSocket {
    override var closeReason: LiveEvent.Disconnected? = null

    override suspend fun send(text: String) = session.send(Frame.Text(text))

    override suspend fun receive(): String? {
        // 서버는 JSON 을 바이너리 프레임으로도 보낸다. 둘 다 UTF-8 글로 읽는다.
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> return frame.readText()
                is Frame.Binary -> return frame.data.decodeToString()
                else -> Unit
            }
        }
        val reason = runCatching { session.closeReason.await() }.getOrNull()
        closeReason = LiveEvent.Disconnected(reason?.code?.toInt(), reason?.message.orEmpty())
        return null
    }

    override suspend fun close() = session.close()
}
