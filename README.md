# LilacAnime v16 오류 복구 개선 APK

[**APK 바로 다운로드 (arm64)**](https://github.com/wiesunggue/LilacAnime/raw/refs/heads/downloads/subtitle-check-v16/LilacAnime-v16-arm64.apk)

기존 `com.lilac.anime.subtitlecheck` 앱 위에 업데이트하세요. 앱을 삭제할 필요가 없습니다. versionCode **45**, versionName `0.4.1-alpha-16-subtitle-check`, 기존 패키지·인증서를 유지합니다.

번역 실패 때 요청이 과도하게 늘어나는 문제, 다음 회차에서 번역 모드가 원문으로 돌아가는 문제, 변환 자막에서 미완료 원문이 노출되는 문제를 수정했습니다. 실패 시 완료된 번역을 보존하는 **번역 재시도**와 **원문으로 계속**을 추가하고, 자막을 숨기면 새 번역 요청도 멈춥니다. 모바일 뒤로가기는 열린 플레이어 메뉴를 먼저 닫습니다. v15의 PiP 복귀 수정과 자막 크기·색상 보존도 포함합니다.

## 확인한 범위

- JVM **126개 전부 통과**. 수정 전 인증 오류 요청 300회/네트워크 오류 요청 80회가 발생한 회귀 검사를 포함합니다. 이 횟수는 가상 시계/대체 제공자에서 재현한 값입니다.
- Android **서로 다른 25개 중 최신 결과 24개 통과, 1개 실패**. 여러 실행의 결과이며 한 번의 전체 실행 성공을 뜻하지 않습니다.
- 실제 플레이어의 크기·색상·연속 대사 1~5·되감기, 지연 번역의 대기/재개, 실패/재시도·숨김/재표시, 다음 회차/늦은 응답, HOME→실제 PiP→복귀 **3회**를 확인했습니다.
- 홈·시청기록·내 목록·설정·다운로드 관리 왕복, 자막 검색/다운로드 취소, 실제 HTTP/Keystore를 사용하는 로컬 API 오류·복구 검사도 통과했습니다.
- **2배속에서 500ms 동안 표시되는 대사의 캡처 검사는 아직 실패합니다.** 최종 오류는 `Capture crossed cue 2 at 3800`으로 캡처 중 다음 대사로 진행했습니다. 모든 짧은 대사의 표시가 검증됐다고 주장하지 않습니다.
- KVM 없는 API 29 소프트웨어 에뮬레이터를 사용했습니다. 반복되는 GMS/System UI ANR로 해당 read-only 검사 장치의 Google Play 서비스를 일시 비활성화했습니다. 실제 휴대폰·다른 Android 버전·유료 API·외부 실스트림·Google 서비스/Cast는 미검증입니다.

[자세한 변경 및 검사 기록](https://github.com/wiesunggue/LilacAnime/blob/98b5d4b434824fc5a8a54e267b67aa3f15579097/docs/BUGFIX_V16.md), [검사 메타데이터](validation.json), [실행 로그](results), [화면 캡처](evidence)를 제공합니다. 캡처 대사는 검사 전용 문구입니다. 실패 결과도 로그에 포함했습니다.

사용자 요청대로 Codex 클라우드에서 직접 Gradle로 빌드하고 기존 키로 서명했습니다. GitHub Actions 빌드는 `[skip ci]`로 건너뛰었습니다. GitHub는 소스와 다운로드 배포에 사용합니다.

소스: [98b5d4b434824fc5a8a54e267b67aa3f15579097](https://github.com/wiesunggue/LilacAnime/commit/98b5d4b434824fc5a8a54e267b67aa3f15579097), 브랜치 `fix/subtitle-verified-v11`. 이 다운로드 브랜치는 APK와 검사 결과만 보관합니다.

크기: 95,986,092 bytes. ABI: `arm64-v8a`. 검사한 x86 앱과 배포 APK의 DEX 18개가 동일합니다.

APK SHA-256: `13fe8dc7ebe6b7db391754ace154caa08ea3cfaac66abfe61d3b1d658de35116`

인증서 SHA-256: `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927`
