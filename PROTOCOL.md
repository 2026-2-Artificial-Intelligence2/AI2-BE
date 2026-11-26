# 부장님 몰래 · 서버 통신 규약

프론트엔드가 서버와 주고받는 내용을 정리한 문서다. 게임 규칙과 수치는 [GAME_DESIGN.md](GAME_DESIGN.md)에 있다. 서버 안의 임시 화면 `src/main/resources/static/index.html`이 이 규약을 모두 쓰고 있으므로, 구현이 헷갈릴 때 함께 참고하면 된다.

## 연결

- **주소:** `ws(s)://<서버>/ws/lobby` (WebSocket 하나로 모든 것을 주고받는다).
- **화면 주소 허용:** 서버와 다른 주소에서 띄운 화면(예: Vite 개발 서버 `http://localhost:5173`)이 연결하려면 그 주소가 `game.allowed-origins` 설정에 있어야 한다. 기본값은 `http://localhost:*, http://127.0.0.1:*`이고, 서버와 같은 주소는 항상 허용된다. 배포한 프론트엔드 주소는 설정에 추가한다(예: `--game.allowed-origins=https://office-stock.example.com`). 허용되지 않은 주소는 연결 단계에서 403으로 거절된다.
- **보내는 형식:** 클라이언트 → 서버는 **한 줄 텍스트 명령**이다(예: `BUY MOON 10`). 앞뒤 공백은 무시한다.
- **받는 형식:** 서버 → 클라이언트는 **JSON 객체**이고, `type` 필드로 종류를 구분한다.
- **실패:** 명령이 규칙에 맞지 않으면 `{"type":"error","message":"…"}`가 온다. `message`는 화면에 그대로 보여 줄 수 있는 한국어 문장이다. 상태는 바뀌지 않는다.
- **이름 규칙:** 금액은 모두 원 단위 정수(`…Won`)이고, 시간은 판 경과 초(`second`, 출근 전은 음수)다. 종목은 고정 코드(`MINT`, `MOON`, `OURS` …)로 주고받고, 이름은 `catalog` 메시지로 받는다. 플레이어 이름은 `P1`, `P2` …(입장 순서로 정해지고 다시 하기를 해도 유지된다)이다.

## 한 판의 흐름

1. 연결 → `CREATE` 또는 `JOIN 초대코드` → `state` 수신.
2. 대기실: `state`가 바뀔 때마다 온다. 방장은 `LENGTH 분`으로 근무 시간을 고르고 `START`.
3. 시작: 모두에게 `state`(startedAt 채워짐) → `chart` → `catalog` → `work` → `market`이 온다.
4. 근무 중: 매초 `market`(훈계 중이면 대신 `meeting`)이 온다. 명령의 결과는 `work`, `fill`, `orders`, `myTrades` 등으로 온다.
5. 퇴근: `result`가 온다. 이후 게임 명령은 `error`("퇴근했습니다…")로 막히고, `CHAT`, `RESTART`(방장), `LEAVE`만 된다.
6. 방장이 `RESTART` → 같은 방이 대기 상태(`state`의 startedAt이 null, round +1)로 돌아간다.

## 클라이언트 → 서버 명령

### 방

| 명령 | 언제 | 설명 |
|---|---|---|
| `CREATE` | 방 밖 | 방을 만들고 방장이 된다. |
| `JOIN 123456` | 방 밖 | 초대 코드로 입장한다(시작 전에만, 최대 4명). |
| `RESUME 123456 열쇠` | 방 밖 | 새로고침이나 연결 끊김 뒤 같은 자리로 돌아간다. 열쇠는 `state.token`이다. 실패하면 `resumeFailed`가 온다. |
| `LENGTH 15` | 대기실, 방장 | 근무 시간(분, 1~60)을 정한다. |
| `START` | 대기실, 방장 | 2명 이상이면 시작한다. |
| `RESTART` | 퇴근 뒤, 방장 | 같은 방의 대기실로 돌아간다. |
| `LEAVE` | 언제나 | 방을 나간다(방장이면 방이 닫힌다). 그 연결에 `closed`가 온다. |

### 화면

| 명령 | 설명 |
|---|---|
| `SWITCH` | Tab 키. 업무 창 ↔ 주식 창, 메신저 창에서는 업무 창으로 간다. 결과로 `view`가 온다. 훈계 중에는 안 된다. |
| `MESSENGER` | 메신저 창을 연다. 결과로 `view`가 온다. |
| `NEWS HISTORY` | 이번 판의 뉴스·찌라시 전체를 `newsHistory`로 받는다(훈계 중에는 안 된다). |

### 업무

| 명령 | 설명 |
|---|---|
| `WORK 업무번호 답` | 업무 창에서만 낼 수 있다. 점심시간에는 잠긴다. 정답이면 `work`(다음 업무)가 오고, 이미 낸 번호면 무시된다. |

### 주식 (주식 창에서만)

| 명령 | 설명 |
|---|---|
| `BUY MOON 10` / `SELL MOON 10` | 수량 시장가 주문. 바로 체결되고 `work`, `fill`, `myTrades`가 온다. |
| `BUYWON MOON 50000` | 금액 안에서 최대한 산다. |
| `SELLWON MOON 10000` | 그 금액 이상을 받는 데 필요한 만큼 판다. |
| `LIMIT BUY MOON 980 30` / `LIMIT SELL MOON 1100 20` | 지정가 주문을 건다(가격, 수량). 결과로 `work`(묶인 현금)와 `orders`가 온다. 조건이 맞으면 언제든(화면과 상관없이) 체결되고, 그때 `fill`(`limit: true`)·`orders`·`work`·`myTrades`가 온다. |
| `CANCEL 7` | 내 지정가 주문을 취소한다. |
| `LOAN 50000` | 비상 대출(한 판에 한 번, 1원~상한). 훈계 중이 아니면 어느 창에서나 된다. 모두에게 `notice`가 간다. |

### 메신저

| 명령 | 설명 |
|---|---|
| `CHAT ALL 내용` | 단체방. 게임 중에는 메신저 창에서만, 대기실과 퇴근 뒤에는 언제나 보낼 수 있다. 200자 이하, 0.4초 간격. |
| `CHAT P2 내용` | 귓속말(보낸 사람과 받는 사람에게만 간다). |
| `LEAK 내용` | 익명 찌라시(게임 중, 메신저 창에서, 한 판에 한 번, 60자). 모두에게 찌라시로 뜨고 가격은 움직이지 않는다. |

## 서버 → 클라이언트 메시지

예시의 모양은 실제 서버가 보낸 메시지와 같다. 긴 배열은 줄였고(`…`), 숫자는 예를 든 값이다.

### `state` — 방 상태

입장하거나, 누가 들어오고 나가거나, 연결이 끊기거나 돌아오거나, 근무 시간을 바꾸거나, 시작하거나, 다시 하기를 할 때 온다.

```json
{"type":"state","code":"310740","round":1,"sessionSeconds":900,
 "you":"01ba91e0-…","youLabel":"P2","token":"ZspTBes7uNV0XsO20_LkjsQL",
 "members":[{"label":"P1","host":true,"online":true},{"label":"P2","host":false,"online":true}],
 "room":{"hostId":"9d0d3f53-…","players":["9d0d3f53-…","01ba91e0-…"],"startedAt":null},
 "offline":[]}
```

- `token`은 **이 플레이어만 받는 재접속 열쇠**다. 탭별로 저장해 두었다가 `RESUME`에 쓴다(임시 화면은 sessionStorage에 둔다). 다른 사람에게 보여 주면 안 된다.
- `room.startedAt`은 ISO 시각 문자열이고, 시작 전이면 `null`이다. 방장 여부는 `you === room.hostId`로 알 수 있다.
- `members`는 입장 순서다. 메신저의 받는 사람 목록으로 쓴다. `offline`은 연결이 끊긴 플레이어 UUID다.

### `catalog` — 종목 목록과 대출 조건

게임 시작과 재접속 때 온다.

```json
{"type":"catalog","stocks":[{"id":"MINT","name":"민트식품"},{"id":"MOON","name":"달나라로봇"},{"id":"OURS","name":"우리회사"}],
 "loan":{"interestPercent":20,"maxWon":100000}}
```

### `chart` — 차트 전체

게임 시작과 재접속 때 온다. 이후에는 `market.candles`로 이어 붙인다.

```json
{"type":"chart","candleSeconds":10,
 "candles":{"MINT":[{"startSecond":-1800,"open":954,"high":956,"low":948,"close":955,"volume":2}, …], …},
 "news":[{"number":1,"second":2,"kind":"rumor","symbols":[],"headline":"…"}],
 "past":[{"second":-1800,"kind":"past","symbols":["MINT"],"headline":"민트초코 라면 출시 소식에 상승"}, …]}
```

- 봉은 시간순이고, 출근 전(`startSecond` 음수)부터 지금까지다. 출근 전 봉은 마지막 종가가 시작가와 같다.
- `news`는 지금까지의 속보(`news`)·찌라시(`rumor`), `past`는 출근 전 지난 소식이다. `symbols`가 비어 있으면 종목 없는 익명 찌라시다.

### `market` — 매초 시세

근무 중에 매초(그리고 누가 주문할 때마다) 온다. 훈계 중인 사람에게는 대신 `meeting`이 온다.

```json
{"type":"market","remainingSeconds":873,"lunchStartsInSeconds":333,"lunchEndsInSeconds":423,
 "view":"MARKET",
 "prices":{"MINT":1004,"MOON":993,"OURS":1001},
 "pools":{"MINT":{"shares":1000,"cashWon":1004210}, …},
 "books":{"MINT":{"priceWon":1004,"stepWon":5,"asks":[{"priceWon":1005,"shares":1}, …5칸],"bids":[{"priceWon":1000,"shares":2}, …5칸]}, …},
 "candles":{"MINT":{"startSecond":20,"open":1001,"high":1006,"low":1000,"close":1004,"volume":0}, …},
 "holdings":{"MOON":10},
 "portfolio":{"positions":{"MOON":{"shares":10,"costWon":10139,"avgPriceWon":1013,"valueWon":9852,"profitWon":-287,"profitPercent":-2.8}},"realizedWon":0},
 "newsNumber":3,"newsKind":"news","headline":"달나라로봇 · 야간 배송 로봇 실증 승인","newsBody":"…","newsSecond":20,"newsSymbols":["MOON"],
 "beat":10,"walking":true,"step":{"from":{"x":1,"z":-2},"to":{"x":3,"z":-2},"listener":{"position":{"x":1,"z":-1},"forwardZ":1}},
 "coughBeat":-1}
```

| 필드 | 뜻 |
|---|---|
| `remainingSeconds` | 퇴근까지 남은 초. 120 이하면 장 마감 구간이다. |
| `lunchStartsInSeconds`, `lunchEndsInSeconds` | 점심 시작·끝까지 남은 초(지나면 음수). 시작 ≤ 0 < 끝이면 점심이다. |
| `view` | 내 화면: `WORK`, `MARKET`, `MESSENGER`. 서버가 정한 값이 기준이다. |
| `prices` | 기준가(풀 현금 ÷ 주식 수, 내림). |
| `pools` | 풀 상태. 예상 체결 금액을 화면에서 계산할 때 쓴다. 매수 대금 = ⌈S·C ÷ (S−q)⌉ − C, 매도 대금 = C − ⌈S·C ÷ (S+q)⌉. |
| `books` | 호가(매도 `asks`는 가까운 순, 매수 `bids`도 가까운 순). `shares`는 바로 앞 칸에서 그 가격까지 움직이는 데 필요한 주식 수다. |
| `candles` | 종목별 진행 중인 봉. `startSecond`가 마지막 봉과 같으면 바꾸고, 다르면 새로 붙인다. |
| `holdings` | 내 보유 수량(지정가 매도에 묶인 것 포함). |
| `portfolio` | 보유 종목별 원가·평균 매수가·청산가·손익, 확정 손익. 청산가는 "지금 혼자 전부 팔면 받을 금액"이고, 퇴근 정산도 이 값으로 한다. |
| `newsNumber`, `newsKind`, `headline`, `newsBody`, `newsSecond`, `newsSymbols` | 가장 최근의 속보·찌라시. `newsNumber`가 커질 때만 팝업을 띄운다. 재접속 직후에는 띄우지 않는다. |
| `beat`, `walking`, `step` | 부장님 순찰. 2초 박자(`beat`)마다 바뀌고, `walking`이면 `step.from → step.to`로 걷는 발소리를 낸다. `listener`는 내 자리의 위치와 바라보는 방향(`forwardZ`)이다. 단위는 미터. 같은 `beat`는 한 번만 재생한다. |
| `coughBeat` | 의심이 70을 넘은 박자. 값이 커지면 부장님 쪽(`step.to`)에서 헛기침 소리를 낸다(나에게만 온다). |

### `meeting` — 훈계 중

```json
{"type":"meeting","newsNumber":3,"reason":"market"}
```

`reason`은 `market`(주식 창 적발), `messenger`(메신저 창 적발), `idle`(업무 방치)이다. 훈계 중에는 컴퓨터를 볼 수 없으므로 시세·팝업이 오지 않는다. `newsNumber`로 놓친 속보 번호만 맞춰 두고, 끝나면 다시 `market`(view `WORK`)이 온다.

### `view` — 화면 전환 결과

```json
{"type":"view","view":"MARKET"}
```

### `work` — 업무와 잔고

```json
{"type":"work","desk":{"task":{"number":4,"a1":40,"a2":63},"rewardWon":350,
 "balance":{"cashWon":89456,"workIncomeWon":1050,"completedTasks":3,"loanRepayWon":0,"reservedWon":4750}}}
```

`cashWon`은 지금 쓸 수 있는 현금이다. 지정가 매수에 묶인 돈(`reservedWon`)은 따로 센다. `loanRepayWon`은 퇴근 때 갚을 대출 원리금이다(0이면 대출 안 받음).

### `fill` — 체결

```json
{"type":"fill","symbol":"MOON","side":"매수","quantity":10,"amount":10139,"limit":false}
```

`side`는 `매수`, `매도`, `지정가 매수`, `지정가 매도`이다. `limit: true`면 걸어 둔 주문이 체결된 것이므로 알림을 띄운다.

### `orders` — 내 지정가 주문 목록

```json
{"type":"orders","orders":[{"id":7,"symbol":"MINT","side":"매수","limitWon":950,"quantity":5,"reservedWon":4750,"placedSecond":12}]}
```

주문을 걸고, 취소하고, 체결될 때와 재접속할 때 전체 목록이 온다.

### `myTrades` — 이번 판 내 거래

```json
{"type":"myTrades","trades":[{"second":1,"player":"P2","symbol":"MOON","side":"매수","quantity":10,"amountWon":10139}]}
```

### `newsHistory` — 알림창

```json
{"type":"newsHistory","items":[{"number":1,"second":2,"kind":"rumor","symbols":[],"headline":"…","body":null}, …]}
```

속보는 `headline`이 "종목 이름 · 제목"(여러 종목 뉴스는 제목만)이고, `body`는 본문이다(없으면 null).

### `notice` — 사내 소식

```json
{"type":"notice","message":"P2이(가) 비상 대출 10,000원을 받았습니다."}
```

### `chat`, `chatHistory`, `leakSent` — 메신저

```json
{"type":"chat","message":{"id":3,"second":15,"from":"P2","to":null,"text":"달나라 지금 사야 함"}}
{"type":"chatHistory","messages":[{"id":1,"second":-1,"from":"P2","to":null,"text":"안녕하세요"}, …]}
{"type":"leakSent","message":"익명 찌라시를 뿌렸습니다. 누가 뿌렸는지는 퇴근 뒤 공개됩니다."}
```

`to`가 null이면 단체방, 이름이면 귓속말이다. `second`가 −1이면 게임 밖(대기실·퇴근 뒤) 메시지다. `chatHistory`는 입장과 재접속 때 오며, 내가 볼 수 있는 것(단체방과 내 귓속말, 최근 300개)만 담긴다. 익명 찌라시는 다른 사람에게 `market`의 찌라시로 보인다.

### `result` — 퇴근 결과

```json
{"type":"result","you":"P2",
 "standings":[{"rank":1,"player":"P1","netWorthWon":100000,"cashWon":100000,"stockValueWon":0,"loanRepayWon":0,"workIncomeWon":0,"completedTasks":0,"holdings":{}},
              {"rank":2,"player":"P2","netWorthWon":97890,"cashWon":95456,"stockValueWon":14434,"loanRepayWon":12000,"workIncomeWon":350,"completedTasks":1,"holdings":{"MINT":5,"MOON":10}}],
 "trades":[{"second":17,"player":"P2","symbol":"MINT","side":"지정가 매수","quantity":5,"amountWon":4755}, …],
 "news":[…],
 "leaks":[{"player":"P2","newsNumber":1,"text":"우리회사 회장님 은퇴설"}]}
```

순자산 = 현금 + 주식 청산가 − 대출 원리금이다. 같은 금액이면 같은 순위다. 퇴근 뒤 재접속해도 다시 온다.

### 연결 관련

| 메시지 | 뜻 | 화면이 할 일 |
|---|---|---|
| `{"type":"resumeFailed","message":"…"}` | 방이 없거나 열쇠가 틀렸다. | 저장한 열쇠를 지우고 입장 화면으로 간다. |
| `{"type":"replaced","message":"…"}` | 다른 창이 이 자리를 가져갔다. 곧 연결이 끊긴다. | 자동 재접속을 하지 않는다(두 창이 서로 빼앗지 않게). |
| `{"type":"closed","message":"…"}` | 방이 닫혔거나(방장이 나감, 오래 비어 정리됨) 내가 나갔다. | 저장한 열쇠를 지우고 입장 화면으로 간다. |

## 재접속 요령

- 연결이 끊기면 열쇠가 있을 때 1초, 2초, 4초 … 최대 10초 간격으로 다시 연결하고 `RESUME`을 보낸다(임시 화면은 30번까지).
- 재접속하면 `state` → `chatHistory`가 오고, 게임 중이면 `chart` → `catalog` → `work` → `myTrades` → `orders` → `market`이 다시 온다. 퇴근 뒤면 `market` 대신 `result`가 온다.
- 연결이 끊긴 동안 서버는 그 사람의 화면을 업무 창으로 돌려 둔다(자리 비움). 대기실에서는 60초, 시작한 방은 모두가 5분 넘게 끊기면 정리된다.
- 서버를 다시 켜면 방이 모두 사라진다(메모리에만 있다).
