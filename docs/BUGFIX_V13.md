# v13 자막 표시·크기·번역 전환 수정 (2026-11-03 UTC)

대상 브랜치는 `fix/subtitle-verified-v11`이다. v12 이후 사용자가 보고한 크기 조절 불가, 번역 시 색상 소실, 번역 대기·후속 대사·되감기 표시 문제를 수정했다.

## 증상과 변경

| 사용자 증상 | 확인한 원인과 변경 |
| --- | --- |
| 자막 크기를 바꿔도 그대로임 | ASS에서는 크기 적용을 즉시 반환하고 있었다. ASS의 스타일·색상은 유지하면서 mpv의 상대 배율로 크기를 바꾼다. 새 트랙을 선택할 때도 마지막 크기와 표시 설정을 다시 적용한다. |
| 번역 버튼을 누르면 색상·정보가 사라짐 | SRT/VTT/SAMI를 고정 ASS 템플릿으로 바꾸던 재생 경로를 제거했다. 원래 파일의 시간표·cue ID·위치 설정·CSS·스타일을 유지하고 번역된 본문만 바꾼다. ASS는 원본 스크립트·스타일·그리기 이벤트를 유지한다. |
| 번역 대기 중 자막이 비거나 오류가 숨겨짐 | 첫 번역이 도착하기 전에는 원본 트랙을 유지한다. 미번역 구간도 원문으로 남기고 진행/실패 상태를 표시한다. 파일 작성·트랙 선택 실패 시에도 현재 표시 중인 자막을 먼저 제거하지 않는다. |
| 번역이 한 번 나온 후 사라짐 | 갱신할 때 선택된 파일을 덮어쓰고 재로딩하는 대신, 전체 시간표를 담은 새 파일을 완성해 선택한 뒤 이전 트랙을 제거한다. 실패한 선택과 늦은 FILE_LOADED 뒤에는 마지막 완성 파일을 재시도한다. 캐시 변경 감지는 충돌 가능한 Map 해시 대신 증가하는 개정 번호를 사용한다. |
| 원문/번역 버튼이 눌리지 않음 | 모바일 메뉴 안에 별도 660dp 스크롤 뷰가 있어 버튼이 실제 화면 밖에 남았다. DropdownMenu의 단일 스크롤을 사용하고 선택 상태와 터치 영역을 명시했다. 실제 화면에서 버튼을 눌러 확인했다. |

늦게 끝난 자막 준비가 사용자의 정지·탐색을 덮어쓰는 문제와 재생 위치 복원이 반복될 수 있던 대기도 수정했다. 정지 중에는 조작 버튼을 자동으로 숨기지 않는다.

파일 전체 번역 경로에서도 SRT/VTT 마크업 소실, ASS 문장 끝 투명도 태그가 문장 앞으로 이동하는 문제, 일본어가 없는 ASS 번역 누락을 수정했다. 유효한 UTF-8은 다른 문자셋으로 추측하지 않는다. ASS 그리기 이벤트는 모델에 보내지 않으며 같은 시간대의 대사로 대체하지 않는다. 이전에 스타일을 잃은 번역 캐시는 새 버전에서 재사용하지 않는다. 원본 파일·사용자 API 키·설정은 삭제하지 않는다.

## 검증 범위

- JVM 결과 XML: **109개, 실패/오류/건너뜀 0개**. 파일 번역의 새 회귀 검사 4개는 수정 전 모두 실패한 것을 확인했고, 수정 후 ASS 그리기 보존까지 포함한 5개가 통과했다. 실시간 전체 파일 보존과 트랙 선택 실패/재시도/정리 검사도 포함한다.
- Android 실제 표시 검사 **2개, 실패 0개** (`OK (2 tests)`, 150.888초). 실제 API 29 x86_64 에뮬레이터의 **PlayerScreen·TextureView·libmpv**를 사용한다. 모델 응답만 지연 가능한 대체 제공자로 고정하며 실제 유료 API를 호출하지 않는다.
- `PlayerSubtitleIntegrationTest.assSizeSliderScalesRealPixelsWithoutReplacingScriptColour`: 원문 및 번역 ASS의 초록색 유지, 100%→180%의 실제 픽셀 높이 증가, 잘못된 파일 선택 실패 뒤 기존 자막 유지.
- `PlayerSubtitleIntegrationTest.realScreenRetainsColourResizesDuringTranslationAndKeepsLaterCues`: 실제 자막 선택 대화상자, 번역 응답 대기 중 원문 표시, 초록색 SRT 번역, 터치로 크기 조절, 원문↔번역 버튼, 보통 속도 후속 대사 1~5, 되감기.
- `sub-text` 갱신만으로 표시됐다고 판정하지 않는다. 정지 상태에서는 원문과 다른 번역 프레임 및 두 번 연속 같은 안정된 프레임을 기다리고, 되감기는 최초 번역 프레임과 픽셀이 같은지 비교한다. 재생 중에는 해당 대사 문자열과 직전 대사에서 바뀐 초록색 픽셀을 함께 확인한다. PNG는 재생 후 저장해 캡처 저장이 다음 대사의 시간을 소비하지 않게 한다. 기대 대사 1~5와 크기 증가 기준은 유지한다.
- Compose의 제어 시계도 계속 진행한다. 네이티브 영상만 진행시키고 이 시계를 멈추면 앱의 `LaunchedEffect` 번역 갱신도 정지하여 검사 자체가 미완료 원문을 남겼다. 최종 검사에서는 영상과 실제 화면의 갱신 루프가 함께 실행된다. 캡처는 `Pictures/LilacSubtitleEvidence`에 남긴다.

실제 유료 API 응답·품질, 사용자 휴대폰과 실스트림은 별도 검증 범위다. v11의 기존 2배속/500ms 대사 캡처 검사는 소프트웨어 에뮬레이터에서 실패한 기록이 남아 있으며 이번 보통 속도 검사로 그 실패나 전체 CI 성공을 대신 주장하지 않는다. 현재 환경은 GitHub Actions API 접근이 차단되어 최신 원격 실행 결과를 확인하지 못했다.

## 설치 파일

- [v13 arm64 APK 다운로드](https://github.com/wiesunggue/LilacAnime/raw/refs/heads/downloads/subtitle-check-v13/LilacAnime-v13-arm64.apk)
- 패키지 `com.lilac.anime.subtitlecheck`, versionCode **42**, versionName `0.4.1-alpha-13-subtitle-check`.
- 기존 인증서 SHA-256: `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927`.
- APK SHA-256: `06d181de9e604b255785715b2b4cb2e5bca54a7292de86d47b31e9691a1fb41c`, 크기 95,953,324 bytes, ABI `arm64-v8a`.
- 같은 패키지와 서명을 유지하므로 기존 subtitle-check 앱을 삭제하지 않고 업데이트한다. 서명키·비밀번호·백업 ZIP은 저장소와 APK에 포함하지 않는다.

로컬 JVM 명령은 `/workspace/lilac-setup/gradle :app:testDebugUnitTest`, 실제 화면 명령은 `adb -s emulator-5556 shell am instrument -w -r -e class com.lilac.anime.player.PlayerSubtitleIntegrationTest com.lilac.anime.subtitlecheck.test/com.lilac.anime.player.SubtitleTestRunner`였다. 클라우드 임시 도구·로그·개인키가 다음 환경으로 자동 복사된다고 가정하지 않는다. CI는 workflow에 정의한 FFmpeg fixture 생성 및 앱/Android 테스트 빌드 구성을 사용한다.
