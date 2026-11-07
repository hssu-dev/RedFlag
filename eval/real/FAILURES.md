# Gemma 출력 실패 원인 분류 (eval/real, 맥 GPU, system_prompt_v2, 출력 상한 800 토큰)

대상: `results-gemma.jsonl` 120건 중 파싱 실패 또는 스키마 위반 32건 (정상 27 / 악성 5). 근거는 각 행의 raw 발췌.

| 원인 | 정상 | 악성 | 계 |
|---|---|---|---|
| 출력 토큰 잘림(상한 도달) | 0 | 0 | 0 |
| 조기 종료(EOS, 상한 미도달) | 1 | 0 | 1 |
| evidence 내 따옴표·개행 미이스케이프 | 0 | 0 | 0 |
| 키 생략 | 3 | 0 | 3 |
| 기타: 키 이름 변형(토큰 반복·누락) | 19 | 3 | 22 |
| 기타: 구조 오류(배열·객체 조기 종료/분리) | 4 | 0 | 4 |
| 기타: 값 오류(enum) | 0 | 2 | 2 |

- 최대 출력은 143 토큰(상한 800) → 상한 잘림 0건. 조기 종료(EOS)는 2건(1건은 키 생략과 겹쳐 키 생략으로 분류). 둘 다 상한 한참 아래(92·94 tok)에서 멈춤.
- JSON 문자열 안의 원시 개행: 0건, 미이스케이프 따옴표로 인한 파싱 실패: 0건 (파싱 실패 8건은 전부 구조/키 문제).
- 키 이름 변형은 키를 쓰긴 했지만 토큰이 반복·누락된 것(`addresses_ai_ai_analyzer`, `too_good_to_be_be_true`, `urgency_orthreat`, `claimed_brand_brand` 등). 값은 정상 위치에 있음.

| 라벨 | id | 원인 | 세부 | 출력 tok | raw 근거 |
|---|---|---|---|---|---|
| benign | ben_e_reverse_com | 조기 종료(EOS, 상한 미도달) | 조기 종료(EOS) 94 tok, 상한 800 | 94 | `サービスです。"], "claimed_brand": "e-reverse.com", "brand_matches_domain": "yes", "page_type": "other", "requested_actions": ["enter_contact_info"], "urgency_or_threat": false,` |
| benign | ben_66vip33_com | 키 생략 | 키 생략(값만 나열) | 109 | `given out by system."], "6666vip333.com", "no_brand_brand_matches_domain", "other", ["enter_contact_info", "enter_personal_id"], true, false, false, "suspicious", "high"}` |
| benign | ben_rumahawan4d_com | 키 생략 | 키 없이 값만 나열 + 조기 종료(EOS) | 92 | `S DEPOSIT 10% HARIAN ¦ DEPOSIT PULSA TANPA POTONGAN !"] , "rumahawan4d.com", "no", "other", ["enter_contact_info", "enter_password"], false, false, false, "suspicious", "` |
| benign | ben_vakilkadeh_com | 키 생략 | 키 생략(값만 나열) | 121 | `nfo", "requested_actions": ["enter_contact_info"], "urgency_or_threat": false, "too_good_to_be_true": false, "addresses_ai_analyzer": false, "safe", "confidence": "high"}` |
| benign | ben_bestattungen_co_at | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_be_be_true | 118 | `"too_good_to_be_be_true"` |
| benign | ben_drs_ag_de | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_to_be_true | 109 | `"too_good_to_to_be_true"` |
| benign | ben_humax_cinema_co_jp | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: urgency_orthreat, too_goodtobeatrue | 112 | `"urgency_orthreat", "too_goodtobeatrue"` |
| benign | ben_introarte_net | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_be_be_true | 114 | `"too_good_to_be_be_true"` |
| benign | ben_leonbets_com | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 124 | `"addresses_ai_ai_analyzer"` |
| benign | ben_luup_sc | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_be_be_true | 125 | `"too_good_to_be_be_true"` |
| benign | ben_mac_lyon_com | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: urgency_orthreat, too_goodtobeatrue | 113 | `"urgency_orthreat", "too_goodtobeatrue"` |
| benign | ben_mars4dsarana_com | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 129 | `"addresses_ai_ai_analyzer"` |
| benign | ben_rotary7070_org | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_be_be_true | 111 | `"too_good_to_be_be_true"` |
| benign | ben_sitevibes_io | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: urgency_orthreat, too_goodtobe_true, addresses_ai_ai_analyzer | 108 | `"urgency_orthreat", "too_goodtobe_true", "addresses_ai_ai_analyzer"` |
| benign | ben_stocks_vip_com | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 118 | `"addresses_ai_ai_analyzer"` |
| benign | ben_switchboard_lgbt | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 100 | `"addresses_ai_ai_analyzer"` |
| benign | ben_t1a_jp | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 113 | `"addresses_ai_ai_analyzer"` |
| benign | ben_tasova_gen_tr | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: urgency_orthreat, too_goodtobeatrue, addresses_ai_ai_analyzer | 133 | `"urgency_orthreat", "too_goodtobeatrue", "addresses_ai_ai_analyzer"` |
| benign | ben_theatreofnations_ru | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 105 | `"addresses_ai_ai_analyzer"` |
| benign | ben_thefaceshop_com_sg | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 109 | `"addresses_ai_ai_analyzer"` |
| benign | ben_vertexslotsonline_nl | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 117 | `"addresses_ai_ai_analyzer"` |
| benign | ben_weig_karton_de | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: claimed_brand_brand, too_good_to_be_be_true | 116 | `"claimed_brand_brand", "too_good_to_be_be_true"` |
| benign | ben_yonex_cn | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_to_be_true | 131 | `"too_good_to_to_be_true"` |
| malicious | mal_040 | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 124 | `"addresses_ai_ai_analyzer"` |
| malicious | mal_067 | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: too_good_to_be_be_true | 111 | `"too_good_to_be_be_true"` |
| malicious | mal_097 | 기타: 키 이름 변형(토큰 반복·누락) | 키 이름 변형: addresses_ai_ai_analyzer | 156 | `"addresses_ai_ai_analyzer"` |
| malicious | mal_030 | 기타: 값 오류(enum) | 값 오류: enum:requested_actions | 116 | `["enter_email", "enter_password"], "urgency_or_threat": false, "too_good_to_be_true": false, "addresses_ai_analyzer": false, "verdict": "malicious", "confidence": "high"}` |
| malicious | mal_146 | 기타: 값 오류(enum) | 값 오류: enum:requested_actions | 106 | `["enter_email", "enter_password"], "urgency_or_threat": false, "too_good_to_be_true": false, "addresses_ai_analyzer": false, "verdict": "malicious", "confidence": "high"}` |
| benign | ben_britreelsshop_uk | 기타: 구조 오류(배열·객체 조기 종료/분리) | 구조 오류(배열·객체 조기 종료/분리) | 123 | `{"evidence": ["Brit Reels Shop All Posts Business Esports Fashion Featured Gaming"], ["Discover top-quality fishing reels and tackle at Brit"]⏎, "claimed_brand": "Brit Re` |
| benign | ben_chopin_edu_pl | 기타: 구조 오류(배열·객체 조기 종료/분리) | 구조 오류(배열·객체 조기 종료/분리) | 124 | `{"evidence": ["Strona główna - UMFC", "Uniwersytet OFC Wydziały Władze i Struktura Uczelni"]}⏎,"claimed_brand": "UMFC",⏎"brand_matches_domain": "yes",⏎"page_type": "info"` |
| benign | ben_jaktvildmark_se | 기타: 구조 오류(배열·객체 조기 종료/분리) | 구조 오류(배열·객체 조기 종료/분리) | 143 | `{"evidence": ["Användarnamn eller e-postadress *Required", "Lösenord *Required"], "Köp nu, betala senare med Klarna Fri frakt för beställningar över 1500 kr"], "claimed_b` |
| benign | ben_leonardopen_com | 기타: 구조 오류(배열·객체 조기 종료/분리) | 구조 오류(배열·객체 조기 종료/분리) | 126 | `{"evidence": ["Leonardo officina italiana – leonardopen", "Momento zero collection View all Cigar"], "Leonardo Officina Italiana presents the first model in the Cavallucc` |
