# v12 자막 및 작업 종료 개선 (2026-11-03)

## 재현하고 수정한 버그

1. **UTF-8 일본어 손상**: 유효한 UTF-8을 Shift_JIS로도 읽을 수 있을 때 글자 수가 긴 잘못된 디코딩을 선택했다. 엄격한 UTF-8 검증을 먼저 수행한다. NUL이 있는 BOM 없는 UTF-16 시간표는 기존 인코딩 감지 경로에 남긴다.
2. **실제 VTT 대사 누락**: `NOTE:`로 시작하는 timed cue 본문을 주석으로 오인해 버렸다. 주석 판정은 cue 밖의 블록 헤더에서만 수행한다.
3. **VTT 주석 노출**: NOTE 주석 안 예제 시간표가 실제 자막으로 파싱됐다. NOTE/STYLE/REGION 메타데이터 블록을 건너뛰고 실제 cue 본문은 유지한다.
4. **작업 취소 후 무한 대기**: OrderedTaskQueue의 소유 scope가 취소되면 큐 안의 미실행 작업 Deferred가 끝나지 않았다. worker 완료 시 입력을 닫고 미실행 작업의 완료 신호를 취소한다. worker 시작 전 취소도 처리한다. 정상 close는 이미 접수한 작업을 기존 순서대로 끝낸다.

## 검증

- 수정 전 새 회귀 검사 5개 모두 실패한 것을 확인했다(관련 subset 26개 중 5개 실패).
- 수정 후 전체 JVM **93개, 실패/오류/건너뜀 0개**. 새 검사 6개는 위 5개 재현과 정상 close의 순차 처리 검사다.
- 앱 Kotlin 컴파일, Android 테스트 소스 컴파일, arm64 APK 빌드 통과.
- 기존 패키지 `com.lilac.anime.subtitlecheck`, 기존 인증서 SHA-256 `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927` 유지. versionCode **41**, versionName `0.4.1-alpha-12-subtitle-check`.
- APK SHA-256: `a41242081e161d4fc8128fa67d254abd8b7155b70fd65294e79dcdd2deb54037`.
- 실행 명령: `/workspace/lilac-setup/gradle :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` (서명은 별도 비공개 helper에서 기존 키 환경변수로 전달).

## 남은 범위

v12 실제 Android 화면 검사를 실행했다고 주장하지 않는다. v11에서 소프트웨어 에뮬레이터의 연속 6개 대사 캡처는 실패했고, 캡처 비용이 832~1419ms라 2배속의 500ms 대사를 건너뛰는 사실을 측정했다. 이 실패를 건너뛰거나 기대 개수를 낮추지 않았다. 가속 CI의 픽셀 검사, 전체 PlayerScreen UI 전환, 사용자 기기의 실스트림/실제 유료 API는 별도 검증이 필요하다. 이번 수정은 모든 앱 버그를 해결했다는 의미가 아니다.
