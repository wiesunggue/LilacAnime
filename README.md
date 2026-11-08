# LilacAnime v15 PiP 복귀 수정 APK

[**APK 바로 다운로드 (arm64)**](https://github.com/wiesunggue/LilacAnime/raw/refs/heads/downloads/subtitle-check-v15/LilacAnime-v15-arm64.apk)

기존 `com.lilac.anime.subtitlecheck` 앱을 삭제하지 않고 업데이트합니다. versionCode **44**, versionName `0.4.1-alpha-15-subtitle-check`입니다. 기존 인증서와 패키지를 유지했습니다.

홈 버튼으로 작은 화면(PiP)에 들어갔다가 복귀할 때 살아 있는 플레이어를 해제하던 문제를 수정했습니다. 창 크기 전환과 엔진 종료를 분리해 재생 위치·자막·번역을 유지합니다. v14의 번역 대기·자동 재개 및 크기·색상 보존도 유지합니다.

- 실제 MainActivity의 HOME→PiP→시스템 확대 버튼 복귀 3회, 같은 엔진·재생 위치·한국어 픽셀 유지 검사와 화면 상태 전환 회귀 검사를 통과했습니다.
- 실제 PlayerScreen·TextureView·libmpv 검사 5개 통과: 터치로 크기 변경, 원문↔번역, 원문/번역 초록색, 연속 대사 1~5, 되감기 화면 일치, ASS 크기/색상, 잘못된 파일 선택 시 기존 자막 유지.
- JVM 117개, 실패/오류/건너뜀 0개. 최종 Android 결과는 수정 없는 4개 검사의 전체 실행 결과와 창 위치 조작을 보완한 실제 PiP 검사의 단독 재실행 결과를 합친 것입니다. 초기 PiP 검사 장치 실패를 숨기거나 전체 실행이 한 번에 모두 통과했다고 주장하지 않습니다.
- 모델 응답만 지연된 대체 제공자를 사용했습니다. 다음 대사 응답을 막은 실제 화면에서 대기·정지 위치 유지·한국어 자동 재개를 검사했습니다. 실제 유료 API·사용자 기기/실스트림은 미검증입니다. 기존 2배속/500ms 대사 캡처 검사의 소프트웨어 에뮬레이터 실패 기록은 별도이며 전체 CI 성공을 주장하지 않습니다.

[검사 캡처](evidence)에는 번역 100%, 큰 크기, 후속 대사, 되감기 화면을 보관했습니다. 화면의 대사는 검사 전용 문구입니다.

이 APK는 Codex 클라우드에서 직접 Gradle로 빌드하고 기존 키로 서명했습니다. GitHub Actions 빌드는 실행하지 않도록 커밋에 `[skip ci]`를 사용했습니다. GitHub는 다운로드 배포에 사용합니다.

소스: [29094fa046aac95d9232cde61dac8dd89f9c35c5](https://github.com/wiesunggue/LilacAnime/commit/29094fa046aac95d9232cde61dac8dd89f9c35c5) (`fix/subtitle-verified-v11`). 이 다운로드 브랜치는 APK와 검사 산출물만 보관합니다.

크기: 95,969,708 bytes. ABI: `arm64-v8a`.

APK SHA-256: `5e291cc4d2143c2a6d8b78b8eec9adb202213fd05577a6a9ae2dbaf6e58b8709`

인증서 SHA-256: `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927`

서명키·비밀번호·백업 ZIP은 이 브랜치나 APK에 포함하지 않습니다.
