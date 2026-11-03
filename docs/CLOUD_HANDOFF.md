# LilacAnime 클라우드 작업 인계

작성일: 2026-11-02 (한국 시간). **이 문서는 작업 상태의 스냅샷이다. 실행 중인 GitHub Actions의 최종 결과는 작업 재개 시 다시 확인한다.**

## 최신 개선: v12 (versionCode 41)

[BUGFIX_V12.md](BUGFIX_V12.md)에 재현한 자막 인코딩/VTT 주석 처리/작업 취소 대기 버그와 검증을 기록했다. JVM 93개 모두 통과, Android 테스트 소스 컴파일 및 기존 서명 arm64 후보 빌드 통과. v11 후보 대신 v12 후보를 사용한다. 실제 화면 전체 검증 완료본은 아니며 아래 v11 픽셀 검사 실패와 미검증 범위는 그대로 남는다.

## 폰트 갱신 정지 및 실제 픽셀 재검사 (2026-11-03 후속)

- 새 `SubtitleFontConfig`는 mpv 생성 전 유효한 기존 설정을 존중하고, 앱 전용 fontconfig 설정과 쓰기 가능한 캐시를 준비한다. `/system/fonts`와 사용자 `mpv_fonts`를 유지한다. 이전 libass 로그에는 설정 부재와 `sub-reload`마다 약 8.7초의 폰트 준비가 기록됐다. 수정 후 같은 에뮬레이터의 갱신 폰트 준비는 약 38ms였다. 실기기 성능 측정은 아니다.
- 짧은 원문 `Line 1`은 실제 화면에 보였지만 640×360에서 밝은 픽셀이 43개여서 기존 >80 조건에 실패했다. 원문 fixture를 충분한 길이로 바꾸고 픽셀 임계값은 유지했다. 폰트 초기화가 ~14초 걸린 로그를 근거로 **미디어 준비** 대기만 60초로 조정했다. 표시/숨김 8초, 배속 구간 10초 제한, 기대 대사 2~7은 유지했다.
- 최종 픽셀 검사: **1개 실행, 1개 실패**. 원문/한국어 정지/숨김/한국어 재생 2·4·6/되감기 프레임은 회수해 확인했다. 기대 2~7 전부의 캡처에는 실패했다. 캡처 비용은 1419/1002/832ms였고 각각 재생 위치가 2466→4666, 4666→6600, 6600→8466ms로 이동했다. 2배속의 500ms 대사를 캡처 중 건너뛰는 사실을 측정했다. 누락된 대사가 실제로 표시됐다고 추정하지 않으며 가속 CI 재검사가 필요하다.
- 폰트 수정 후 JVM 87개 실패/오류/건너뜀 0개와 앱/Android 테스트 APK 빌드를 확인했다. 전체 PlayerScreen UI 전환, 사용자 기기와 실스트림, 유료 API는 미검증이다.
- 최종 arm64 후보: 같은 패키지/기존 인증서/versionCode 40. APK SHA-256 `b7dbc82cbf41a4f7c407c9c3919cac139f9239ac2ab11a531433f901e9df3c7f`. `/workspace/artifacts/LilacAnime-subtitle-check-v11-arm64-candidate.apk`. 아래 이전 후보 해시 대신 이 해시를 사용한다. **완료 배포본으로 표현하지 않는다.**

## 서명 백업 복원과 후보 빌드 (2026-11-03)

- 사용자가 비공개 ZIP 백업을 직접 제공했다. 저장소 밖에 복원한 키에서 내보낸 인증서의 SHA-256이 `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927`과 일치했다. 이전 절의 서명 백업 부재는 해소됐다. 키/비밀번호는 Git에 포함하지 않는다.
- 기존 키로 universal 검사용 APK와 arm64 후보 APK를 빌드했다. `apksigner verify` 통과, 패키지 `com.lilac.anime.subtitlecheck`, versionCode `40`, versionName `0.4.1-alpha-11-subtitle-check`, 후보 ABI `arm64-v8a`를 확인했다.
- arm64 후보 SHA-256: `4f744ea11a69875deda848933cb1372664cb9e152b89f11427007f48367bbbd6`. 로컬 산출물: `/workspace/artifacts/LilacAnime-subtitle-check-v11-arm64-candidate.apk`. **서명과 빌드가 확인된 후보이며 실제 자막 표시 안정화 완료본은 아니다.**
- API 29 x86_64 소프트웨어 에뮬레이터 부팅/직접 APK 설치는 성공했다. KVM이 없으며 Gradle 기기 조회에서 `ShellCommandUnresponsiveException`이 발생했다. 직접 픽셀 검사 실행의 결과는 아래 별도 기록으로 구분한다. 검사 수나 표시 결과를 추정하지 않는다.
- 이번 작업의 JVM 87개/Android 테스트 소스 컴파일 통과 사실은 유지된다. 실제 유료 API 및 전체 PlayerScreen 회차/파일/모드 연속 전환 검증은 남아 있다. v11 브랜치 push는 기존 Actions 검사를 트리거하지만 성공 여부는 실행 결과를 읽고 확인해야 한다.

## 현재 클라우드 후속 작업 (2026-11-02)

- 시작 기준: `fix/subtitle-verified-v11`의 `270607c`. 아래 변경은 현재 작업 공간에 있으며 아직 원격에 반영하지 않았다.
- `PlayerScreen`의 실시간 번역기를 `remember(episode)` 공유 객체에서 번역 `LaunchedEffect`가 소유하는 객체로 옮겼다. 같은 회차에서 파일/제공자/모드를 바꿀 때 취소된 작업의 지연된 `clear()`가 다음 작업의 세션을 지우지 않게 한다. 전체 PlayerScreen 전환 UI를 실행해 재현/해결 확인한 것은 아니다.
- 픽셀 검사는 실패한 단계명, 밝은 픽셀 수, 재생 시각, 자막 문자열/트랙 경로를 assertion에 포함하며 마지막 프레임도 보존한다. 2배속 샘플링 중 PNG 압축/MediaStore 쓰기를 하지 않고 재생 후 저장한다. 기대 대사 집합 `[2,3,4,5,6,7]`, 픽셀 임계값과 대기 제한은 유지했다.
- 현재 인스턴스 검증: JVM 87개, 실패/오류/건너뜀 0개. Android 테스트 소스 컴파일 통과. 실제 영상 픽셀 검사, 전체 PlayerScreen 수명주기 및 유료 API 호출은 아직 검증하지 않았다.
- 최신 Actions API 조회는 `api.github.com` 접근에서 Forbidden이었다. 도메인 허용 초안만 저장했고 적용/조회 성공은 확인하지 않았다. 과거 실패가 해결되었다고 해석하지 않는다.
- SDK/에뮬레이터를 설치했지만 기존 개인 서명 백업은 이 환경에 없고 Library 파일을 읽는 도구도 없다. 기존 키가 복원되기 전 새 키나 APK를 만들지 않았다. 소유자가 비공개 백업을 전달해야 동일 서명 후보와 실제 Android 실행을 이어갈 수 있다.
- 로컬 명령: `/workspace/lilac-setup/gradle :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`. 외부 빌드 설정/로그는 `/workspace/lilac-setup`이며 새 환경에 자동 이전된다고 가정하지 않는다.

## 1. 저장소와 재개 위치

- Android/PC 앱 저장소: https://github.com/wiesunggue/LilacAnime
- 서버 저장소 `wiesunggue/LilacAnime-server`와 혼동하지 않는다. 이번 수정 대상은 위의 **LilacAnime Android 앱**이다.
- `main`: 배포한 v10 소스 및 v10 결함 감사 보고서. 이 인계 문서 추가는 v11 소스 병합을 뜻하지 않는다.
- **진행 중인 수정 브랜치: `fix/subtitle-verified-v11`**
- 앱 및 검사 수정의 최신 기준 커밋: `27ffa6863725a63be9b285d084e16ab079354ad1`.
- 직전 검사 기준 커밋: `45e7422c25e72ba5b842aa23a85aceb0a5620449`.
- `main`만 확인하면 v11 수정이 없으므로 반드시 브랜치와 커밋을 확인한다. 새 작업은 `main`의 이 문서를 먼저 읽고 해당 수정 브랜치를 가져온다.
- 작업 공간의 `/workspace/scratch/...`, `/tmp/...`, 다운로드된 SDK, Gradle 캐시는 새 환경에 있다고 가정하지 않는다. `.gradle`/`build` 생성물은 소스 수정 커밋에 섞지 않는다.

## 2. 사용자의 요청과 필수 원칙

사용자 증상:

1. 한국어 번역 자막이 한마디만 나오거나 반복해서 사라짐. 원문 그대로 재생하면 정상.
2. 일부 자막 검색/다운로드 중 Cancel 또는 뒤로가기가 막힘.
3. 자막 다중 선택 옵션의 의미가 불명확함.
4. 번역 테스트 버튼을 누르면 10~20초 비활성화된 뒤 결과 없이 돌아옴.

요구사항:

- mpv/libass 공식 문서와 실제 번역 프로젝트를 참고하고 기능 검사를 수행한다.
- 테스트 수가 많다는 이유로 실제 표시/실제 API까지 검증했다고 말하지 않는다.
- 기존 설치 앱은 삭제하지 않으며 **패키지와 서명키를 유지**한다.
- 새 개인키를 생성하거나 다른 패키지로 우회하지 않는다.
- 실패를 재현했으면 해결 완료라고 발표하지 않는다. 검사/미검사/실패를 구분한다.

## 3. 배포 이력과 기존 변경

| 버전 | 상태/주요 변경 |
| --- | --- |
| 오래된 v3 | `com.lilac.anime.v3test`. 개인키를 확보하지 못해 현재 검증 앱과 서명이 다르다. 이 APK로 v3를 덮어쓸 수 없다. |
| v9 | `com.lilac.anime.subtitlecheck`, versionCode 38. Android ICU 정규식 오류, 번역 시계 추적, 취소/늦은 응답 처리, 파일별 저장 구분, 자막 한 파일 선택 UI 등을 수정. |
| **v10** | 같은 패키지/키, versionCode 39. 번역 결과를 mpv 기본 자막 트랙으로 표시하고 미번역 구간은 원문으로 유지. 번역 테스트 진행/결과 창, 취소/시간 초과, 탐색 경계 수정. **추가 결함이 남은 배포본**. |
| v11 후보 | versionCode 40 / `0.4.1-alpha-11-subtitle-check`로 CI 구성. 아래 수정/검사를 진행 중. **최종 설치 APK를 아직 전달하지 않았다.** |

마지막 전달 APK: `LilacAnime-subtitle-check-v10-arm64.apk`.
소유자 전용 다운로드: https://chatgpt.com/library?file_id=libfile_844f6e7d5bc48191862dfd57c165392d&account_id=personal

v10의 기존 결과는 JVM 85개/Android 12개 통과였다. 하지만 `sub-text` 문자열과 별도 LibassOverlay 검사를 실제 v10 영상 표면 표시의 증거로 설명한 것은 부정확했다. 버튼 UI도 당시에는 가짜 실행 함수를 사용했다. 세부 한계는 `docs/SUBTITLE_V10_AUDIT.md`를 읽는다.

## 4. 재현한 결함과 v11 수정

| 결함 | v10 재현 | 브랜치 수정 |
| --- | --- | --- |
| F1: 긴 겹침 자막 | 0~60초 간판이 번역 창을 고정해 기본 미리 번역 10개에서도 22초 대사가 누락됨 | 매 폴링 시 현재 활성 대사 전부와 미래 대사를 다시 선택. 긴 대사가 이후 대사의 번역 범위를 가두지 않게 함. |
| F2: 작은 되감기 | 1.1초→0.1초로 1초 되감고 정지하면 앞의 미번역 대사를 요청하지 않음 | 단조 증가 커서와 1.5초 임계값 제거. 작은 되감기도 현재 미번역 대사를 재검토. |

핵심 파일:

- `app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslator.kt`
- `app/src/test/kotlin/com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslatorTest.kt`
- 실제 사용 화면/트랙 연결: `app/src/main/kotlin/com/lilac/anime/player/PlayerScreen.kt`, `MpvPlayerEngine.kt`
- 버튼: `app/src/main/kotlin/com/lilac/anime/ui/settings/TranslationTestPanel.kt`, `AiSettingsScreen.kt`
- HTTP/키 경로: `TranslationManager.kt`, `providers/GeminiTranslator.kt`, `SecureApiKeyStore.kt`

검사용 주입 지점: Gemini 기본 URL과 provider factory에 선택 인수를 추가했다. 기본 앱 동작은 기존 제공자/URL을 사용한다. 실제 AI 설정 화면 검사에서는 Gemini 제공자의 HTTP 목적지만 로컬 MockWebServer로 바꾼다.

## 5. 이번 검사에서 확인한 사실과 현재 실패

### 확인 완료: 커밋 e37f0ee6116e74582b6a4bf0ab106553f4bee47b

검사 실행: https://github.com/wiesunggue/LilacAnime/actions/runs/41458898119

- 결과 XML 직접 확인: **JVM 87개, 실패/오류/건너뜀 0개**. 새 겹침/짧은 되감기 검사가 포함됨.
- Android API 29 x86_64: **15개 중 14개 통과, 1개 실패**.
- 통과한 HTTP 검사 2개는 실제 `AiSettingsScreen`에서 Gemini를 선택하고 버튼을 누른다. `TranslationManager`→Android Keystore에 저장한 테스트 키→실제 Gemini HTTP 클라이언트→로컬 HTTP 서버→JSON 파싱→성공/401 오류 대화상자까지 검사한다.
- 실패: `SubtitleVideoPixelsTest.translatedPixelsSurvivePlaybackSeekAndVisibilityToggle`. 기대한 재생 샘플 대사 집합 `[2,3,4,5,6,7]`에 비해 수집 집합은 `[2,3,5,7]`. 이 결과만으로 자막 렌더러가 누락했다고 단정할 수 없다. 전체 화면 비트맵 복사/PNG 저장이 배속 샘플링을 늦췄을 가능성을 확인해야 한다.
- 캡처 회수도 실패했다. Gradle이 검사 패키지를 제거한 뒤 앱 전용 외부 디렉터리에 접근할 수 없었다.

### 후속 수정/검사

- `45e7422...`: 영상 표면을 640×360으로 제한해 캡처 비용을 줄이고, 되감기 후 문장 확인을 추가. 실행: https://github.com/wiesunggue/LilacAnime/actions/runs/41459508551 . **이 실행도 Android 15개 중 동일한 픽셀 검사 1개가 실패했다. 이번 오류는 8000ms 픽셀 대기 시간 초과다.** 단순히 캡처 크기를 줄여 해결됐다고 판단하지 않는다. 원문/번역/숨김/재생/되감기 중 어느 단계인지 오류에 단계명과 마지막 프레임을 추가해 좁혀야 한다.
- `27ffa686...`: 캡처를 앱 전용 폴더 대신 `MediaStore`의 `Pictures/LilacSubtitleEvidence`에 저장하도록 변경. Gradle 정리 후에도 adb로 회수하도록 CI 경로 수정. **이 변경은 문서 작성 시 재검증 중이며 픽셀 시간 초과를 고친 변경은 아니다.** 실행: https://github.com/wiesunggue/LilacAnime/actions/runs/41460711233
- 최신 실행 목록: https://github.com/wiesunggue/LilacAnime/actions/workflows/build-subtitle-size-fix-apk.yml
- 테스트 파일: `SubtitleVideoPixelsTest.kt`, `TranslationHttpIntegrationTest.kt`, `SubtitleEvidence.kt` (`app/src/androidTest/kotlin/com/lilac/anime/player/`).
- `scripts/emit_subtitle_evidence.py`는 회수된 PNG를 검사 로그에 함께 보존한다.

### 아직 검증했다고 말할 수 없는 범위

- 실제 유료 API 호출 및 원격 모델의 추론/지연/요금 제한.
- 사용자 휴대폰의 API 키, 저장 설정, 네트워크, 실제 애니 스트림.
- S25에서의 실행 및 로컬 LLM 바이너리 추론.
- `PlayerScreen` 전체를 UI 조작하며 회차/파일/모드를 연속 전환하는 통합 수명주기 검사. 현재 영상 검사는 실제 MpvPlayerEngine/TextureView/번역기를 사용하지만 전체 PlayerScreen UI를 띄우지는 않는다.
- `sub-text`는 숨겨진 상태에서도 반환될 수 있다. 문자열만으로 실제 표시를 판정하지 않는다.

## 6. 서명: 다음 작업에서도 반드시 동일하게 유지

| 항목 | 값 |
| --- | --- |
| applicationId | `com.lilac.anime.subtitlecheck` |
| 키 별칭 | `lilac-subtitle-check` |
| 키 파일 | `lilac-subtitle-check.jks` |
| 인증서 SHA-256 | `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927` |
| 마지막 전달 versionCode | `39` (v10) |
| 진행 중 후보 versionCode | `40` (v11), 배포 전 최신 설치본/후속 커밋을 다시 확인 |

상세 복원 지침: `docs/ANDROID_SIGNING.md`.
개인키 백업은 공개 Git에 없다. 소유자 계정의 비공개 파일에 키와 비밀번호가 함께 있다:

- 파일명: `LilacAnime-subtitle-check-private-signing-backup.zip`
- Library ID: `libfile_ff3ae7ee3b8081919934560dacc7ab20`
- 링크: https://chatgpt.com/library?file_id=libfile_ff3ae7ee3b8081919934560dacc7ab20&account_id=personal
- ZIP 구성: `lilac-subtitle-check.jks`, `store-password.txt`, README. 비밀번호 파일은 store/key 비밀번호 모두에 사용한다.

새 환경에 해당 Library 도구/파일 접근 권한이 없으면 소유자가 위 백업을 내려받아 **그 환경에 비공개로 전달해야 한다**. ID/링크를 알고 있다는 것만으로 키 파일이 자동 전송되는 것은 아니다. 이번 환경에서는 이 백업을 다시 복원했지만 그 로컬 경로가 다음 환경에도 존재한다고 가정하지 않는다.

빌드가 읽는 환경변수:

```text
LILAC_KEYSTORE_PATH=<복원한 jks의 절대경로>
LILAC_STORE_PASSWORD=<store-password.txt의 값>
LILAC_KEY_PASSWORD=<같은 비밀번호>
LILAC_KEY_ALIAS=lilac-subtitle-check
```

키/비밀번호/ZIP을 Git이나 공개 로그에 추가하지 않는다. 키가 없으면 복원부터 수행하며 새 키를 생성하지 않는다. 앱 API 키는 이 서명키와 별개이며 사용자 Android Keystore에 암호화되어 있다.

CI에서 정의한 GitHub Actions secret 이름은 `LILAC_ANDROID_KEYSTORE_BASE64`, `LILAC_ANDROID_STORE_PASSWORD`, `LILAC_ANDROID_KEY_PASSWORD`, `LILAC_ANDROID_KEY_ALIAS`, `LILAC_ANDROID_CERT_SHA256`이다. 이름이 코드에 있다는 사실은 secret이 등록되어 있다는 뜻이 아니다. 새 클라우드 환경의 secret과 GitHub Actions secret도 서로 자동 공유된다고 가정하지 않는다.

## 7. 빌드/검사 환경

기준 구성은 `.github/workflows/build-subtitle-size-fix-apk.yml`이다. CI가 루트/모듈 Gradle 파일을 생성하므로 단순히 임의의 기존 Gradle 파일로 빌드하지 않는다.

- JDK 17, Gradle 9.0.0, Android SDK platform/build-tools 36 / 36.0.0.
- AGP 8.13.0, Kotlin/Compose plugin 2.2.0.
- Android 검사는 API 29 x86_64 에뮬레이터, mpv 실제 TextureView. 에뮬레이터 가속 환경 필요.
- ffmpeg로 검은 MPEG4 영상을 생성한다. 자막 픽셀의 음성 대조군은 자막 숨김 상태의 검은 영상이다.
- 검사 APK에는 arm64/x86_64를 포함하고 후보 APK는 arm64로 다시 빌드한다.
- JVM: `gradle :app:testDebugUnitTest --stacktrace --no-daemon`
- Android: `gradle :app:connectedDebugAndroidTest --stacktrace --no-daemon`
- APK: `gradle :app:assembleDebug --stacktrace --no-daemon`
- 참고: 새 환경에서 SDK/Gradle/에뮬레이터가 준비되지 않았으면 위 GitHub Actions로 검사할 수 있다. 로컬 SDK가 없는데 Android 검사를 실행했다고 기록하지 않는다.

CI 아티팩트 `subtitle-ci-candidate-for-local-signing`은 개인키 서명이 보장된 배포본이 아니다. 기존 키가 없는 CI에서는 임시 debug 인증서일 수 있으므로 그대로 사용자에게 설치 파일로 전달하지 않는다. 기존 개인키로 재서명하고 `apksigner verify --print-certs`로 위 지문을 대조한다. 패키지/버전도 확인하며 versionCode를 올린다.

## 8. 다음 담당 작업

1. `AGENTS.md`, 이 문서, `docs/ANDROID_SIGNING.md`, `docs/SUBTITLE_V10_AUDIT.md`를 읽고 `fix/subtitle-verified-v11`의 최신 커밋을 가져온다.
2. 최신 Actions 상태/로그/결과 XML을 확인한다. 실패했던 화면 샘플링 검사와 캡처 회수의 최종 결과를 구분한다.
3. 픽셀 검사가 계속 실패하면 프레임의 실제 자막 부재인지, 캡처로 재생 구간을 놓친 것인지 원인을 측정한다. 기대 대사 수를 낮추거나 실패를 무시해서 통과시키지 않는다.
4. MediaStore 캡처 PNG를 직접 열어 한국어 표시/원문 대체/숨김/재생/되감기 결과를 확인한다. 기존 문자열 검사로 대체하지 않는다.
5. 파일/회차/번역 모드 전환의 전체 플레이어 수명주기 검사를 추가하고 필요한 결함을 수정한다. 가능한 실호출 여부는 별도로 명시한다.
6. 통과한 소스를 정확히 기록한 후 같은 패키지/키로 후보 APK를 생성하고 인증서/버전을 대조한다. 사용자에게 전달하고 `main` 반영/인계 문서도 갱신한다.

## 9. 새 클라우드 작업에 전달할 요청

> wiesunggue/LilacAnime 저장소의 AGENTS.md와 docs/CLOUD_HANDOFF.md부터 읽어줘. 자막 수정은 fix/subtitle-verified-v11 브랜치에서 이어가고, 최신 GitHub Actions 결과부터 확인해. 재현된 겹침/짧은 되감기 결함의 JVM 수정은 통과했지만 Android 픽셀 검사/캡처 회수의 최종 결과와 전체 PlayerScreen 수명주기 검증이 남아 있어. 기존 com.lilac.anime.subtitlecheck 패키지와 docs/ANDROID_SIGNING.md의 개인키를 반드시 재사용해. 키가 없으면 새로 만들지 말고 비공개 백업부터 복원해. 실제 검사 결과와 미검증 범위를 구분하고 동일 서명 APK까지 마무리해.
