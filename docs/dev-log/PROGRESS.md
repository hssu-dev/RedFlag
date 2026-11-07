# PROGRESS

## 상태 요약 (항상 최신)
- 세션 시작: 2026-11-04 02:12 (로컬)
- 현재: **M0~M7 완료** + v4(전송처 관찰·맥락 분석기) 구현. 아침 인계 = `MORNING.md`
- 다음 할 일: v4 맥락 분석기 폰 시연(3 시나리오) · SMS 출발 시 맥락 카드 빈도(33%) 처리 결정 · D2·D3 부작용(정상 오탐 45%) 결정
- Opus 실제 호출 누적: **15 / 40** (backend api_requests 기준)
- **사용자 결정 (11:12): 고급(클라우드) 분석은 사용자가 요청할 때만. 어떤 상황에서도 자동 전송 없음(추천일 때도).** 아래 Auto 에스컬레이션 기록은 과거 동작.
- 세션 중단: 03:20~07:00 API 차단(HTTP 403, 네트워크) — M6a 에이전트가 끊김(수집 자체는 완료돼 있었음)

## 사용자 결정 D1~D3 (2026-11-04 07:50) — 완료 (08:40)
- [x] D1 가중치: `brand_mismatch_known_brand` 40 유지 + 신규 `brand_mismatch_sensitive_request` +20 (브랜드 사칭 + Gemma 요구 행동 password/card/secret_code 또는 폼 민감 필드), `secret_code_requested` 15→25. scoring_rules v2, scoring_vectors 52→63 (변경 18건 손계산 재유도 + 신규 11건). Kotlin 63/63, Python 63/63.
- [x] D2 보정 출력 캡: 신규 규칙 종류 `clamp` — `model_output_repaired` [30,59] (safe 불가, 최대 caution; Safe Browsing floor만 예외). 벡터·테스트 추가.
- [x] D3 fail-safe 불변식: `analysis_incomplete` clamp [30,100] + `ReportBuilder`가 status≠complete면 강제 적용 + 앱 전역 `isVerifiedSafe`(complete && safe만 통과·캐시·허용). 테스트: core `FailSafeInvariantTest` 6건(입력 격자 전수), app `FailSafeLiteTest` 14건(모델 없음 / 로드 실패 2종 / 추론 예외 / 파서 실패 5종 / 보정 / 타임아웃 / RDAP 실패·예외 / 추출 실패 / 추론 중 취소 / 늦은 판정), backend 미완료 테스트 1건.
  - RDAP **실패**(네트워크·타임아웃·5xx·429·예외)만 미완료로 처리, **정보 없음**(404·TLD 미지원)은 unknown으로 둠 — 사설 PSL 호스트(*.pages.dev 등)를 전부 caution으로 만들지 않기 위해.
- 재측정 (`eval/real/REAL_REPORT.md`, A/B/전체):
  - D1만: 악성 ≥danger 3% → 27% (A 37% / B 17%), 정상 오탐 0% 유지
  - D1+D2+D3(현재 앱): 악성 ≥caution 63%, ≥danger 23%, **정상 오탐 ≥caution 45% (27/60)** — 전부 모델 답 문제(보정 19, 파싱 실패 8). ≥danger 오탐 0%.
  - ~~Auto 모드에서 caution은 Cloud 에스컬레이션~~ → 11:12 사용자 결정으로 자동 에스컬레이션 제거(요청 시에만).
- 실기기에 v2 APK 설치 완료. 발견: S26에서 재설치 시 접근성 설정이 null로 초기화 → MORNING에 기록.

### 실기기 벤치마크 — 완료 (08:05) — S26 Ultra SM-S948N, Android 17 (API 37), SM8850, Chrome 156
- 사용자 지시로 1회만: GPU + 추측 디코딩, cases.jsonl 10건 (`eval/device/bench/results-s26-gpu-spec.jsonl`)
- 파싱 10/10, 스키마 10/10, verdict 9/10, 악성 6/6 (strict 5), 정상 오탐 0/4, 규칙: 악성 6/6 ≥danger, 정상 0/4
- **Gemma 지연 평균 4.24 s, 중앙 3.93 s, 최대 5.93 s** (엔진 로드 8.8 s, 최초 1회). 토큰 수는 미기록(getBenchmarkInfo null)
- 발견: `adb shell mkdir`로 만든 models/는 shell 소유(drwxrws---)라 앱이 모델을 못 읽음("model missing") → 앱이 시작 시 직접 생성하도록 수정, MORNING 절차 수정

## v4: 데이터 전송처 관찰 + 맥락 분석기 — 구현 (2026-11-04 10:00~11:10)
계획: `docs/plans/form_destination_observation.md`, `docs/plans/context_analysis.md`. 커밋 28c93ac, 9d739cc 외.
- **설계 결정**: Turn 1(검증된 프롬프트)은 바이트 그대로. 전송처 판단(D)·맥락 필요 판단(T2, 도구 호출)·맥락 판단(T3)은
  각각 **페이지 텍스트 없는 짧은 별도 대화** (도구 선언이 Turn 1 시스템 턴에 끼면 기준선이 바뀌므로; 인젝션 표면도 줄어듦).
  계획서의 "같은 대화에서 이어서" 대신 이 방식을 택함.
- 계약 v4 (`scoring_rules.json`): 코드 규칙 `data_sent_to_messenger_bot` +35(텔레그램 봇 API·디스코드/슬랙 웹훅),
  `sensitive_data_to_form_service` +30(formspree·Apps Script 등 + 비번/카드/인증번호/신분증), Gemma `destination_unrelated` +20
  (개인정보 요구 시), 맥락 `context_scam_scenario` +25 · `context_purpose_mismatch` +20 · `guided_app_install` 최소 85 ·
  `unknown_sender_payment` +20 · `used_trade_escrow_payment` +25. **전부 가산만.** 벡터 70→91 (손계산), Kotlin·Python 통과.
- 추출기 `data_endpoints`: 폼 action(JS 변경 포함), 전송 API가 있는 인라인 스크립트의 URL 리터럴, 로딩 중 fetch/XHR/beacon. 쿼리 제거.
  샌드박스 Chromium에서 데모 페이지 확인: 폼→optika-shop-ua.com, 실행되지 않은 스크립트의 api.telegram.org/bot 모두 잡음.
- 후속 턴 쌍둥이: core `followup/Followups.kt` ↔ backend `lite_followups.py`, `contracts/followup_vectors.json`(프롬프트 바이트 일치).
  고정 목록: `context_tools.json`(도구 3종), `context_catalog.json`(출발 앱·질문 U1–U4·칩·동의 문구) — Gemma는 화면 문장을 못 만듦.
  대화·사용자 진술의 `<`,`>`는 치환(태그 탈출 차단), 전화·계좌번호 마스킹, 근거 인용은 원문에 있어야만 채택.
- 앱: 출발 앱(referrer / 알림 매칭), 코드 판단 → Gemma 도구 호출 → 동의 카드 [허용][건너뛰기][다시 묻지 않기] / 질문 카드(칩+300자) →
  Turn 3 → 같은 id로 재채점(점수 하락 불가 가드). [상황 직접 입력] 항상 표시. 알림 버퍼는 메모리 전용 30분.
  오버레이는 입력칸이 보일 때만 focusable. "안전 → 자동 진행"은 맥락 단계가 끝날 때까지 대기, 카드가 떴으면 자동 진행 안 함.
- 테스트: core 39 · backend 269 · app 61(신규 LiteFollowupsTest 6, NotificationBufferTest 3) 전부 통과.
- **측정** (`eval/followups/REPORT.md`, 맥 GPU, eval/real 120건):
  - Gemma 도구 호출 형식 준수 **236/236 (100%)**, 전부 네이티브 tool_calls. 지연 평균 1.2 s(맥). 폰 1건 1.64 s(S26 GPU).
  - 출발 앱 unknown(크롬 경로): 맥락 카드 정상 **4/60 (7%)**, 피싱 42/60 (70%).
  - 출발 앱 SMS: 동의 카드 정상 **20/60 (33%)** ⚠, 피싱 54/60 (90%). 정상 쪽 다수가 reason "other"만 — 제안: "괜찮음"에서 reason이 other뿐이면 무시 → 정상 11/60(18%), 피싱 51/60(85%). **사용자 결정 대기.**
  - Turn D: 오프라인은 폼 action만 있어 1/120(가짜 DHL→xxxxxxxx.test) → Gemma "unrelated", 90→100. 스크립트/XHR 전송처는 실제 120건에서 미수집(대부분 사이트 내려감).
- 데모 페이지(로컬 전용) 4종: `safepay-escrow.shop`(중고 안전결제), `quick-remote-support.com`(원격지원 앱), `redelivery-center.com`(택배 수수료),
  `lucky-event-gift.com`(텔레그램 봇 + 무관 도메인 전송). 디버그 빌드에서만 `adb shell cmd notification post` 알림을 SMS로 취급(시연용).
- 사용자 결정(11:00): "괜찮음"에서 Gemma 이유가 other뿐이면 무시 → SMS 출발 정상 카드 33%→18%, 피싱 90%→85% (저장된 답 재집계).
- **폰 시연 (S26, 실제 Gemma GPU, 11:03~11:09)** — 스크린샷 `eval/device/context_demo/`(녹화 mp4는 로컬만, git 제외):
  | 시나리오 | 페이지만 | 맥락 후 | 맥락 단계 |
  |---|---|---|---|
  | 2 원격지원 앱 + 직접 입력 "예, 전화로" | 25 안전 | **85 매우 위험** (guided_app_install) | T2 U3 1.7 s, T3 2.2 s, gov_voice_phishing |
  | 1 중고 안전결제 + 문자 대화 4개(알림) | 25 안전 | **95 매우 위험** (used_trade_escrow) | 동의 카드 → 허용, 근거 "그럼 안전결제로 해요." |
  | 3 택배 수수료 + 국제발신 문자(알림) | 10 안전 | **55 주의** (parcel_smishing, 모르는 발신자+결제) | T3 2.5 s |
  | 전송처: 텔레그램 봇 + 무관 도메인 폼 | — | **70 위험** (봇 +35 코드, Gemma D "unrelated" +20, 1.3 s) | 건너뛰기 → 판정 유지 확인 |
- 시연 중 발견·수정:
  1. 맥락 카드가 뜬 페이지가 1차 "안전"으로 12시간 캐시돼 다음엔 분석 없이 통과 → 캐시는 맥락 단계가 끝난 뒤에만
     (카드가 떴는데 건너뛰거나 떠나면 캐시 안 함, 답한 뒤에도 안전일 때만).
  2. **클라우드 fail-safe 구멍**: 샌드박스가 페이지를 못 열면(DNS 실패) Chromium 오류 페이지(chrome-error://)를 분석해
     "complete · 0점 · 안전"을 냈음 → 오류 페이지는 로드 실패로 처리(status error → 최소 30). 테스트 추가, 잘못된 캐시 1건 삭제.
     (화면은 Lite 70을 보여 실제 통과는 없었음. 원인 중 하나: 컨테이너 재빌드 때 DEMO_MODE=1 누락 → 재기동.)
- Opus 실제 호출 누적 **15 / 40** (전송처 데모에서 Auto 에스컬레이션 2회; backend api_requests 테이블 기준).

## 앱 안 링크 검사 + 사용자 맥락을 고급 분석에 포함 — 구현·폰 시연 (2026-11-04 12:00~12:30)
설계: `docs/plans/manual_url_check.md` (결정 6건 추천안대로). 커밋 cbbd0c1(단계1) 946222c(2+4) 112d6f7(3) + 시연 수정.
- **단계 1** core `UrlExtractor`(계약 `url_extract_vectors.json` 42건: 한글 붙음, 문장부호, 디팽, TLD 목록 확인, 이메일·다른 스킴·loopback 제외), `Gate.evaluate(useSafeCache=false)`.
- **단계 2** `CheckActivity`(비공개): Chrome 자동 이동 없음, 알려진 사이트는 "알려진 사이트 + 그래도 자세히 검사", 단축 URL 체인 표시, 같은 글의 다른 링크 칩. 붙여 넣은 글 → `<untrusted_conversation>` 1건(동의 창 없음, [빼기] 칩), 상황 설명 칸 → `<user_statement>`. 홈 "링크 검사" 카드(입력 중 링크 칩), 최근 기록 탭 → 저장된 판정 + 다시 검사.
- **단계 3** 사용자 맥락 → 정밀 분석: 기기 `CloudContext`(마스킹·길이·개수 제한, 알림 대화 원문 제외, `requestCloud()` 안에서만 생성, 열린 질문 카드는 끝날 때까지 대기), "함께 보냄 · 보낼 내용 보기", 이후 설명 추가 시 "설명을 더해 다시 정밀 분석". 백엔드 `UserContext`(extra=forbid) → 페이지 단계는 캐시 그대로(맥락 미혼입) → Opus 맥락 판단 1회(`opus_context.txt`, FIELDS는 Gemma와 글자 동일) → 기기 판단과 위험 쪽 병합 → v4 맥락 규칙. 저장·trace에 원문 없음.
- **단계 4** 공유 시트·텍스트 선택(`ShareEntryActivity` 트램펄린: 공유 글만 전달, 외부 extra 무시), 앱 바로가기(입력칸 포커스, 클립보드 자동 읽기 없음).
- 테스트: backend 281(+11), core 44(+2), app 70(+6: 클립보드 소스 가드, 트램펄린, export, Chrome 버튼만, 맥락은 requestCloud만).
- **폰 시연** (S26, `eval/device/inspect_demo/*.png`):
  - 공유로 중고거래 문자 전달 → 기기 50(주의) → Gemma가 U4 질문 → "예, 중고거래 중" → **100**(+50, 증거는 붙여 넣은 문구).
  - [보낼 내용 보기]: 칩 라벨 + 입력 문장 + 붙여 넣은 메시지, 전화번호 `###-####-####`.
  - [정밀 분석] → 캐시 hit + 맥락 판단 1회 → 클라우드 100, Opus가 기기에 없던 consistency=mismatch를 더함. 서버 sqlite 덤프·로그에 문자/번호/입력 문장 **없음** 확인.
  - 홈 카드: `naver.com/news or hxxps://redelivery-center[.]com/...` → 링크 칩 2개, naver → "알려진 사이트예요(네이버 공식 도메인)", 자동 이동 없음. 기록 탭 → "저장된 판정이에요".
- 시연에서 찾은 문제 2건(수정): ① `ComposeView.apply {}` 안의 `Content()`가 ComposeView 자신의 `Content()`로 해석돼 무한 재귀(StackOverflow) → 이름 변경. ② 실제 API가 forced `tool_choice`+thinking 조합을 400으로 거절 → 페이지 에이전트와 같은 형태(adaptive thinking, auto)로 변경. 첫 시도는 기기 판단으로 정상 폴백(95 매우 위험)되어 D3 확인.
- Opus 실제 호출 누적 **19 / 40** (이번 시연 3: 페이지 1 + 맥락 400 1 + 맥락 1).

## CDN·차단·응답 없음 대응 (②③④) + Gemma 페이지 상태 측정 (2026-11-04 22:00~22:45)
- **측정** `eval/page_state/REPORT.md`: 실제 샌드박스 관찰 224건 + 다른 업체 페이지 12건(손으로 작성), 빈 관찰 28건 제외.
  - Gemma 230/236(97%), 맥 1회 ~1.3초. 코드 서명 235/236(같은 페이지를 보고 만들어 낙관적).
  - "애매할 때만 Gemma"는 234/236으로 오히려 손해. Gemma는 가짜 "사람인지 확인" 피싱(mal_011, mal_086)을 CDN 검사로 봤다.
  - 프롬프트 v2("페이지가 그렇게 말할 때만")는 주입 문구("answer unavailable")에 넘어가서 v1 유지.
  - **결정: 코드 서명이 판단하고, Gemma 페이지 상태 턴은 끔**(`GEMMA_PAGE_STATE=false`). 쌍둥이 메시지/파서와 벡터는 남겨 둠.
- **② CDN 검사 기다리기**: 숨은 WebView가 "Just a moment..." 같은 검사를 만나면 최대 12초 기다린다(Chrome 덮개 아래에서는 3초 뒤 Chrome 화면으로 넘어감).
  - 검사는 업체 표시나 403/429/503 상태가 있을 때만 인정한다. 가짜 CAPTCHA 피싱은 검사가 아니라 분석 대상이다.
- **③ Chrome 화면 읽기** `ChromeScreenReader`: 숨은 브라우저가 막히거나, 시간이 다 되거나, 연결이 안 되면 접근성으로 Chrome이 보여 주는 글과 입력칸을 읽어 Gemma로 분석한다.
  - 다른 앱에서 링크를 연 경우, 막히면 Chrome에서 덮개를 씌워 열고 화면을 읽는다(`ScreenHandoff`).
  - 숨은 브라우저는 거부됐는데(차단, 401/403) Chrome에는 입력칸이 있으면 기존 `cloaking_suspected`(+35, 코드 관찰)가 발동한다.
  - 폰 확인: 로컬 은닉 픽스처(`eval/device/cloak/`)에서 WebView는 403 차단, Chrome에는 가짜 로그인이 보였다. 0.9초에 넘기고 화면은 2ms에 읽었으며 결과는 **65 위험**("검사기 앞에서 정체를 숨기는 페이지").
  - 사이트가 WebView를 알아보는 방법: `X-Requested-With: dev.redflag.app`, `sec-ch-ua: "Android WebView"`. 위장은 하지 않기로 함.
- **④ 응답 없음**: "사이트가 응답하지 않아요"와 "사이트가 검사를 막았어요"를 "확인 못 함"과 구분해 보여 준다. 폰에서 없는 도메인으로 확인.
- Cloudflare "Suspected Malware"도 호스팅 업체 경고 판정(`cloudflare_malware`)으로 처리한다. 404 등 오류 상태로 보낸 사기 콘텐츠도 분석한다(예전에는 실패 처리).
- 테스트: backend 284, core 46, app 87.

## 검사 알약 + 대화형 홈 + Chrome 자체 화면 오판 수정 (2026-11-05 01:20~02:10)
설계: `docs/superpowers/specs/2026-11-05-check-pill-design.md`, `docs/superpowers/specs/2026-11-05-chat-home-design.md` (결정은 추천안대로).
- **확인 점**: "RedFlag 확인 완료" 글자 알림 → 오른쪽 위 초록 점(1.8초, 화면 낭독은 글자 그대로). 530396a
- **검사 알약** 765abfd: Chrome 덮개가 검사 중에는 툴바 아래 알약(회색 검사 중 → 초록 안전 → 점 / 노란 주의 / 회색 확인 못 함 / 노란 질문).
  전체 화면은 차단 목록·재방문·위험·매우 위험·알약 탭·**입력 보호**(검사 중 입력칸, 안전 아닌 판정 뒤 비밀번호 칸)일 때만.
  숨은 WebView는 보이지 않는 무대 창(alpha 0)으로 옮겼다. 설정 "검사 중 전체 화면 가리기"(기본 끔).
  - 폰: bakery(알약→초록→점), CJ 데모(알약→전체 95), 입력칸 탭(전체 + "입력은 잠시 막아둘게요"), 알약 탭/작게 보기, 없는 도메인(회색), SB 시험 페이지(처음부터 전체).
- **Chrome 자체 화면 오판** 97710ab: 숨은 브라우저 실패 뒤 Chrome 화면을 읽을 때, Chrome의 오류 화면(ERR_NAME_NOT_RESOLVED)을 사이트로 읽어 **없는 도메인이 "안전"**,
  `dbs-secure-verify.top`(93자)도 "안전"으로 저장돼 있었다. 이제 Chrome Safe Browsing 경고 → 매우 위험 95, ERR 코드 → 응답 없음, 그 밖의 Chrome 경고 → 확인 못 함. 폰 안전 캐시의 두 항목 삭제.
- **나가면 덮개 닫기** e826d49 (사용자 보고: 검사 중 뒤로가기/나가기 → 무한 "검사 중"): 주소창이 URL이 아니게 되거나(검색, 새 탭) 다른 앱이 앞에 오면 덮개와 검사를 닫는다. 돌아오면 다시 검사.
- **대화형 홈**: 앱 홈 = 대화 화면(입력창 아래). 링크 있는 글 = 받은 메시지(untrusted), 검사 뒤 링크 없는 글 = 사용자 상황 설명(user_statement) → 재판정(더하기만).
  여러 링크 = 선택 칩, 알려진 사이트 = 열기/그래도 검사, [자세히] = 기존 분석 화면, 정밀 분석·Chrome 열기는 버튼으로만. 지난 검사 8건(링크·판정만, 원문 저장 없음).
  공유/텍스트 선택은 앱 안 `SharedInbox` → 홈(홈은 인텐트 엑스트라를 읽지 않음). 기존 홈 카드는 ⚙ 설정 화면으로.
  - 폰: 링크+메시지 → 95 매우 위험(붙여넣은 메시지 사용 칩), "A number I do not know texted me this" → +5 → 100, 자세히/설정/뒤로, 공유 → 대화에 들어와 검사 + 맥락 질문 카드.
- 테스트: app 118(+31: CoverPolicy 13, ChromeErrorPage 8, FailSafeLite +2, ChatRouter 7, InspectMode 공유·홈 엑스트라·Chrome 버튼 범위 확대), core 46.

## 보류 계획
- Cloud 에이전트 사이트 직접 탐색: `docs/plans/cloud_agentic_browsing.md` (저장만, 착수 전 결정 2건)
- 프롬프트 인젝션 보강: `docs/plans/prompt_injection_hardening.md` (저장만; 태그 탈출 구멍 확인됨)

## 환경
| 도구 | 버전 | 비고 |
|---|---|---|
| macOS | 27.2 (26B118) | Apple M5 Pro, 48 GB |
| JDK (기본) | Oracle 26.0.2.1 | AGP에는 너무 새로움 → 사용 안 함 |
| JDK (빌드용) | Homebrew openjdk@17 17.0.21 | `JAVA_HOME=/opt/homebrew/opt/openjdk@17` |
| Android SDK | `~/Library/Android/sdk` | `ANDROID_HOME` 미설정 → 스크립트에서 export. platforms;android-36, build-tools 35/36, NDK 27.2 |
| cmdline-tools | Homebrew android-commandlinetools 15859902 | sdkmanager/avdmanager (`--sdk_root` 명시) |
| emulator | 설치 중 (37.2.13) | 원래 없음 → M0에서 설치 시작 |
| system image | 설치 중 `system-images;android-37.0;google_apis_playstore;arm64-v8a` | 최신 안정 API(37.0) Play 이미지 |
| adb | platform-tools 37.0.1 | |
| Docker | 29.7.2 | Docker Desktop, 18 CPU / 8 GB |
| Python | 3.12.11 (uv 0.11.7) | 레포 `.venv`에 litert-lm-api 0.19.0 (어젯밤 실험용) |
| Gradle | wrapper 9.8.2 | 전역 gradle 없음 → wrapper 사용 |

### 고정 버전 (공식 저장소 maven-metadata / Last-Modified로 확인, 2026-10-21 이후 출시분 제외)
| 라이브러리 | 버전 | 출시일 | 비고 |
|---|---|---|---|
| Android Gradle Plugin | 9.4.1 | 2026-09-18 | 9.5.0(10-28)은 2주 미만 |
| Kotlin | 2.4.21 | 2026-10-08 | |
| Gradle | 9.8.2 | 2026-10-19 | |
| Compose BOM | 2026.09.00 | 2026-09-09 | |
| LiteRT-LM Android (`com.google.ai.edge.litertlm:litertlm-android`) | 0.18.1 | 2026-10-20 | 0.19.0(10-29)은 2주 미만. 맥 Python 실험은 0.19.0 |
| activity-compose | 1.13.0 | 2026-03-11 | |
| core-ktx | 1.19.1 | 2026-09-23 | |
| lifecycle | 2.11.0 | 2026-06-17 | |
| webkit | 1.17.1 | | 1.18.0(10-21)은 경계값이라 제외 |
| kotlinx-serialization-json | 1.12.0 | 2026-10-19 | |
| kotlinx-coroutines | 1.11.0 | 2026-05-07 | |
| OkHttp | 5.5.0 | 2026-08-16 | 5.5.1(10-22) 제외 |
| Roborazzi | 1.76.0 | 2026-09-29 | |
| Robolectric | 4.17 | 2026-09-10 | |

## 모델
- `models/gemma-4-E4B-it.litertlm` — 3,659,530,240 bytes
- sha256 `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`
- 출처: HF `litert-community/gemma-4-E4B-it-litert-lm` (공식, gated 아님). HF API `paths-info`의 LFS sha256과 **일치** 확인.
- 어젯밤 이미 `~/models/litert/`에 받아 둔 동일 파일을 APFS clone(`cp -c`)으로 복사 → 재다운로드 불필요.

## 마일스톤 로그
### M7 — 완료 (07:45)
- `MORNING.md` 작성(실제 경로·패키지·시리얼 변수로 복사 실행 가능), README 갱신

### M6 — 완료 (07:30)
- M6a: OpenPhish 60 (시도 174, 제외 114: http_error 77, empty 27 …) + Tranco 20만~100만 60 (시도 82, 제외 22). Docker 샌드박스에서만 열람. `eval/real/collect_log.json`
- M6b: 맥 LiteRT-LM GPU, v2 프롬프트, 120건. 결과 `eval/real/REAL_REPORT.md`(repair 적용, 앱 동작과 동일) / `REAL_REPORT_strict.md`(repair 없음)
  - 파싱 112/120 (93%), 스키마 유효 88/120 (73%) — 원인: 불리언 키 누락(addresses_ai_analyzer·too_good_to_be_true 각 12건) → **FeatureRepair 추가**(Kotlin+Python, 36건 패리티 테스트) → 규칙에 쓰인 특징 93%
  - 악성 탐지(≥caution) 55% → repair 후 58%, ≥danger 3%. 정상 오탐(≥caution) **0/60**
  - Gemma 자체 verdict: 악성 87% (malicious|suspicious), 정상 → suspicious 19%, malicious 0%
  - 지연(맥 GPU) 평균 3.13 s, p95 3.96 s
  - 도메인 나이: 악성 39/60이 RDAP 없음(pages.dev 등 PSL private 호스팅/조회 실패) → new_domain 2/60만 발화
  - 튜닝(A에서만 조정, B로 보고): brand_mismatch_known_brand 40→60, secret_code_requested 15→25 → B: ≥danger 0%→47%, 오탐 0% 유지. **제안만, 계약 미변경**(`eval/real/tuning_proposal.json`)
- `eval/score_device_results.py`: 어젯밤 결과 파일로 summarize_results.py와 수치 일치 확인

### 리뷰 수정 (07:10~07:40) — 별도 리뷰 에이전트 지적 8건 전부 수정
1. 취소된 실행이 엔진을 추론 중에 닫고 GPU를 영구 비활성화 → CancellationException 재throw, 추론 직렬화(inferMutex), 취소 시 cancelProcess
2. 릴리스에서 모델 없으면 Fake가 "safe" → 릴리스는 항상 실제 엔진(없으면 error), Fake는 데모 호스트 외엔 예외 → **unverified**
3. 타임아웃/빈 페이지/HTTP≥400/SSL 오류 페이지가 관찰로 쓰임 → 모두 추출 실패 처리
4. 파이프라인 예외가 앱 크래시 → status=error 보고서
5. 커버 닫아도 Lite 계속 실행 → 컨트롤러가 Lite 잡 추적·취소
6. 신호 예산 미강제(OkHttp blocking) → callTimeout 2.5 s
7. 잘못된 백엔드 URL → 크래시 대신 Cloud 실패 표시
8. GPU init 실패 시 엔진 누수 → close
- 회귀(에뮬레이터 07:42): 데모 차단 정상, 비데모 페이지는 Fixture 엔진에서 Unverified, **늦은 판정**: 8 s 미확인 → 12 s에 100 critical로 전환 (`shots/m5/r*.png`)

### M5 — 완료 (03:16, 이후 07:40 보강)
- APK: `android/app/build/outputs/apk/debug/app-debug.apk` (`./gradlew :app:assembleDebug` 성공, ~118 MB: LiteRT-LM 네이티브 포함)
- 에뮬레이터: AVD `redflag37` (API 37.0 Google Play arm64, RAM 8 GB), Chrome 145 포함, 시리얼 `emulator-5554`
- 시나리오 증거 (`shots/m5/`, 직접 열어 확인함):
  1. 역할/접근성: `cmd role add-role-holder` + `settings put secure enabled_accessibility_services` → `dumpsys accessibility`에 바인드 확인
  2. `s2_known_good_to_chrome.png` — naver.com: 화면 없이 Chrome으로 (로그: gate=Pass/brand)
  3. `s3a_waiting.png`, `s3b_block.png` — dbs-secure-verify.top: 대기 → 95 critical 차단
  4. `s4_safe_browsing_block.png` — SB 테스트 URL 즉시 차단(페이지 로드 없음)
  5. `s5a/s5b` — Chrome 안 이동 → TYPE_ACCESSIBILITY_OVERLAY 커버 → 75 danger (`s5_overlay_window_dump.txt`)
  6. `s6a/b/c` — 회색지대(soomstudio.kr) 35 caution → Auto 에스컬레이션 → SSE trace 실시간 표시 → Cloud 25 safe
  7. `s7a..d` — 클로킹(singpost-redelivery.info): Lite 95 critical / Cloud 60 danger + cloaking SUSPECTED 배너
- Roborazzi JVM 스크린샷 12장 (`shots/roborazzi/`): 대기/차단/주의/클라우드/클로킹/미확인/안전 × 라이트·다크·한국어
- 발견·수정한 버그:
  - (안전) WebView 메인 프레임 로드 실패 시 에러 페이지가 추출되어 "빈 페이지 → safe"로 판정되던 문제 → 로드 에러는 추출 실패(status=error, 미확인 화면)로 처리
  - Chrome url_bar는 스킴을 숨김 → https 실패 시 http 재시도, 데모 매핑도 스킴 무시
  - 에뮬레이터: GPU 엔진 init은 성공하지만 첫 추론에서 "Can not find OpenCL" 실패 → 런타임 GPU→CPU 폴백 추가

### M4 — 완료 (02:53) — 커밋 f02fb1d
- pytest 217 passed (Anthropic mock)
- 실제 Opus E2E (DEMO_MODE=1, `backend/samples/`): bank 95 critical / injection 95 critical(ai 플래그) / cloaking 60 danger + cloaking_suspected
- 공식 문서 확인: web_search `web_search_20260209`, anthropic SDK 1.17.0, `claude-opus-5-5`, submit_report는 strict 미사용(maxItems 제약) — 상세는 커밋 메시지/에이전트 보고 참조

### M3 — 완료 (02:43) — 커밋 5f17aed
- `demo-pages/` 8개 (요구 6개 + 회색지대 필라테스 + SB 스텁 히트), 로컬 nginx
- 증거: `shots/m3/sheet_*.png` — 클로킹 페이지가 webdriver=true(Playwright)에선 블로그, false(폰 WebView 조건)에선 SingPost 피싱. 추출기(contracts/extract_page.js)가 m365 숨김 인젝션 문구 검출

### M2 — 완료 (02:46) — 커밋 7379a5a
- `./gradlew :core:test` → 14 테스트 클래스 메서드 전부 통과: url_vectors 64/64, scoring_vectors 52/52, 파서 패리티 363개 raw 레코드(어젯밤 results/*.jsonl) 100% 일치, PromptBuilder 골든 20/20 바이트 일치
- Tranco: list GQ38K (2026-11-02) 상위 100k → PSL 적용 후 99,572 도메인 해시셋 (796 KB)

### M1 — 완료 (02:28) — 커밋 73cd12e
- 스키마 3종(2020-12) 자체 검증, fixtures 4종 스키마 통과, scoring_vectors 52 (손계산 기대값 → 파이썬 구현과 52/52 일치), url_vectors 64
- 부록 B 초기 가중치로 cases.jsonl 악성 6/6 danger+, 정상 4/4 safe 확인 (test_appendix_b_case_requirement)

### M0 — 완료 (02:20)
- 레포 구조 생성, 어젯밤 실험 파일 → `redflag-gemma-test/`로 이동 (`.venv`는 루트에 두고 심볼릭 링크)
- `.gitignore` 확장 (models, gradle/build, apk, backend/data, 대용량 eval)
- 모델 sha256 검증 완료

## 결정 기록
- [M0] `redflag-gemma-test/`가 따로 없었음 — 어젯밤 실험 파일이 레포 루트에 있었다. 그대로 `redflag-gemma-test/`로 이동(내용 수정 없음). `REPORT.md`는 **존재하지 않음** → `summarize_results.py`로 결과를 다시 집계해 런타임을 결정함(아래).
- [M0] Lite 런타임/설정 = **LiteRT-LM, GPU, free-form(제약 디코딩 없음), thinking off, `system_prompt_v2.txt`**. 근거(`summarize_results.py results/litert-e4b-*` 재집계):
  - v2 GPU free t0.7×3: verdict 29/30, 악성 탐지 18/18(strict 17), 정상 오탐 0/12, 파싱 30/30
  - schema 모드는 claimed_brand 0/N(제약 디코딩이 브랜드 필드를 망가뜨림) → 사용 안 함
  - think 모드는 정확도 이득 없음 + 토큰 7배 → 사용 안 함
  - v2는 v1과 입력 포맷(`<signals>` + `<untrusted_page>`)과 10필드 출력이 동일 → 마스터 프롬프트의 "system_prompt.txt 포맷 그대로" 조건 충족
- [M0] 에뮬레이터 API 37.0 Play 이미지 — 마스터 프롬프트의 "최신 안정 API". 앱 compileSdk/targetSdk는 36 (설치된 플랫폼, AGP 9.4 안정 지원).

- [M1] 스코어링 규칙은 선언형 식(fact/eq/lt/has/all/any/not)으로 표현 → Kotlin·Python이 같은 파일을 해석. 템플릿/헤드라인 규칙도 JSON에.
- [M1] `punycode_or_homoglyph` 규칙은 **homoglyph일 때만** 발화 — 부록 B 조건이 "IDN 혼동 문자"이므로. 정상 한글 IDN(punycode만)은 점수 없음.
- [M1] brand_domains: 일반 단어 브랜드(apple, grab, toss, amazon, meta…)는 `match: exact` — "Big Apple Bakery" 같은 오탐 방지. 38개 브랜드, 확실한 공식 도메인만.
- [M1] 홀드아웃 kr_pilates_new(정상)는 초기 가중치로 35 caution(new_domain+urgency). 기대값/가중치는 건드리지 않고 그대로 기록 — M6에서 분할 튜닝으로 다룸.
- [M1] RiskReport 필드 추가: `status`(complete/timeout/error), `escalate`, red_flag `weight`, signals `homoglyph`·`external_link_domains`. 이름 변경 없음.
- [M1] 단축 URL: 게이트는 Unknown(shortener)만 반환, 앱이 렌더링 없이 리다이렉트만 따라가(HEAD→GET, 5홉) 최종 URL을 다시 게이트.
- [M2] demo_hosts.json은 core 리소스에서 제외(릴리스에 실리지 않게), 앱 debug 에셋으로만 포함. 백엔드는 DEMO_MODE=="1"일 때만 읽음.
- [M5] compileSdk 37 (Compose BOM 2026.09.00이 37 이상 요구), targetSdk 36. Robolectric 테스트는 sdk 34(36은 JDK21 필요, 설치된 건 17/26).
- [M5] 추출기 WebView: 별도 WebView Profile(MULTI_PROFILE)로 쿠키·저장소 분리 후 매 실행 삭제, JS 브리지 없음, 다운로드 무시, file/content 접근 off, 팝업·비 http 스킴 차단. UA는 WebView의 "; wv"를 제거해 Chrome과 같게 → 클로커가 사용자 Chrome에 주는 화면을 그대로 봄.
- [M5] Lite 신호(RDAP·crt.sh)는 **등록 도메인 이름만** 공용 레지스트리에 조회(페이지 내용·URL 경로는 기기 밖으로 안 나감). 데모 호스트는 overrides 사용.
- [M5] 화면 레벨 = Lite와 Cloud 중 더 엄격한 쪽. ~~Auto 모드 에스컬레이션: 점수 30–70 또는 Lite 미완료~~
- [v4, 사용자 결정 11:12] 클라우드 분석은 사용자가 버튼을 누를 때만(`requestCloud()` 단일 진입점, `NoAutomaticCloudTest`가 소스로 강제). 모드는 '기기 안에서만'(클라우드 버튼 숨김) / '요청 시 고급 분석' 2가지로 정리, 저장된 AUTO·CLOUD는 '요청 시'로 이전. report.escalate는 화면의 '추천' 표시로만 쓰임. 폰 확인: 이전에 자동 전송됐던 70점 데모 → 백엔드 /v1/analyze 0건, Opus 15 유지.
- [사용자 결정 2026-11-04] 사용자가 직접 건넨 맥락(상황 설명·질문 답·붙여넣은 메시지, 숫자 마스킹)은 고급 분석을 누를 때 함께 보낸다. 자동 전송 금지는 그대로. 자동 수집 대화 원문은 보내지 않음(추천안). 설계: `docs/plans/manual_url_check.md` §5.
- [M5] 실기기/에뮬레이터 주소 자동 선택: 에뮬레이터 10.0.2.2, 실기기 localhost(`adb reverse tcp:8000/8081`).
- [M5] Gemma 설정 = 맥 실험 선택값(free-form, think off, greedy, max 800 tok) + 추측 디코딩 옵션(기본 on, 설정·벤치 플래그로 끔). S26 추정치(무추측 6.6 s)가 8 s 타임아웃에 가까워서.

- [M5] 늦은 판정: 8 s 타임아웃 후에도 Lite를 최대 25 s 계속 돌려 화면을 실제 판정으로 갱신(S26 추정 지연이 타임아웃 근처라서).
- [M6] FeatureRepair: 파싱은 됐지만 스키마 위반인 Gemma 출력은 보수적으로 보정(누락 플래그=false, 잘못된 enum=중립값), trace에 기록. 파싱 실패는 보정 안 함.
- [M6] 가중치 튜닝 결과는 **제안으로만** 남김 — 계약(scoring_rules/vectors) 변경은 사용자 확인 후.

## Blocked
- 에뮬레이터에서 LiteRT-LM 실행 불가: GPU=OpenCL 없음, CPU=에뮬레이터 hvf_map_safe FATAL 크래시 → Fixture 엔진으로 진행(마스터 프롬프트 허용). 실기기에서 확인 필요.
- 03:20~07:00 API 네트워크 차단(HTTP 403)으로 세션 정지.
