# 개발 그래프

`scripts/graph/render.py` 가 `dev-graph.json` 에서 만든다. 손으로 고치지 않는다.

노드 67개: todo 9, doing 0, done 58, blocked 0. 색은 회색 todo, 노랑 doing, 초록 done, 빨강 blocked 이다.

```mermaid
flowchart TD
  subgraph P0["P0 작업 환경 깔기"]
    h0["h0 #1<br/>사람: 초기 프로젝트 셋업"]
    h1["h1 #2<br/>뼈대 정리와 GitHub 올리기"]
    h2["h2 #3<br/>CLAUDE.md, 소스셋별 규칙"]
    h3["h3 #4<br/>검증 명령 하나, 훅, CI"]
    h4["h4 #5<br/>그래프, 이슈, 프로젝트 로드맵"]
    g0["g0 #6<br/>사람: 하네스 확인"]
  end
  subgraph P1["P1 미리 확인"]
    s1["s1 #9<br/>Gemini 오디오 전송과 실제 청구액"]
    s2["s2 #10<br/>기기 음성으로 질문 읽기 품질"]
    s3["s3 #11<br/>iOS 화면 꺼진 채 녹음 이어 가기"]
    s4["s4 #50<br/>Montage 화면 위 네이티브 탭과 뒤로가기"]
    g1["g1 #12<br/>확인: 미리 확인 결과를 설계에 반영"]
  end
  subgraph P2["P2 공통 뼈대 먼저"]
    c1["c1 #13<br/>도메인 모델"]
    c2["c2 #14<br/>인터페이스"]
    c3["c3 #15<br/>오픽 팩 틀: 프롬프트, 스키마, 등급 체계"]
    c5["c5 #51<br/>Montage 토큰, 아이콘, 컴포넌트 이식"]
    c4["c4 #16<br/>본보기 기능: 키 입력"]
    g2["g2 #17<br/>확인: 공통 뼈대 리뷰"]
  end
  subgraph P3["P3 기능 동시 구현"]
    n_state["n-state #18<br/>시험 진행 상태 계산"]
    n_db["n-db #19<br/>Room 3 저장소"]
    n_gemini["n-gemini #20<br/>Gemini 라이브러리, 어댑터, 모델 목록, 출력 검사"]
    n_route["n-route #52<br/>분석 경로 고르기와 두 단계 경로"]
    n_key["n-key #21<br/>키 저장 (Keystore, Keychain), 제공사별"]
    n_tts["n-tts #22<br/>기기 음성, 단조 시계"]
    n_rec_a["n-rec-a #23<br/>Android 조각 녹음과 끼어듦"]
    n_rec_i["n-rec-i #24<br/>iOS 조각 녹음과 끼어듦"]
    n_queue["n-queue #25<br/>분석 대기열 (WorkManager, iOS)"]
    n_quest["n-quest #26<br/>출제, 내장 문항 풀, 파일 불러오기"]
    n_weak["n-weak #27<br/>약점 점수"]
    n_eval["n-eval #28<br/>품질 확인 도구 세 가지"]
  end
  subgraph P4["P4 화면"]
    u_onb["u-onb #29<br/>시작 분기, 키 입력, 음성 전송 동의"]
    u_shell["u-shell #53<br/>앱 틀: 하단 탭 셋과 뒤로가기"]
    u_exam["u-exam #30<br/>모의시험 화면"]
    u_single["u-single #31<br/>한 문항 연습"]
    u_report["u-report #32<br/>리포트와 기록"]
    u_flag["u-flag #33<br/>틀린 교정 표시와 내보내기"]
    u_model["u-model #54<br/>설정: 분석 모델 고르기, 제공사별 키 관리, 화면 모드"]
    g3["g3 #34<br/>확인: 화면을 목업과 대조"]
    n_data["n-data #103<br/>앱 연결: 화면 저장소 구현, 녹음 폴더, 백업 제외"]
    n_di["n-di #104<br/>앱 연결: DI 그래프 조립과 iOS Info.plist"]
    u_copy["u-copy #105<br/>문구: 문항 유형 이름, 모델 표시 이름, 큰 글자 줄바꿈, 서베이 Part 1~3"]
    u_wire["u-wire #106<br/>앱 연결: 모든 경로에 화면 연결, 시작 분기 S0, 스플래시 대기"]
    u_settings2["u-settings2 #107<br/>설정: 동의 철회, 답변 타이머, 문항 파일 불러오기, 내보내기 행, 앱 정보와 라이선스"]
  end
  subgraph P5["P5 마무리"]
    e2e["e2e #35<br/>확인: 에뮬레이터, 시뮬레이터로 한 회"]
    docs["docs #36<br/>ADR 25개, README AI 사용 범위, 모듈별 README, 오류 분석"]
    store["store #37<br/>스토어 준비: 개인정보처리방침, Data safety, 마이크 서비스 신고"]
    g4["g4 #38<br/>완료 보고"]
  end
  subgraph P6["P6 보완"]
    n_json["n-json #118<br/>읽을 수 없는 JSON 응답을 한 번 다시 요청"]
    n_grade["n-grade #119<br/>회차 등급: 마지막 답변 분석 뒤 등급 요청, 저장, 리포트와 기록 표시"]
    n_flag2["n-flag2 #120<br/>틀림 표시를 교정 하나 단위로 약점 계산에서 뺀다"]
    n_gen["n-gen #121<br/>AI 출제 연결, 실패하면 내장 문항으로"]
    n_survey["n-survey #122<br/>서베이 Part 1~3 데이터를 시험 팩에 넣고 앱에서 넘긴다"]
    t_resume["t-resume #123<br/>이어 본 시험에서 40분 타이머가 다시 흐르는지"]
    e2e_ios["e2e-ios #124<br/>iOS XCUITest: 실제 터치로 모의시험 앞부분과 강제 종료 뒤 이어 보기"]
    live["live #125<br/>실제 Gemini 확인: 앱 분석 경로와 품질 확인 첫 보고서"]
    g5["g5 #126<br/>확인: 요구사항 번호마다 테스트가 있는지"]
  end
  subgraph P7["P7 말하기 연습"]
    speaking_spec["speaking-spec #283<br/>말하기 연습 요구사항, 설계, 결정 기록, 개발 그래프"]
    live_config["live-config #284<br/>Live 연결에 말 끝 판정 설정 넘기기"]
    review_fsrs["review-fsrs #285<br/>표현 카드와 FSRS 복습 계산"]
    speaking_db["speaking-db #286<br/>표현 카드, 연습 기록 저장 표"]
    speaking_engine["speaking-engine #287<br/>말하기 연습 세션 상태와 코치 흐름"]
    card_writer["card-writer #289<br/>연습 정리: 대화 전문에서 표현 카드 만들기"]
    expressions_tab["expressions-tab #291<br/>표현 탭과 복습 화면"]
    speaking_service["speaking-service #288<br/>화면이 꺼져도 도는 연습, 이어폰과 알림 조작"]
    speaking_ui["speaking-ui #290<br/>말하기 연습 화면"]
    review_notify["review-notify #292<br/>말하기 연습과 복습 설정, 복습 알림"]
    review_widget["review-widget #293<br/>오늘 복습 위젯"]
    speaker_mode["speaker-mode #294<br/>이어폰 없이 스피커로 하는 연습"]
  end
  h0 --> h1
  h1 --> h2
  h1 --> h3
  h1 --> h4
  h2 --> g0
  h3 --> g0
  h4 --> g0
  g0 --> s1
  g0 --> s2
  g0 --> s3
  g0 --> s4
  s1 --> g1
  s2 --> g1
  s3 --> g1
  s4 --> g1
  g1 --> c1
  c1 --> c2
  c1 --> c3
  g1 --> c5
  c2 --> c4
  c5 --> c4
  c3 --> g2
  c4 --> g2
  g2 --> n_state
  g2 --> n_db
  g2 --> n_gemini
  g2 --> n_route
  g2 --> n_key
  g2 --> n_tts
  g2 --> n_rec_a
  g2 --> n_rec_i
  n_gemini --> n_queue
  n_db --> n_queue
  n_route --> n_queue
  n_gemini --> n_quest
  n_db --> n_quest
  n_db --> n_weak
  n_gemini --> n_eval
  n_key --> u_onb
  n_queue --> u_onb
  g2 --> u_shell
  n_state --> u_exam
  n_rec_a --> u_exam
  n_rec_i --> u_exam
  n_tts --> u_exam
  n_quest --> u_exam
  u_exam --> u_single
  n_weak --> u_single
  n_queue --> u_report
  n_weak --> u_report
  u_report --> u_flag
  n_gemini --> u_model
  n_key --> u_model
  n_route --> u_model
  u_shell --> u_model
  u_onb --> g3
  u_shell --> g3
  u_single --> g3
  u_flag --> g3
  u_model --> g3
  g3 --> n_data
  store --> n_data
  n_data --> n_di
  g3 --> u_copy
  n_di --> u_wire
  u_copy --> u_wire
  u_wire --> u_settings2
  g3 --> e2e
  n_data --> e2e
  n_di --> e2e
  u_copy --> e2e
  u_wire --> e2e
  u_settings2 --> e2e
  g3 --> docs
  n_eval --> docs
  g3 --> store
  e2e --> g4
  docs --> g4
  store --> g4
  g5 --> g4
  e2e --> n_json
  docs --> n_json
  store --> n_json
  e2e --> n_grade
  docs --> n_grade
  store --> n_grade
  n_json --> n_flag2
  n_grade --> n_gen
  n_flag2 --> n_gen
  n_gen --> n_survey
  n_survey --> t_resume
  t_resume --> e2e_ios
  n_grade --> live
  n_gen --> live
  e2e_ios --> live
  n_json --> g5
  n_grade --> g5
  n_flag2 --> g5
  n_gen --> g5
  n_survey --> g5
  t_resume --> g5
  e2e_ios --> g5
  live --> g5
  g5 --> speaking_spec
  speaking_spec --> live_config
  speaking_spec --> review_fsrs
  speaking_spec --> speaking_db
  review_fsrs --> speaking_db
  live_config --> speaking_engine
  review_fsrs --> speaking_engine
  speaking_db --> speaking_engine
  speaking_db --> card_writer
  speaking_engine --> card_writer
  review_fsrs --> expressions_tab
  speaking_db --> expressions_tab
  card_writer --> expressions_tab
  speaking_engine --> speaking_service
  expressions_tab --> speaking_service
  speaking_service --> speaking_ui
  card_writer --> speaking_ui
  expressions_tab --> review_notify
  speaking_ui --> review_notify
  review_notify --> review_widget
  speaking_service --> speaker_mode
  classDef todo fill:#eeeeee,stroke:#999999,color:#000
  class g4,speaking_engine,card_writer,expressions_tab,speaking_service,speaking_ui,review_notify,review_widget,speaker_mode todo
  classDef doing fill:#fff3b0,stroke:#d4a017,color:#000
  classDef done fill:#c8f7c5,stroke:#2e7d32,color:#000
  class h0,h1,h2,h3,h4,g0,s1,s2,s3,s4,g1,c1,c2,c3,c5,c4,g2,n_state,n_db,n_gemini,n_route,n_key,n_tts,n_rec_a,n_rec_i,n_queue,n_quest,n_weak,n_eval,u_onb,u_shell,u_exam,u_single,u_report,u_flag,u_model,g3,n_data,n_di,u_copy,u_wire,u_settings2,e2e,docs,store,n_json,n_grade,n_flag2,n_gen,n_survey,t_resume,e2e_ios,live,g5,speaking_spec,live_config,review_fsrs,speaking_db done
  classDef blocked fill:#ffcdd2,stroke:#c62828,color:#000
```

## 노드 안쪽 루프

모든 노드가 같은 반복을 돈다. `dev-graph.json` 의 `loop` 가 원본이고 노드의 `loop` 가 횟수 상한을 덮어쓴다. done 이 `human` 인 노드는 사람이 확인한다.

```mermaid
flowchart LR
  L_implement["구현"]
  L_done["done 명령"]
  L_review["리뷰"]
  L_merge["병합"]
  L_blocked(["blocked: 사람"])
  L_implement --> L_done
  L_done -->|pass| L_review
  L_done -. 넘으면 .-> L_blocked
  L_done -->|fail 최대 3번| L_implement
  L_review -->|approve| L_merge
  L_review -. 넘으면 .-> L_blocked
  L_review -->|reject 최대 2번| L_implement
```
