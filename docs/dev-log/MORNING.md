# 좋은 아침 — RedFlag 인계

## 한눈에
- 완료: M0, M1, M2, M3, M4, M5, M6, M7 / 부분: - / 미착수: -
- 아침에 바로 할 일 3가지:
  1. 폰 연결 → 아래 "폰 연결 후 순서" 1~4 (설치·모델 push·권한, 10분)
  2. **실기기 벤치마크**(5번) — 실제 지연이 8초 타임아웃 안에 드는지가 오늘의 핵심 숫자. 추측 디코딩 on/off 둘 다 돌린다.
  3. 데모 리허설(6번) — `adb reverse` 후 데모 링크 7개 + Chrome 안 이동 + 클로킹 Cloud 비교

## 완료된 것과 검증 증거
| 마일스톤 | 결과 | 증거 |
|---|---|---|
| M1 계약 | 스키마 3종(2020-12), scoring_rules + 벡터 52, url 벡터 64, 브랜드 38, 게이트 리스트, PSL, 디자인 토큰, fixtures 4 | `backend` pytest `test_contracts.py` |
| M2 core (Kotlin JVM) | 게이트(PSL private·IDN·homoglyph·단축·UGC·SB stub·Tranco 100k), 규칙 엔진, Gemma 파서/프롬프트 | `./gradlew :core:test` 14/14 — url 64/64, scoring 52/52, 어젯밤 raw 363건 파서 패리티 100%, 프롬프트 골든 20/20 바이트 일치 |
| M3 데모 페이지 | 8개(요구 6 + 회색지대 + SB 히트), 클로킹은 webdriver에 따라 다른 화면 | `shots/m3/sheet_cloaking.png` 등 |
| M4 백엔드 | FastAPI SSE, Opus 5.5 루프(클라이언트 툴 + web_search, submit_report), Docker Playwright 샌드박스, 캐시·예산 | pytest 217 passed / 실제 Opus E2E `backend/samples/*.sse` |
| M5 앱 | ROLE_BROWSER 링크 핸들러, Chrome url_bar 감시 + 터치 차단 커버, 격리 WebView 추출, LiteRT-LM 엔진(GPU→CPU 폴백), Cloud SSE, Compose UI(라이트/다크/한국어), 늦은 판정, 디버그 벤치마크 | 에뮬레이터 시나리오 1~7 `shots/m5/`, Roborazzi 12장 `shots/roborazzi/`, `:app:testDebugUnitTest` 18/18 |
| M6 | OpenPhish 60 + Tranco 20만~100만 60 (Docker 샌드박스만) → 맥 Gemma → 규칙 점수 | `eval/real/REAL_REPORT.md` |
| 리뷰 수정 | 독립 리뷰 8건(취소 시 엔진 종료, 모델 없을 때 가짜 safe, 오류 페이지 판정 등) 전부 수정 + 에뮬레이터 회귀 | `PROGRESS.md` "리뷰 수정", `shots/m5/r*.png` |

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (debug, 데모 호스트·벤치마크 포함)

## 미완·막힌 것 (사용자 조치 필요 항목 포함)
- **LiteRT-LM on 에뮬레이터: 실패(예상 범위).** GPU는 init은 되지만 첫 추론에서 `Can not find OpenCL`, CPU는 모델 로딩 중 에뮬레이터가 `hvf_map_safe: FATAL`로 죽음(`shots/m5/emulator_litert_cpu_crash.txt`). 그래서 시나리오는 Fixture 엔진으로 검증했다. → **실제 Gemma 경로는 오늘 폰에서 처음 돈다.** 벤치마크(5번)로 먼저 확인할 것.
- 이 결과로 **런타임 GPU→CPU 폴백**을 넣었다(init 성공 후 첫 추론 실패도 CPU로 재시도).
- 실제 페이지에서 악성 탐지율이 낮다(≥caution 58%, ≥danger 3%, 오탐 0%). 실제 피싱은 등록 오래된/호스팅 도메인이 많아 new_domain이 거의 안 켜지고, 브랜드 사칭 가중치가 danger까지 못 올린다. → 아래 "확인이 필요한 결정"의 가중치 제안 참고.
- 밤사이 API 차단(HTTP 403, 네트워크 문제)으로 세션이 약 3.5시간 멈췄다. M6a 수집은 그 전에 끝나 있었다.
- Safe Browsing은 stub(키 없음). live 클라이언트는 구현돼 있지만 꺼져 있다.

## 폰 연결 후 순서 (복사해서 실행)
레포 루트에서 실행. `PHONE`은 `adb devices`에 나온 시리얼로 바꾼다.

```bash
cd "$(git rev-parse --show-toplevel)"
# 0) 에뮬레이터가 떠 있으면 끈다 (맥 Gemma·에뮬레이터 동시 실행 금지 규칙)
adb devices
export PHONE=<S26 시리얼>

# 1) 자동 차단 off, USB 디버깅 허용 확인
adb -s $PHONE shell getprop ro.product.model

# 2) 설치
adb -s $PHONE install -r -g android/app/build/outputs/apk/debug/app-debug.apk

# 3) 모델 push (3.66 GB, sha256 0b2a8980…52e0)
# ⚠ adb shell mkdir 로 models/ 를 만들면 소유자가 shell 이라 앱이 못 읽는다 ("model missing").
#   앱이 직접 만들게 한 번 실행한 뒤 push 한다. (이미 shell 소유로 있으면 rm -rf 후 아래 순서)
adb -s $PHONE shell am start -n dev.redflag.app/.MainActivity && sleep 2
adb -s $PHONE shell ls -ld /sdcard/Android/data/dev.redflag.app/files/models   # 소유자가 u0_aXXX 여야 함
adb -s $PHONE push models/gemma-4-E4B-it.litertlm /sdcard/Android/data/dev.redflag.app/files/models/

# 4) 권한: 기본 브라우저 역할 + 접근성
adb -s $PHONE shell cmd role add-role-holder android.app.role.BROWSER dev.redflag.app 0
adb -s $PHONE shell settings put secure enabled_accessibility_services dev.redflag.app/dev.redflag.app.accessibility.RedFlagAccessibilityService
adb -s $PHONE shell settings put secure accessibility_enabled 1
adb -s $PHONE shell cmd role get-role-holders android.app.role.BROWSER     # -> dev.redflag.app
adb -s $PHONE shell dumpsys accessibility | grep "RedFlag link guard"      # 바인드 확인
```
⚠ S26에서 확인: **APK를 재설치(`install -r`)하면 접근성 설정이 null로 초기화된다** → 재설치할 때마다 4) 접근성 두 줄을 다시 실행. 값은 있는데 `dumpsys accessibility`에 안 붙으면(강제 종료 후) `settings delete secure enabled_accessibility_services` 후 다시 put. 삼성 기기가 접근성 설정을 되돌리면 설정 > 접근성 > 설치된 앱 > RedFlag 링크 보호 를 손으로 켠다.

### 5) 실기기 벤치마크 (실제 Gemma, 앱과 같은 설정)
```bash
adb -s $PHONE push redflag-gemma-test/cases.jsonl /sdcard/Android/data/dev.redflag.app/files/
adb -s $PHONE push redflag-gemma-test/cases_holdout.jsonl /sdcard/Android/data/dev.redflag.app/files/

# (a) GPU + 추측 디코딩 (앱 기본값)
adb -s $PHONE shell am start -n dev.redflag.app/.debug.DebugBenchmarkActivity \
  --es cases cases.jsonl --ei runs 1 --es tag s26-gpu-spec --ez spec true --es backend gpu
adb -s $PHONE logcat -s RedFlagBench      # "DONE ..." 나오면 Ctrl-C

# (b) GPU, 추측 디코딩 끔 / (c) 홀드아웃
adb -s $PHONE shell am start -n dev.redflag.app/.debug.DebugBenchmarkActivity \
  --es cases cases.jsonl --ei runs 1 --es tag s26-gpu-nospec --ez spec false --es backend gpu
adb -s $PHONE shell am start -n dev.redflag.app/.debug.DebugBenchmarkActivity \
  --es cases cases_holdout.jsonl --ei runs 1 --es tag s26-gpu-spec-holdout --ez spec true --es backend gpu

# 결과 가져오기 + 지표 계산 (run_eval.py 함수 재사용, summarize_results.py와 같은 지표)
mkdir -p eval/device
adb -s $PHONE pull /sdcard/Android/data/dev.redflag.app/files/bench/ eval/device/
backend/.venv/bin/python eval/score_device_results.py eval/device/bench/results-s26-gpu-spec.jsonl
backend/.venv/bin/python eval/score_device_results.py eval/device/bench/results-s26-gpu-nospec.jsonl
backend/.venv/bin/python eval/score_device_results.py eval/device/bench/results-s26-gpu-spec-holdout.jsonl --cases redflag-gemma-test/cases_holdout.jsonl
```
판단 기준: Gemma 지연 + 추출(약 1.5~2.5 s)이 **8 s 안**이면 그대로. 넘으면 (1) 추측 디코딩 on 유지, (2) `LiteEngine.TIMEOUT_MS` 조정을 검토. 넘어도 **늦은 판정**이 있어서 "확인 못 함" 화면이 25 s 안에 실제 판정으로 바뀐다.
`--es backend cpu`로 CPU 수치도 비교할 수 있다.

### 6) 데모 (백엔드 + 데모 페이지를 맥에서, 폰은 adb reverse로)
```bash
cd backend && DEMO_MODE=1 docker compose up -d --build && cd ..
curl -s localhost:8000/healthz            # demo_mode true, anthropic_requests_total 확인
adb -s $PHONE reverse tcp:8000 tcp:8000    # 앱 백엔드 기본값(실기기) = http://localhost:8000
adb -s $PHONE reverse tcp:8081 tcp:8081    # 데모 페이지 = http://localhost:8081
```
체크리스트 (앱 홈 화면의 "데모 링크 열어 보기"로도 실행 가능):
- [ ] 정상: `adb -s $PHONE shell am start -a android.intent.action.VIEW -d https://www.naver.com/` → 화면 없이 Chrome
- [ ] 은행 사칭: `... -d "https://dbs-secure-verify.top/login?ref=sms"` → 대기 → 차단(critical)
- [ ] SB: `... -d https://testsafebrowsing.appspot.com/s/phishing.html` → 즉시 차단
- [ ] 인젝션: `... -d https://secure-account-review.net/` → "AI 검사를 속이려는 문구" 플래그
- [ ] 가짜 쇼핑몰: `... -d https://nike-outlet-sale.shop/`
- [ ] 한국 택배 스미싱: `... -d https://cjlogis-kr.co/`
- [ ] 회색지대: `... -d https://soomstudio.kr/` → 주의 → (Auto) Cloud trace 실시간 → Cloud 판정
- [ ] 클로킹: `... -d https://singpost-redelivery.info/` → 차단 화면 "클라우드에 다시 물어보기" → cloaking SUSPECTED 배너
- [ ] Chrome 안 이동: Chrome 주소창에 `localhost:8081/cj/` 입력 → 커버 오버레이 → 위험
- [ ] 정상 소상공인: `... -d https://hanaroombakery.sg/` → 잠깐 확인 후 Chrome으로

## 확인이 필요한 결정
- **(최신, 08:40) D2·D3 적용 후 실제 페이지 정상 오탐 45%** — Gemma가 실제 페이지에서 키를 자주 빠뜨려(보정 19/60, 파싱 실패 8/60) 정상 페이지가 caution이 됨. ≥danger 오탐은 0%. 상세 `eval/real/REAL_REPORT.md` 상단 비교표.
- **가중치 제안(미적용)**: `brand_mismatch_known_brand` 40→60, `secret_code_requested` 15→25. A 분할에서만 조정 → B(홀드아웃): ≥danger 0%→47%, 오탐 0% 유지. 30+30 소표본이라 방향성으로만. 적용하려면 `contracts/scoring_rules.json` + `scoring_vectors.json`(손계산 기대값) 갱신 후 Kotlin·Python 테스트 재실행.
- **FeatureRepair**: 스키마 위반 Gemma 출력을 버리지 않고 보수적으로 보정(누락 플래그=false). 실제 페이지에서 사용 가능 특징 73%→93%.
- **늦은 판정**: 8 s에 "확인 못 함"을 보여주고 25 s까지 계속 분석해 화면 갱신.
- Lite 런타임 = 어젯밤 맥 결과 기준 **GPU, free-form, thinking off, `system_prompt_v2.txt`** (REPORT.md가 없어서 `summarize_results.py` 재집계로 결정). 근거는 PROGRESS.md 결정 기록.
- 추측 디코딩 기본 **on** — S26 추정치(무추측 평균 6.6 s, 최대 8.0 s)가 8 s 타임아웃에 붙어서. 벤치마크로 확정할 것.
- `punycode_or_homoglyph`는 **homoglyph일 때만** 발화(정상 한글 IDN 보호).
- 일반 단어 브랜드(apple/grab/toss/amazon/meta…)는 정확 일치만.
- 화면 레벨 = Lite·Cloud 중 **더 엄격한 쪽**.
- Lite도 RDAP·crt.sh에 **도메인 이름**은 조회한다(페이지 내용은 안 나감). 온보딩 문구를 이렇게 정직하게 바꿈.
- compileSdk 37 / targetSdk 36 (Compose BOM 2026.09.00 요구).
- 버전: 출시 2주 미만 제외 규칙으로 LiteRT-LM Android **0.18.1**(맥 Python은 0.19.0), AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1.

## 숫자
- Opus 실제 호출: **7 / 40** (Messages API 요청 수 기준)
- 테스트: core 15/15 (url 64, scoring 52, 파서 패리티 363, 프롬프트 20, repair 36), app 18/18 (Roborazzi 12 + 데모 매핑 6), backend 217 passed
- 실제 페이지 평가 (`eval/real/REAL_REPORT.md`, 맥 GPU, 60 악성 / 60 정상):

| 지표 | 값 |
|---|---|
| Gemma 파싱 / 규칙에 쓰인 특징(repair 후) | 93% / 93% (repair 전 73%) |
| 악성 탐지 (규칙 ≥caution / ≥danger) | 58% / 3% |
| 정상 오탐 (≥caution / ≥danger) | 0% / 0% |
| Gemma 자체 verdict 악성 포착 | 87% (정상 → suspicious 19%, malicious 0%) |
| 지연 (맥 GPU) 평균 / p95 | 3.13 s / 3.96 s |
| 가중치 제안 후 홀드아웃 B | ≥danger 47%, 오탐 0% |
