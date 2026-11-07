# Project RedFlag

> 낯선 웹페이지만 붙잡아 **온디바이스 Gemma**로 몇 초 안에 판정하고,
> 사용자가 원할 때만 **클라우드 에이전트(Claude Opus 5.5)**가 정밀 분석하는 Android 앱.
>
> 전체 설명서: [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md)

⚠️ **연구용 프로토타입입니다.** 피싱을 모두 잡지 못하며(실제 페이지 평가는
[`eval/real/REAL_REPORT.md`](eval/real/REAL_REPORT.md)), 보안 제품을 대신하지 않습니다.

```
링크 탭 / Chrome 이동
   │
   ▼
Gate (android/core) ── 알려진 정상 ──► Chrome (화면 없음)
   │  PSL+private, IDN/homoglyph, 단축 URL, UGC, Safe Browsing v5(선택), Tranco 100k
   ▼ 낯선 도메인
Lite (기기 안, ≤8 s)          격리 WebView → contracts/extract_page.js
   │                          + RDAP/crt.sh (도메인 이름만)
   │                          + Gemma 4 E4B (LiteRT-LM) = 10필드 "증언"
   │                          → 규칙 엔진(contracts/scoring_rules.json)이 점수
   ▼ 사용자가 [정밀 분석]을 누를 때만 (자동 전송 없음)
Cloud (backend/, 백그라운드)  Opus 5.5 + Docker Playwright 샌드박스 + web_search
                              → 같은 규칙 엔진 + 클로킹 비교 → SSE trace
```

## 시작하기

필요한 것은 단계마다 다릅니다. **1단계는 키도 모델도 없이** 됩니다.

| 단계 | 할 수 있는 것 | 필요한 것 |
|---|---|---|
| 1. 테스트 | 게이트·규칙 엔진·파서·백엔드 전체 단위 테스트 | JDK 17, Android SDK (compileSdk 37), [uv](https://docs.astral.sh/uv/) |
| 2. 에뮬레이터 데모 | 앱 화면 흐름, 데모 링크 판정 (Gemma 대신 Fixture 엔진) | + Android 에뮬레이터 (API 31+), Docker |
| 3. 실기기 + Gemma | 실제 온디바이스 판정 | + Android 12+ 폰 (검증 기기: Galaxy S26), 모델 3.66 GB |
| 4. Cloud 정밀 분석 | Opus 에이전트, 샌드박스, 클로킹 비교 | + `ANTHROPIC_API_KEY` |

### 1. 테스트

```bash
cp .env.example .env            # 선택. 비워 둬도 모든 테스트가 통과합니다
cd android && ./gradlew :core:test :app:testDebugUnitTest   # JAVA_HOME이 JDK 17을 가리켜야 합니다
cd ../backend && uv run --group dev pytest -q
```

### 2. 에뮬레이터 데모

```bash
cd backend && DEMO_MODE=1 docker compose up -d --build     # api :8000, demo-pages :8081
cd ../android && ./gradlew :app:installDebug
```

- debug 빌드에 모델이 없으면 자동으로 Fixture 엔진을 씁니다. LiteRT-LM은 에뮬레이터에서 돌지 않습니다.
  release 빌드는 Fixture를 쓰지 않으며, 모델이 없으면 "안전"이 아니라 오류를 표시합니다.
- 데모 링크 목록은 http://localhost:8081 에 있습니다. 도메인 매핑은 `contracts/demo_hosts.json`에 있습니다.
  실제 브랜드를 흉내 내는 데모 페이지 5개는 공개 레포에 없습니다([`demo-pages/README.md`](demo-pages/README.md)).

### 3. 실기기 + Gemma

1. 모델 받기: Hugging Face [`litert-community/gemma-4-E4B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)의
   `gemma-4-E4B-it.litertlm`을 `models/`에 둡니다. [Gemma 이용약관](https://ai.google.dev/gemma/terms)이 적용됩니다.
2. 설치하고 **앱을 한 번 실행**한 다음 모델을 push합니다. `adb shell mkdir`로 폴더를 만들면 앱이 읽지 못합니다.
   ```bash
   adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n dev.redflag.app/.MainActivity
   adb push models/gemma-4-E4B-it.litertlm /sdcard/Android/data/dev.redflag.app/files/models/
   ```
3. 앱의 시작 화면에서 스위치 두 개를 켭니다. 하나는 **기본 브라우저**, 다른 하나는 **접근성(Chrome 주소창 확인)**입니다.
4. 백엔드에 연결하려면 `adb reverse tcp:8000 tcp:8000`을 실행하거나, 앱 설정에서 백엔드 URL을 바꿉니다.

에뮬레이터·기기별 주의사항과 벤치마크 절차는 [`docs/dev-log/MORNING.md`](docs/dev-log/MORNING.md)에 있습니다.

### 4. Cloud 정밀 분석

`.env`에 `ANTHROPIC_API_KEY`를 넣고 `docker compose up`을 다시 실행합니다. 키가 없으면 Opus 단계를 건너뛰고, trace에 그 사실이 표시됩니다.

## API 키와 보안

- `.env`는 gitignore되어 있습니다. 예시는 [`.env.example`](.env.example)에 있습니다.
- **`GOOGLE_CLOUD_API_KEY`는 빌드할 때 APK의 `BuildConfig`에 들어갑니다.** APK를 나눠 주면 키도 함께 나갑니다.
  Google Cloud Console에서 키를 Safe Browsing API로만, Android 앱 `dev.redflag.app`과 서명 인증서 SHA-1로만 쓰도록 제한하세요.
  배포용 APK는 `./gradlew :app:assembleRelease -Predflag.noSbKey`로 키 없이 빌드할 수 있습니다.
- Cloud로 보내는 데이터, 기기에 남는 데이터의 범위는 [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md) 6절 "개인정보와 보안"에 정리되어 있습니다.

## 디렉터리

| 디렉터리 | 내용 |
|---|---|
| `contracts/` | 단일 진실 원천: 스키마, 규칙, 벡터, 브랜드, 게이트 리스트, PSL, 추출 JS, 디자인 토큰 |
| `android/core` | 순수 Kotlin: gate, scoring, Gemma 파서/프롬프트, RiskReport |
| `android/app` | Compose 앱: 링크 핸들러, 접근성 커버, Lite 엔진, Cloud 클라이언트, 디버그 벤치마크 |
| `backend/` | FastAPI + SSE, Opus 루프, 샌드박스, 캐시/예산 (`docker compose`) |
| `demo-pages/` | 로컬 전용 데모 페이지 (가짜 도메인, 폼은 제출되지 않음) |
| `eval/` | 실제 페이지 평가, 실기기 벤치마크 채점 |
| `redflag-gemma-test/` | 맥 Gemma 실험 (baseline) |
| `docs/dev-log/` | 개발 기록: 진행 로그, 인계 노트, 원본 작업 지시 |

## 라이선스

코드는 [Apache License 2.0](LICENSE)을 따릅니다. 함께 포함된 데이터(PSL, Tranco)와 외부 모델·서비스의 조건은
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)에 있습니다.
