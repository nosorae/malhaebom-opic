---
name: architecture
description: 이 앱의 층과 의존, ViewModel(UiState, Intent, onIntent), UseCase, 화면 두 겹, 이동, 일회성 이벤트, 화면 인자, DI(Metro), 결과 타입 규칙. ViewModel, UseCase, 화면, 내비게이션, DI 그래프를 만들거나 고칠 때 먼저 읽는다.
---

# 아키텍처

이 문서의 규칙은 `docs/adr/` 의 결정에서 나왔다. 규칙을 바꾸려면 ADR 을 먼저 더한다.

## 1. 층과 의존

| 층 | 모듈 | 의존해도 되는 층 |
|---|---|---|
| apps | `apps/opic/*` | apps, ui, packs, engine, libs |
| ui | `ui/design`, `ui/navigation`, `ui/feature-*` | ui, engine, libs |
| packs | `packs/opic-en` | engine, libs |
| engine | `engine/session`, `engine/answer`, `engine/question` | engine, libs |
| libs | `libs/*` | 없음 |

- 화면은 시험 팩을 모른다. 문구, 단계 이름, 등급 체계는 엔진의 타입으로 받고, 앱 층이 팩을 넣어 조립한다
- libs 는 프로젝트 모듈을 의존하지 않고 DI 주석도 쓰지 않는다. 평범한 생성자만 둔다. 폴더째 떼어 다른 저장소로 옮길 수 있어야 한다
- 화면 모듈은 api, impl 로 나누지 않는다. 화면 경로 키는 앱 층의 `AppRoute` 한 곳에 둔다(5절)
- 검사는 `./gradlew checkLayers` 가 한다

## 2. ViewModel

```kotlin
class ExamViewModel(
    @Assisted val sessionId: String,
    private val submitAnswerUseCase: SubmitAnswerUseCase,
    private val observeExamUseCase: ObserveExamUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExamUiState())
    val uiState: StateFlow<ExamUiState> = _uiState.asStateFlow()

    fun onIntent(intent: ExamIntent) {
        when (intent) {
            ExamIntent.SubmitAnswer -> submit()
            ExamIntent.NavigationHandled -> _uiState.update { it.copy(navigation = null) }
        }
    }
}
```

- **상태**: `XxxUiState` data class 하나를 `StateFlow` 로 내보낸다. 기본은 `MutableStateFlow` + `update`. 상태가 언제 바뀌는지가 `onIntent` 핸들러에 모두 보인다
- **stateIn 예외**: 저장소 Flow 를 그대로 보여 주는 기록 화면만 `stateIn(viewModelScope, WhileSubscribed(5_000), 초기값)` 을 쓴다. 뒤에서 데이터가 바뀌는 다른 화면(리포트 등)은 `init` 에서 저장소 Flow 를 구독해 `update` 로 옮긴다
- **파생 값**: 다른 필드로 계산되는 값은 UiState 의 계산 프로퍼티로 둔다(`val canGoNext get() = ...`)
- **mutableStateOf 금지**: ViewModel 에 Compose 상태를 두지 않는다
- **Intent**: `sealed interface XxxIntent`, 진입점은 `fun onIntent(intent)` 하나. Intent 하나에 트리거 하나
- **Intent 이름**: 동작 이름으로 짓는다(`StartExam`, `ReplayQuestion`, `SubmitAnswer`). 같은 동작이 버튼, 제스처, 키보드 어디서 와도 이름이 맞다. `Clicked` 는 컴포저블 람다 이름(`onStartClicked`)에만 쓴다
- **생성자**: UseCase 만 주입한다. `Clock`, `CoroutineDispatcher`, 로거는 예외. 변수 이름에 `UseCase` 접미사를 붙인다
- **순수성**: ViewModel, UseCase 시그니처에 Context, Compose 타입, Uri 를 쓰지 않는다. JVM 과 iOS 시뮬레이터 테스트에서 그대로 돌아야 한다

## 3. 일회성 이벤트

화면 이동, 안내 메시지처럼 한 번만 일어날 일은 UiState 의 필드로 두고, 화면이 처리한 뒤 처리 완료 Intent 로 지운다.

```kotlin
data class ExamUiState(
    val navigation: ExamDestination? = null,
    val message: ExamMessage? = null,
)
sealed interface ExamIntent {
    data object NavigationHandled : ExamIntent
    data object MessageShown : ExamIntent
}
```

화면이 처리했다고 알려야 상태가 지워지므로 처리를 빠뜨릴 수 없고, 화면을 돌려도 남는다. Channel, SharedFlow 로 이벤트를 보내지 않는다. 앱 재시작 뒤 복원은 이 필드가 아니라 Room 에 저장된 시험 상태가 맡는다.

## 4. 화면 두 겹

```kotlin
@Composable
fun ExamRoute(viewModel: ExamViewModel, onExit: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.navigation) {
        state.navigation?.let { onExit(); viewModel.onIntent(ExamIntent.NavigationHandled) }
    }
    ExamScreen(state = state, onIntent = viewModel::onIntent)
}

@Composable
fun ExamScreen(state: ExamUiState, onIntent: (ExamIntent) -> Unit) { /* 그리기만 */ }
```

- 바깥(`XxxRoute`)은 ViewModel 을 관측하고 이동, 메시지를 처리한다. 안쪽(`XxxScreen`)은 state 와 `onIntent` 만 받아 그린다. 미리보기와 스크린샷 테스트는 안쪽을 쓴다
- 상태 수집은 `collectAsStateWithLifecycle()`
- `onIntent` 는 화면 최상위만 받는다. 하위 컴포저블에는 이벤트별 람다(`onReplayClicked: () -> Unit`)를 내려보낸다. 작은 컴포저블을 다른 화면에서 다시 쓸 수 있고 역할이 시그니처에 보인다

## 5. 이동

화면 경로 키와 화면 등록은 앱 층(`apps/opic/shared`) 한 곳에 둔다(ADR 0034). 화면 모듈은 키도 플랫폼도 탭도 모른다.

| 맡은 일 | 파일 |
|---|---|
| 키와 경로 표: 화면마다 탭 표시, 뒤로가기 가로채기, iOS 뒤로 버튼, 제목, 탭 순서 | commonMain `AppRoute.kt` (`@Serializable sealed class AppRoute`) |
| 화면 등록: 키마다 그릴 `XxxRoute` 와 ViewModel | commonMain `RouteContent.kt` 의 `when` 하나 |
| Android 앱 틀: Navigation 3 `NavDisplay`, 하단 탭(Material 3 Expressive `ShortNavigationBar`), 예측 뒤로가기 | androidMain `AppShell.kt` |
| iOS 앱 틀: SwiftUI `TabView` 안 탭마다 `NavigationStack`, 뒤로 버튼과 가장자리 스와이프 | `apps/opic/iosApp/iosApp/ContentView.swift` |

- 하단 탭은 모의고사, 연습, 표현, 기록, 설정 다섯이다(`AppRoute.tabs`). 앱을 켜면 모의고사 탭이 먼저 보인다
- Android 는 백스택이 하나다. 하위 화면에서 탭을 숨기므로 탭을 옮기는 건 탭 화면에서뿐이다. 다른 탭은 모의고사 탭 위에 쌓아 뒤로가기로 모의고사 탭에 돌아온다. iOS 는 탭마다 경로를 따로 둔다
- 첫 실행의 키 입력과 동의(`StartKeyEntry`, `StartConsent`)는 탭 없이 혼자 뜨고, 끝나면 시작 분기를 다시 해 백스택을 통째로 바꾼다
- 새 화면은 `AppRoute` 에 case 를 더하고 `RouteContent` 에 가지를 더한다. 빠뜨리면 `when` 이 컴파일되지 않는다. `ui/navigation` 모듈은 비어 있다
- 저장이나 검증이 끝나야 가는 이동(시험 나가기, 시험 끝, 키 확인 성공)만 UiState 의 `navigation` 필드로 한다
- 뒤로가기, 탭 전환, 리포트 열기 같은 단순 이동은 화면이 람다로 바로 처리한다. 앱 층이 그 람다에서 다음 `AppRoute` 를 쌓는다. Intent 쌍이 늘지 않고 시스템 뒤로가기 애니메이션이 유지된다
- 막아야 하는 뒤로가기는 시험 중(`blocksBack`)과 화면이 처리 중일 때(키 확인 중, `onBlockBack`)뿐이다. 녹음 중 연습과 바꾼 답이 있는 서베이는 화면이 뒤로가기를 받아 나갈지 묻는다

## 6. 화면 인자

ViewModel 생성자에 Metro assisted 주입으로 받는다(`ExamViewModel.Factory.create(sessionId)`). 인자는 `AppRoute` 의 속성이고 `RouteContent` 가 키 값으로 ViewModel 을 만든다(`viewModel(key = route.toString())`). Android 는 백스택을 직렬화해 저장하므로 프로세스가 죽어도 인자가 남고, iOS 는 다시 켜면 모의고사 탭부터 보이며 시험은 Room 에서 이어 본다. 진입이 두 번 들어오는 경우를 따로 막을 필요가 없다. `Entered(id)` 같은 진입 Intent 를 만들지 않는다.

## 7. UseCase

```kotlin
class SubmitAnswerUseCase(
    private val answerRepository: AnswerRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(answerId: AnswerId): DomainResult<Unit> = withContext(ioDispatcher) { ... }
}
```

- 인터페이스 없는 클래스 하나가 파일 하나다. `operator fun invoke` 하나만 둔다
- 이름은 현재형 동사 + 대상 + `UseCase` (`SubmitAnswerUseCase`, `ObserveExamUseCase`)
- 한 번 결과면 `suspend`, 여러 번이면 `Flow`
- 경계는 사용자 행동 하나다. ViewModel 이 UseCase 여러 개를 엮게 되면 상위 UseCase 로 묶는다
- main-safe 로 만든다. 무거운 일은 주입받은 디스패처로 옮긴다. 상태를 들지 않는다
- ViewModel 이 저장소를 바로 부르지 않는다

## 8. 결과 타입

```kotlin
sealed interface DomainResult<out T> {
    data class Success<T>(val value: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>
}
```

예상되는 실패(키 틀림, 오프라인, 한도 초과, 평가 불가 녹음)는 `DomainResult.Failure` 로 돌려준다. 예외를 일으켜 호출한 쪽에 넘기지 않는다. `DomainError` 는 sealed 로 두어 화면이 경우마다 문구를 고른다. 외부 라이브러리의 결과 타입을 층 밖으로 내보내지 않는다.

## 9. DI (Metro)

- 컴파일 타임에 그래프를 검사한다. 바인딩이 빠지면 빌드가 실패한다
- 그래프는 플랫폼마다 하나, 앱 층(`apps/opic/shared`)에서 조립한다. iOS 그래프는 Kotlin iosMain 에서 만든다
- libs 클래스는 주석 없이 두고, 앱 층에서 `@Provides` 로 연결한다

## 10. 시험 진행 상태

시험 진행은 engine 의 순수 함수 하나가 계산한다. 지금 상태와 일어난 일(Play, Next, 다시 듣기, 재조정 선택, 앱 꺼짐, 시간 끝)을 받아 다음 상태를 돌려주고, 저장이나 화면 일은 하지 않는다. 바뀐 상태는 통째로 Room 에 저장하고, Room 이 진실 공급원이다. 화면은 Room 의 상태를 구독해 그린다.

## 11. 플랫폼 서비스

녹음, 키 보관, 음성 합성, 진동, 대기열은 commonMain 인터페이스 + 플랫폼 구현 클래스로 둔다. `expect class` 를 쓰지 않는다. Activity 가 필요한 기능은 `@Composable rememberXxx()` 로 노출한다.

분석 작업의 상태는 Room 의 `analysis_job` 표 한 곳에 있다(ADR 0032). 플랫폼 대기열(`WorkQueue`)은 상태를 들지 않고 표를 비우라는 신호만 준다. Android 는 WorkManager 가 연결 조건으로 비우기를 부르고, iOS 는 앱이 앞에 있을 때 비우며 뒤로 가면 이어 돌 시간을 받아 두고 BGProcessingTask 를 예약한다. iOS 의 녹음을 실은 요청(답변 분석, 회차 등급)은 백그라운드 `URLSession` 업로드로 보내 앱을 내려도 끊기지 않는다(#216). 앱 밖 진행 표시와 끝 알림도 이 표에서 계산하고 플랫폼은 띄우기만 한다(ADR 0035).
