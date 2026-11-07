# live-gemini

Gemini Live API 에 WebSocket 하나로 붙는 클라이언트다. 마이크 음성(16kHz PCM)과 글, 도구 응답을 보내고 모델 음성(24kHz PCM), 양쪽 받아쓰기, 도구 호출, 사용량을 `Flow<LiveEvent>` 로 받는다. 프롬프트와 도구 내용은 모른다. 프로젝트의 다른 모듈을 의존하지 않는다.

```kotlin
val session = LiveSession() // 테스트는 가짜 LiveSocket 을 여는 함수를 준다
session.connect(apiKey, LiveConfig(model, systemInstruction, voice, tools)).collect { event ->
    when (event) {
        is LiveEvent.AudioChunk -> TODO("재생한다")
        is LiveEvent.ToolCall -> session.sendToolResponse(event.id, event.name, TODO("결과"))
        is LiveEvent.Disconnected, is LiveEvent.Failed -> TODO("흐름이 끝났다")
        else -> Unit
    }
}
// 다른 코루틴에서
session.sendAudio(pcm)
```

- `setupComplete` 를 받기 전과 다시 여는 동안 보낸 것, 끊긴 소켓에 보내다 실패한 것은 버린다
- 서버가 곧 끊는다고 알리면(goAway) 마지막 이어 붙이기 핸들로 새 연결을 열고 `Resumed` 를 낸다. 끊긴 뒤 같은 세션으로 다시 `connect` 해도 받아 둔 핸들로 이어 붙인다
- 새 핸들을 받을 때마다 `ResumeHandle(handle)` 을 낸다. 앱이 저장해 두었다가 `connect(apiKey, config, resumeHandle)` 로 넣으면 앱이 죽었다 살아나도 이어 붙인다. 핸들은 2시간 유효해서 나이는 부르는 쪽이 본다
- 핸드셰이크 실패는 `Failed(status, message)`, 연결 뒤 서버가 닫거나 망이 끊기면 `Disconnected(code, reason)` 다. 모양이 다른 메시지 하나는 버리고 다음 메시지를 읽는다
- 키는 주소의 `?key=` 로 보낸다. 이 라이브러리는 주소를 찍지 않고, 엔진 예외 글에 실린 key 쿼리는 지운다
- 대상은 Android, iOS, JVM 이다. 엔진은 Android 와 JVM 이 OkHttp, iOS 가 Darwin 이고, HttpClient 하나를 같이 쓴다

## 테스트

`commonTest` 의 `LiveSessionTest` 가 가짜 `LiveSocket` 으로 보내는 JSON 모양, 서버 메시지 해석, goAway 뒤 재연결, 소켓 예외를 본다. Android 엔진은 `androidHostTest` 가 본다. 실제 서버는 부르지 않는다.
