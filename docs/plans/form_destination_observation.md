# 계획 (승인, 미착수): 데이터 전송처 관찰 + Gemma 관계 판단

상태: 구현 (2026-11-04, scoring_rules v4). 결과: ../dev-log/PROGRESS.md "v4" 절, eval/followups/REPORT.md.

## 근거 (eval/real 120건)
- 피싱 60건 중 다른 사이트로 전송하는 폼은 1건뿐(가짜 DHL → xxxxxxxx.test). 59건은 자기 사이트 PHP 또는 JS 전송.
- 정상 쪽 다른 사이트 전송 3건은 뉴스레터(Mailchimp list-manage.com, Brevo sibforms.com).
- 즉 폼 action만 보는 현재 방식은 실제 피싱 키트의 전송처를 거의 못 본다.

## 설계
1. **실제 전송처 관찰 (코드)**: 폰 WebView·클라우드 샌드박스에서 페이지 스크립트의 전송 대상
   (fetch / XMLHttpRequest / sendBeacon / form action / JS로 바뀌는 action)을 추출.
   피싱 키트 단골 수집처는 코드 목록으로 즉시 탐지: 텔레그램 봇 API, 디스코드 웹훅, formsubmit/formspree/getform,
   Google Apps Script(script.google.com/macros) 등.
2. **Gemma 관계 판단 (점수 추가만)**: 새 필드 `form_destination` =
   same_org / known_service(결제·로그인·폼 서비스) / **unrelated** / unclear / no_form.
   unrelated + 개인정보 요구 → 가산. Gemma는 점수를 **더할 수만** 있음(코드가 잡은 +20을 지울 수 없음; 인젝션 대비).
3. **민감 범위 확대**: Gemma requested_actions(주민번호·계좌·연락처)를 다른 사이트 전송 판단에 반영.

## 검증
프롬프트 버전 변경 → 합성 18건 + 실제 120건(A/B) + 폰 10건 재측정, 지연 포함.
