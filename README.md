# LilacAnime v13 자막 수정 APK

[**APK 바로 다운로드 (arm64)**](https://github.com/wiesunggue/LilacAnime/raw/refs/heads/downloads/subtitle-check-v13/LilacAnime-v13-arm64.apk)

기존 `com.lilac.anime.subtitlecheck` 앱을 삭제하지 않고 업데이트합니다. versionCode **42**, versionName `0.4.1-alpha-13-subtitle-check`입니다. 기존 인증서와 패키지를 유지했습니다.

크기 조절, 번역 시 원본 색상·스타일 보존, 번역 대기 중 원문 유지, 전체 시간표를 보존하는 트랙 교체, 원문/번역 버튼의 화면 밖 스크롤 문제를 수정했습니다.

- 실제 PlayerScreen·TextureView·libmpv 검사 2개 통과: 터치로 크기 변경, 원문↔번역, 원문/번역 초록색, 연속 대사 1~5, 되감기 화면 일치, ASS 크기/색상, 잘못된 파일 선택 시 기존 자막 유지.
- JVM 109개, 실패/오류/건너뜀 0개.
- 모델 응답만 지연된 대체 제공자를 사용했습니다. 실제 유료 API·사용자 기기/실스트림은 미검증입니다. 기존 2배속/500ms 대사 캡처 검사의 소프트웨어 에뮬레이터 실패 기록은 별도이며 전체 CI 성공을 주장하지 않습니다.

[검사 캡처](evidence)에는 번역 100%, 큰 크기, 후속 대사, 되감기 화면을 보관했습니다. 화면의 대사는 검사 전용 문구입니다.

소스: [650c8409611627db4568248998d6dfc7241953c8](https://github.com/wiesunggue/LilacAnime/commit/650c8409611627db4568248998d6dfc7241953c8) (`fix/subtitle-verified-v11`). 이 다운로드 브랜치는 APK와 검사 산출물만 보관합니다.

크기: 95,953,324 bytes. ABI: `arm64-v8a`.

APK SHA-256: `06d181de9e604b255785715b2b4cb2e5bca54a7292de86d47b31e9691a1fb41c`

인증서 SHA-256: `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927`

서명키·비밀번호·백업 ZIP은 이 브랜치나 APK에 포함하지 않습니다.
