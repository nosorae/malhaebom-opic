# duplex-audio

코치 음성을 재생하는 동안에도 마이크를 여는 라이브러리다. 사용자가 코치 말 도중에 끼어들 수 있고, 스피커로 나간 코치 말은 플랫폼 에코 제거로 마이크 소리에서 빠진다. 프로젝트의 다른 모듈을 의존하지 않는다.

| 플랫폼 | 구현 | 방식 |
|---|---|---|
| Android | `AndroidDuplexAudio(context)` | `AudioRecord`(`VOICE_COMMUNICATION`) + `AcousticEchoCanceler`, 재생은 `AudioTrack`(`USAGE_VOICE_COMMUNICATION`) |
| iOS | `IosDuplexAudio()` | `AVAudioEngine` 입력 노드 음성 처리 모드, 세션 `.playAndRecord` + `.voiceChat`, 재생은 `AVAudioPlayerNode` |

```kotlin
audio.mic.collect { chunk ->          // 모으는 동안 마이크와 스피커가 열린다. 16kHz 16bit 모노 40ms 조각
    if (chunk.speech) send(chunk.pcm)  // -40dB(F5.5) 아래 조각은 침묵이라 보내지 않는다
}
audio.play(pcm24k)                    // 24kHz 16bit 모노. 대기열 끝에 넣는다
audio.flush()                         // 끼어들면 재생과 대기열을 버린다
audio.micLevel; audio.playbackLevel   // 목소리 띠용 0~1
```

- 음량은 recorder 와 같게 -50dB~0dB 를 0~1 로 바꾼 값이다
- 마이크 권한(`RECORD_AUDIO`)은 앱이 먼저 받는다. 못 열면 `mic` 이 `IllegalStateException` 으로 끝난다

## 테스트

말소리 판정과 조각 자르기는 `commonTest`, iOS 표본율 변환은 시뮬레이터 테스트로 본다. 에코 제거와 끊김 없는 재생은 실기기에서 사람이 본다.
