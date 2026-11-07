# 배포

서명된 Android AAB 와 iOS ipa 를 만들고, 스토어 문구와 스크린샷을 fastlane 으로 올리는 절차다.

## 배포용 빌드

키와 비밀번호는 저장소에 넣지 않는다. 업로드 키는 `~/.config/aiopic-release/` 에 두고(폴더 700, 파일 600) 저장소는 서명 정보 파일의 경로만 받는다.

```bash
mkdir -p ~/.config/aiopic-release && chmod 700 ~/.config/aiopic-release && cd ~/.config/aiopic-release
keytool -genkeypair -keystore upload.jks -storetype PKCS12 -alias upload -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AiOpicSelfStudy"
# 입력한 비밀번호로 서명 정보 파일을 만든다. storeFile 이 상대 경로면 이 파일 옆을 본다.
printf 'storeFile=upload.jks\nstorePassword=<비밀번호>\nkeyAlias=upload\nkeyPassword=<비밀번호>\n' > signing.properties
chmod 600 upload.jks signing.properties
```

경로는 `local.properties` 의 `AIOPIC_RELEASE_SIGNING` 이나 같은 이름의 환경 변수로 넘긴다. 둘 다 없거나 파일이 모자라면 서명 없이 빌드하고 `릴리스 빌드를 서명 없이 만든다: <이유>` 를 찍는다.

| 목적 | 명령 |
|---|---|
| 서명된 AAB | `./gradlew :apps:opic:androidApp:bundleRelease` (결과 `apps/opic/androidApp/build/outputs/bundle/release/androidApp-release.aab`) |
| 서명 확인 | `jarsigner -verify apps/opic/androidApp/build/outputs/bundle/release/androidApp-release.aab` |
| iOS 아카이브 | `xcodebuild -project apps/opic/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS' -archivePath build/release/iosApp.xcarchive -allowProvisioningUpdates archive` |
| App Store 용 ipa | `xcodebuild -exportArchive -archivePath build/release/iosApp.xcarchive -exportOptionsPlist apps/opic/iosApp/ExportOptions.plist -exportPath build/release/ipa -allowProvisioningUpdates` |

iOS 서명 팀은 `Local.xcconfig` 의 `TEAM_ID` 에서 온다. App Store 용 ipa 를 내보내려면 그 팀 계정이 Xcode 설정의 계정에 로그인돼 있어야 배포 프로파일이 자동으로 만들어진다.

App Store Connect 는 같은 빌드 번호를 두 번 받지 않는다. 새 빌드를 올릴 때마다 `apps/opic/iosApp/Configuration/Config.xcconfig` 의 `CURRENT_PROJECT_VERSION` 을 1 올리고(버전 1.0 의 첫 빌드가 1 이다), 사용자에게 보이는 버전이 바뀌면 `MARKETING_VERSION` 도 올린 뒤 커밋한다. 그다음 위 두 명령으로 ipa 를 만들고 TestFlight 로 올린다. 수출 규정 답은 `Info.plist` 의 `ITSAppUsesNonExemptEncryption`(HTTPS 만 써서 `false`)이 대신해서 업로드 뒤에 콘솔에서 따로 답하지 않는다.

```sh
export ASC_API_KEY_PATH=<App Store Connect API 키 JSON 경로>
fastlane ios_build   # build/release/ipa/AiOpicSelfStudy.ipa 를 올린다. 처리 완료는 기다리지 않는다
```

## 스토어 문구 올리기

스토어 등록 문구, App Store 심사 메모, 스크린샷은 `fastlane/` 에 있고 [fastlane](https://docs.fastlane.tools) 으로 올린다. 앱 바이너리는 올리지 않고 심사 제출도 하지 않는다. 계정 키는 저장소 밖에 두고 경로만 환경 변수로 넘긴다.

```sh
export PLAY_JSON_KEY_PATH=<Play Console 서비스 계정 JSON 경로>
export ASC_API_KEY_PATH=<App Store Connect API 키 JSON 경로>   # key_id, issuer_id, key 를 담은 fastlane 형식
export REVIEW_GEMINI_KEY=<심사용 Gemini API 키>               # 심사 메모의 {{REVIEW_GEMINI_KEY}} 에 들어간다
fastlane store                                              # 한 스토어만: fastlane play, fastlane app_store
```

| 무엇 | 위치 |
|---|---|
| Play 문구 (이름, 짧은 설명, 전체 설명) | `fastlane/metadata/android/ko-KR/` |
| App Store 문구 (이름, 부제, 홍보 문구, 설명, 키워드, 지원 주소, 개인정보처리방침 주소) | `fastlane/metadata/ios/ko/`. 설명은 Play 전체 설명을 가리킨다 |
| App Store 심사 메모 | `fastlane/metadata/ios/review_information/notes.txt` |
| Play 스크린샷, 아이콘 | `fastlane/metadata/android/ko-KR/images/` (`phoneScreenshots/` 등) |
| App Store 스크린샷 | `fastlane/screenshots/ios/ko/` |

스크린샷은 지어낸 예시 기록(`StoreDemo.kt`)을 넣은 앱을 찍는다. Play 폰 다섯 장(1080x2160)은 `StoreScreenshotTest` 가 `./gradlew recordRoborazziAndroidHostTest` 로 다시 찍고 `check` 가 비교한다. App Store 다섯 장(6.9형, 1320x2868)은 iPhone 17 Pro Max 시뮬레이터에서 XCUITest 로 찍는다.

```sh
xcrun simctl status_bar 'iPhone 17 Pro Max' override --time 9:41 --batteryState charged --batteryLevel 100
TEST_RUNNER_STORE_SCREENSHOTS_DIR="$PWD/fastlane/screenshots/ios/ko" xcodebuild test -project apps/opic/iosApp/iosApp.xcodeproj \
  -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -only-testing:iosAppUITests/StoreScreenshotsUITests
```

앱 아이콘 원본은 `apps/opic/icon/app-icon.svg` 이고 `python3 apps/opic/icon/make_icon.py` 가 PNG(iOS 기본·다크·틴트, Play 512)를 다시 만든다. 규격과 원본 일치는 `python3 -m unittest scripts/test_store_assets.py` 가 본다.

스크린샷 폴더가 비어 있으면 문구만 올리고 콘솔에 있는 이미지는 건드리지 않는다. 글자 수 제한은 `python3 -m unittest scripts/test_store_metadata.py` 가 키 없이 미리 확인한다. Data safety, 포그라운드 서비스 신고처럼 콘솔에서 직접 넣는 답은 `fastlane/store-declarations.md` 에 있다. 라이브 코치의 실시간 음성 전송에 더할 신고 항목은 그 문서 5절에 표로 있다. 콘솔 입력은 사람이 한다.
