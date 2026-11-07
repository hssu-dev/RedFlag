# 설계: 검사 중 알약 표시 (Chrome 덮개를 위험할 때만 전체 화면으로)

상태: 구현·폰 확인 완료 (2026-11-05). 결정 1–3 추천안.
관련: `RedFlagAccessibilityService.kt`, `AnalysisScreen.kt`, `docs/PROJECT_OVERVIEW.md` §3, 메모리 "cloud only on user request".

## 0. 왜
Chrome에서 처음 보는 사이트에 들어갈 때마다 화면 전체가 가려진다. 대부분은 안전하거나 "주의"로 끝나는데도
판정이 날 때까지 페이지를 쓸 수 없다. 검사 중에는 화면 상단에 작은 알약만 띄우고,
**위험할 때만** 전체 화면으로 키운다.

## 1. 지키는 것
- **판정 전 입력 금지**: 전체 덮개가 막아 주던 핵심은 "검사가 끝나기 전에 비밀번호를 넣는 일"이다.
  알약 상태에서는 **입력 보호**(§3)가 이 역할을 맡는다.
- **D3**: 실패한 검사는 어떤 경로로도 초록(안전)으로 끝나지 않는다. 확인 못 함 = 회색 알약.
- 클라우드는 사용자가 누를 때만 간다(변경 없음, `NoAutomaticCloudTest`).
- 분석 엔진(`LiteEngine`)과 `AnalysisController`는 바꾸지 않는다. 바뀌는 것은 **보여 주는 방식**뿐이다.
- 범위: Chrome 접근성 경로만. 다른 앱에서 링크를 탭한 경우(`LinkHandlerActivity`)는 전체 화면 그대로.

## 2. 상태별 화면

알약 위치: Chrome 툴바(주소창) 바로 아래 가운데. 주소창은 가리지 않는다(주소는 사용자가 확인할 핵심 정보다).
(구현 중 변경: 상태 표시줄 가운데는 카메라 구멍과 알림 아이콘에 글자가 가려져서, 툴바 아래로 옮겼다. 위치는 `url_bar` 노드의 화면 좌표에서 계산한다.)

| 상황 | 화면 | 사용자 동작 |
|---|---|---|
| 처음 보는 사이트, 검사 중 | 회색 알약 `◌ RedFlag 검사 중 · host` + 얇은 진행 막대 | 탭 → 전체 화면(진행 단계). [작게 보기] → 알약 |
| 안전 (`isVerifiedSafe`) | 초록 알약 "안전" 0.6초 → 초록 점(530396a)으로 줄어든 뒤 사라짐 | — |
| 위험·매우 위험 | 알약이 전체 화면으로 커짐(진동) → 기존 차단 화면, 터치 차단 | 돌아가기 / 그래도 열기 |
| 주의 | 노란 알약 "주의 · 눌러서 보기", 사용자가 다른 사이트로 갈 때까지 남음 | 탭 → 기존 주의 화면 |
| 확인 못 함 (`unverified`) | 회색 알약 "확인 못 함 · 눌러서 보기" | 탭 → 기존 확인 못 함 화면 |
| 맥락 질문 카드가 준비됨 (`Consent`/`Ask`) | 노란 알약 "확인할 게 있어요" (저절로 커지지 않음) | 탭 → 전체 화면의 질문 카드 |
| Gate Block, 전에 위험 판정한 사이트 재방문 | **처음부터 전체 화면** (지금과 같음) | — |
| 세이프 브라우징 탐지 (검사 중 발견) | 위험 판정과 같음 → 전체 화면 | — |

전체 화면에서 판정이 아직 "위험"이 아니면 [작게 보기]가 있다. 위험·매우 위험 화면에는 없다.

## 3. 입력 보호
- 판정이 안전이 아닌 동안(검사 중, 주의, 확인 못 함, 질문 대기) Chrome 웹 콘텐츠의 입력칸에 포커스가 가면 즉시 전체 화면으로 커진다.
  - **검사 중**에는 모든 입력칸이 대상이다. 문구: "검사가 끝날 때까지 입력은 잠시 막아둘게요".
  - **주의·확인 못 함**에는 비밀번호 칸(`isPassword`)만 대상이다. 정상 사이트의 45%가 "주의"로 나오므로(측정치) 검색칸까지 막으면 지금과 다를 바가 없다.
- 그 뒤 판정이 안전으로 나오면 다시 알약 → 초록 점으로 끝난다.
- 감지: `accessibility_service_config.xml`에 `typeViewFocused|typeViewClicked`를 더하고,
  이벤트 출처가 `com.android.chrome`이고 노드가 편집 가능(`isEditable`)한 경우에만 반응한다.
  Chrome 자체 입력칸(주소창, 페이지 내 찾기)은 `com.android.chrome:id/...` 뷰 id로 제외한다.
  웹 페이지 입력칸도 HTML `id`가 뷰 id로 보이므로(예: `id="n"`), "뷰 id가 없을 것"으로 거르면 안 된다(폰에서 확인).
- 막은 뒤 그 입력칸의 포커스를 지운다(`ACTION_CLEAR_FOCUS`).

## 4. 구조

```
RedFlagAccessibilityService
  ├─ CoverPolicy (순수)     : 입력 → PILL(style) | FULL(reason) | DISMISS
  └─ CoverWindows           : 창 2개 관리
       ├─ 무대 창  stage    : 숨은 WebView 전용. MATCH_PARENT, NOT_TOUCHABLE, 창 alpha 0
       └─ 표시 창  surface  : PILL = 알약 크기(WRAP_CONTENT, NOT_TOUCH_MODAL → 바깥 터치는 Chrome)
                              FULL = MATCH_PARENT, 터치 차단 (지금의 덮개와 같은 플래그)
```

- 숨은 WebView는 지금 덮개 `root`에 붙는다(`controller.start(viewHost = root)`).
  앞으로는 항상 **무대 창**에 붙는다. 표시 창 크기가 바뀌어도 WebView는 그대로다.
- 알약 → 전체 화면: 표시 창을 먼저 MATCH_PARENT로 바꾸고, Compose 안에서 알약 위치에서 전체로 펼치는 애니메이션을 그린다.
  줄어들 때는 반대 순서(애니메이션 → 창 축소).
- 검토 후 버린 안: 창 하나에서 NOT_TOUCHABLE만 바꾸는 방식. 알약도 누를 수 없게 되고,
  창의 일부만 터치를 받는 API(`OnComputeInternalInsetsListener`)는 공개 API가 아니다.

### CoverPolicy 입력
`gateKind`(Unknown/Block), `stored`(재방문 차단), `phase`, `displayLevel`, `unverified`,
`contextCard`(Consent/Ask 표시 중), `inputFocus`(없음/일반/비밀번호), `userExpanded`, `userCollapsed`, `fullCoverSetting`.

### 설정
"검사 중 전체 화면 가리기 (기존 방식)" 스위치, 기본 꺼짐. 켜면 지금과 똑같이 동작한다(발표 비교용).

## 5. 위험 요소와 사전 검증 (구현 0단계)
1. 창 alpha 0인 무대 창에서 WebView가 페이지를 그리고 `extract`와 스크린샷이 지금처럼 되는가.
   안 되면: 무대 창을 화면 밖 좌표에 두거나, alpha 0.01로 시험한다.
2. Chrome 웹 입력칸 포커스가 `TYPE_VIEW_FOCUSED`/`TYPE_VIEW_CLICKED`로 오는가, `isPassword`가 보이는가.
   안 되면: 페이지 관찰에 비밀번호 칸이 있으면 바로 전체 화면(관찰이 끝나야 알 수 있다는 한계를 문서에 적는다).

## 6. 테스트
- `CoverPolicyTest`: §2 표의 모든 행, §3의 두 규칙, D3(실패·시간 초과·확인 못 함이 PILL 초록이나 DISMISS가 되지 않음), 설정 스위치.
- 기존 테스트(app 87) 통과, `NoAutomaticCloudTest` 그대로.
- 폰 시연(GIF): 위키백과(알약 → 초록 점), 로컬 피싱 데모(알약 → 전체 화면), 로컬 로그인 데모에서 검사 중 입력칸 탭(입력 보호),
  응답 없는 사이트(회색 알약), 차단 목록 사이트(처음부터 전체), 위험 판정 사이트 재방문(처음부터 전체).
  실제 악성 페이지는 열지 않는다(로컬 데모만).

## 7. 구현 중 발견한 문제 (같은 커밋에서 수정)
회색 알약 시험 중, **존재하지 않는 도메인이 "안전"으로 끝났다.** 원인은 알약이 아니라 기존 Chrome 화면 읽기(③, 1e07e65)였다.
숨은 브라우저가 실패하면 Chrome 화면을 읽는데, Chrome이 띄운 **자체 오류 화면**("사이트에 연결할 수 없음 … ERR_NAME_NOT_RESOLVED", 118자)을
사이트 페이지로 읽어 Gemma가 판정했다. 기록을 보니 `dbs-secure-verify.top`도 같은 경로로 93자짜리 화면이 "안전"(25점)으로 저장돼 있었다.

`ChromeScreenReader.chromePage(ids, text)`:
| Chrome 화면 | 결과 |
|---|---|
| Safe Browsing 경고("위험한 사이트", `primary-button`/`details-button` id) | `safe_browsing_hit:chrome_warning` → 매우 위험 95 |
| `ERR_…` 오류 코드 | `load_error:chrome_…`(인증서는 `ssl_error:`) → 확인 못 함(응답 없음) |
| 그 밖의 Chrome 경고 페이지 | `load_error:chrome_interstitial` → 확인 못 함 |

경고 문구만 흉내 낸 페이지(Chrome id 없음)는 사이트 내용으로 읽는다. id까지 흉내 내도 결과는 매우 위험이나 확인 못 함이고, 안전은 나오지 않는다.
`LiteEngine.readScreen`은 화면 결과가 SB 경고일 때도 받아들인다(전에는 페이지나 호스트 경고만 받았다).
폰의 안전 캐시에서 잘못 저장된 두 항목(`redflag-nonexistent-zq7.com`, `dbs-secure-verify.top`)을 지웠다.
테스트: `ChromeErrorPageTest`(8), `FailSafeLiteTest`에 2건.
