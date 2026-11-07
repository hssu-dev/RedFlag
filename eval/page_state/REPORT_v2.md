# Page state: can Gemma tell the site's own page from a CDN check / block / host warning / dead site?

Mac, same LiteRT-LM runtime + gemma-4-E4B as the phone, greedy, thinking off, separate short conversation
(contracts/gemma_page_state.txt). Cases: eval/real sandbox observations (real) + hand-written vendor pages (synthetic).
Excluded: 28 empty observations (no title, text or inputs): the app already stops there ('empty_page', never safe), and Gemma called all of them 'unavailable'.

- **real** (224): Gemma 219/224 (98%) · code 223/224 (100%) · code+Gemma(ambiguous only) 222/224 (99%)
- **synthetic** (12): Gemma 10/12 (83%) · code 12/12 (100%) · code+Gemma(ambiguous only) 12/12 (100%)
- **all** (236): Gemma 229/236 (97%) · code 235/236 (100%) · code+Gemma(ambiguous only) 234/236 (99%)

Latency per Gemma call (Mac GPU): median 1281 ms, p90 1352 ms.
Gemma output unparseable: 2/236. Ambiguous cases that would reach Gemma on the phone: 21/236.

## Per label (Gemma / code)

| label | n | Gemma | code |
|---|---|---|---|
| loaded | 142 | 138 | 141 |
| challenge | 6 | 6 | 6 |
| blocked | 11 | 9 | 11 |
| host_warning | 67 | 67 | 67 |
| unavailable | 10 | 9 | 10 |

## Gemma confusion (label -> answer)

- loaded -> challenge: 2
- blocked -> None: 1
- unavailable -> blocked: 1
- loaded -> None: 1
- blocked -> challenge: 1
- loaded -> unavailable: 1

## Every miss

| id | source | label | Gemma | code |
|---|---|---|---|---|
| ben_assessorhub_net | real | blocked | None | blocked |
| mal_011 | real | loaded | challenge | loaded |
| mal_021 | real | unavailable | blocked | unavailable |
| mal_049 | real | loaded | None | loaded |
| mal_086 | real | loaded | challenge | loaded |
| mal_099 | real | loaded | loaded | unavailable |
| syn_turnstile_interactive | synthetic | blocked | challenge | blocked |
| syn_injection_unavailable | synthetic | loaded | unavailable | loaded |
