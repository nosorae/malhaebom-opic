---
paths:
  - "settings.gradle.kts"
  - "**/build.gradle.kts"
  - "build-logic/**"
  - "gradle/**"
---

# 모듈과 빌드 설정

다섯 층으로 나눈다. 모듈 경로의 첫 마디가 층이다.

| 층 | 모듈 | 하는 일 | 의존해도 되는 층 |
|---|---|---|---|
| 앱 | `apps/opic/shared`, `apps/opic/androidApp`, `apps/opic/iosApp` | 조립만. DI 그래프, 진입점, 내비게이션 등록 | apps, ui, packs, engine, libs |
| 화면 | `ui/design`, `ui/navigation`, `ui/feature-*` | 팩이 주는 문구와 등급 체계를 받아 그린다. "오픽"이라는 말을 코드에 쓰지 않는다 | ui, engine, libs |
| 시험 팩 | `packs/opic-en` | 서베이, 문항 순서, 체크리스트, 프롬프트, 내장 문항, 등급 | engine, libs |
| 엔진 | `engine/session`, `engine/answer`, `engine/question`, `engine/coach`, `engine/ai-gemini` | 시험 진행 계산과 이어 보기, 답변 분석 포트와 경로 고르기와 출력 검사, 출제, 라이브 코치 세션, Gemini 어댑터 | engine, libs |
| 라이브러리 | `libs/recorder`, `libs/llm-gemini`, `libs/work-queue`, `libs/keyvault`, `libs/tts`, `libs/timer`, `libs/haptics`, `libs/debug`, `libs/debug-noop` | 플랫폼 기능과 외부 API. 폴더째 떼어 쓸 수 있다 | 없음 (프로젝트 모듈 의존 0개) |

`tools/eval` 은 JVM 명령줄 도구이고 engine, packs, libs 만 의존한다.

의존 규칙은 `build-logic` 의 `LayerRules.kt` 에 표 하나로 있고, `./gradlew check` 의 `checkLayers` 가 어긴 의존마다 고칠 방법을 찍고 실패한다. 화면은 시험 팩을 모른다. 팩은 앱이 조립해서 넘긴다.

새 모듈은 `settings.gradle.kts` 에 넣고 층에 맞는 플러그인 id 하나만 건다.

| 플러그인 id | 쓰는 곳 |
|---|---|
| `aiopic.kmp.library` | engine, packs |
| `aiopic.kmp.library.explicit` | libs (explicit API 모드) |
| `aiopic.kmp.compose` | `ui/design`, `ui/navigation`, `apps/opic/shared` |
| `aiopic.kmp.compose.explicit` | 화면을 가진 libs (`libs/debug`) |
| `aiopic.ui.feature` | `ui/feature-*` (디자인, 내비게이션, ViewModel, 코루틴 테스트 의존 포함) |
| `aiopic.android.application` | `apps/opic/androidApp` |

Android namespace 와 Compose 리소스 패키지는 모듈 경로로 만든다. `:ui:feature-exam` 은 `io.github.nosorae.aiopic.ui.featureexam` 이다. SDK, JVM 버전은 `gradle/libs.versions.toml` 에만 쓴다.
