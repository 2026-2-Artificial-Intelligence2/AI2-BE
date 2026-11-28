# 부장님 몰래 · 서버 통신 규약

프론트엔드가 서버와 주고받는 내용을 정리한 문서다. 게임 규칙과 수치는 [GAME_DESIGN.md](GAME_DESIGN.md)에 있다. 서버 안의 임시 화면 `src/main/resources/static/index.html`이 이 규약을 모두 쓰고 있으므로, 구현이 헷갈릴 때 함께 참고하면 된다.

## 연결

- **주소:** `ws(s)://<서버>/ws/lobby` (WebSocket 하나로 모든 것을 주고받는다).
- **화면 주소 허용:** 서버와 다른 주소에서 띄운 화면(예: Vite 개발 서버 `http://localhost:5173`)이 연결하려면 그 주소가 `game.allowed-origins` 설정에 있어야 한다. 기본값은 `http://localhost:*, http://127.0.0.1:*`이고, 서버와 같은 주소는 항상 허용된다. 배포한 프론트엔드 주소는 설정에 추가한다(예: `--game.allowed-origins=https://office-stock.example.com`). 허용되지 않은 주소는 연결 단계에서 403으로 거절된다.
- **보내는 형식:** 클라이언트 → 서버는 **한 줄 텍스트 명령**이다(예: `BUY MOON 10`). 앞뒤 공백은 무시한다.
- **받는 형식:** 서버 → 클라이언트는 **JSON 객체**이고, `type` 필드로 종류를 구분한다.
- **요청 ID:** 연결 직후 `{"type":"hello","requestIds":true}`를 받으면 명령을 `{"requestId":"42","command":"LOAN 100"}` 형식으로 보낼 수 있다. ID는 연결 안에서 고유하게 정하며 영문, 숫자, 밑줄, 하이픈 1~64자다.
- **성공:** JSON 요청에는 기존 결과 메시지에 이어 `{"type":"ack","requestId":"42"}`가 온다. 텍스트 명령은 기존 결과 메시지만 보낸다.
- **실패:** `{"type":"error","command":"LOAN","requestId":"42","message":"…"}`가 온다. 텍스트 명령에 대한 오류에는 `requestId`가 없다. `command`는 명령 이름(예: `MIC`, `VOICE SIGNAL`)이고 `message`는 화면에 보여 줄 수 있는 한국어 문장이다.
- `MIC`나 `VOICE SIGNAL`처럼 성공 확인이 필요 없는 명령은 텍스트로 보내면 된다.
- **이름 규칙:** 금액은 모두 원 단위 정수(`…Won`)이고, 시간은 판 경과 초(`second`, 출근 전은 음수)다. 종목은 고정 코드(`MINT`, `MOON`, `OURS` …)로 주고받고, 이름은 `catalog` 메시지로 받는다. 플레이어 이름은 `P1`, `P2` …(입장 순서로 정해지고 다시 하기를 해도 유지된다)이다.

## 한 판의 흐름

0. 연결하자마자: 보관한 열쇠가 있으면 `AUTOLOGIN 열쇠`. 없으면 회원은 `LOGIN`/`SIGNUP`, 비회원은 `GUEST 닉네임`(선택). 첫 화면의 명예의 전당은 `LEADERBOARD`로 받는다.
1. 연결 → `CREATE` 또는 `JOIN 초대코드` → `state` 수신. 솔로는 `SOLO 분` 한 번으로 방을 만들고 바로 시작한다.
2. 대기실: `state`가 바뀔 때마다 온다. 방장은 `LENGTH 분`으로 근무 시간을 고르고 `START`.
3. 시작: 모두에게 `state`(startedAt 채워짐) → `chart` → `catalog` → `work` → `market`이 온다.
4. 근무 중: 매초 `market`(훈계 중이면 대신 `meeting`)이 온다. 명령의 결과는 `work`, `fill`, `orders`, `myTrades` 등으로 온다.
5. 퇴근: `result`가 온다. 이후 게임 명령은 `error`("퇴근했습니다…")로 막히고, `CHAT`, `RESTART`(방장), `LEAVE`만 된다.
6. 방장이 `RESTART` → 같은 방이 대기 상태(`state`의 startedAt이 null, round +1)로 돌아간다.

## 클라이언트 → 서버 명령

### 회원·비회원과 명예의 전당

**회원**은 이름 + 비밀번호로 가입하고 어느 기기에서든 로그인한다. 판 결과와 꾸미기가 저장되고 솔로 기록이 명예의 전당에 오른다. 가입·로그인하면 **그 기기용 자동 로그인 열쇠**(`profile.token`)를 주므로, 화면은 localStorage에 보관했다가 다음 접속 때 `AUTOLOGIN`으로 보낸다. 서버에는 비밀번호의 BCrypt 해시와 열쇠의 SHA-256 해시만 남는다.

**비회원**은 닉네임만 정하고 바로 한다. 아무것도 저장하지 않는다. 화면은 `guest.notice`(기록이 남지 않는다는 안내)를 보여 준다. 닉네임 없이 들어오면 P1, P2…로 불린다.

| 명령 | 언제 | 설명 |
|---|---|---|
| `SIGNUP 김대리 비밀번호` | 방 밖 | 회원 가입. 이름은 한글·영문·숫자·`_`·`-` 2~10자(공백 없음), 전체에서 겹치지 않는다(대소문자 무시), `P1` 같은 이름은 안 된다. 비밀번호는 6~64자이고 공백이 있어도 된다(이름 뒤 나머지 전부). `profile`(열쇠와 `recoveryCode` 포함)이 온다. 복구 코드는 이때 한 번만 오므로 화면이 꼭 보여 주고 저장하라고 안내한다. |
| `LOGIN 김대리 비밀번호` | 방 밖 | 로그인. 이 기기용 새 열쇠를 준다. 같은 이름으로 5번 틀리면 1분 동안 막힌다. |
| `RESET 김대리 ABCD-EFGH-JKMN-PQRS 새비밀번호` | 방 밖 | 비밀번호를 잊었을 때. 이름 + 복구 코드(대소문자·하이픈·공백 무시)로 새 비밀번호를 정한다. 모든 기기의 로그인을 풀고 이 기기에 새 열쇠를 준다. 쓴 코드는 버리고 새 `recoveryCode`를 `profile`에 실어 준다. 틀리면 로그인과 함께 5번에 1분 막힌다. |
| `AUTOLOGIN 열쇠` | 방 밖 | 보관한 열쇠로 자동 로그인. 성공하면 `profile`(열쇠 없음), 실패하면 `profileFailed`(열쇠를 지우고 로그인 화면으로). |
| `LOGOUT` | 방 밖 | 이 기기의 열쇠를 지운다. 다른 기기의 로그인은 그대로다. `loggedOut`이 온다. |
| `GUEST 닉네임` | 방 밖 | 비회원으로 한다. 이름 규칙은 회원과 같고 회원 이름과 겹치면 안 된다. `guest`가 온다. |
| `PROFILE NAME 새이름` | 회원, 언제나 | 이름을 바꾼다. 이미 들어가 있는 방에서는 다음 방부터 바뀐다. |
| `PROFILE CUSTOM {"hair":"red"}` | 회원, 언제나 | 꾸미기 설정 저장. JSON 객체, 2,000자 이내. 내용은 프론트엔드가 정한다. |
| `PROFILE PASSWORD 새비밀번호` | 회원, 언제나 | 비밀번호를 바꾼다. `notice`가 온다. |
| `PROFILE RECOVERY` | 회원, 언제나 | 복구 코드를 새로 만든다. 예전 코드는 더는 쓸 수 없다. `{"type":"recoveryCode","code":"…"}`가 온다. |
| `PROFILE DELETE 비밀번호` | 회원, 방 밖 | 회원 탈퇴. 비밀번호를 확인하고 프로필·모든 기기의 로그인·판 결과(명예의 전당 기록 포함)를 지운다. `{"type":"accountDeleted","message":"…"}`가 오면 화면은 보관한 열쇠를 지우고 입장 화면으로 간다. |
| `RECORDS` | 회원, 언제나 | 내 전적. `records`가 온다(아래). |
| `LEADERBOARD` | 언제나 | 솔로 명예의 전당(`leaderboard`). 로그인하지 않아도 된다. |

- 방에서는 회원 이름이나 비회원 닉네임으로 불린다. `state.members[].label`, `youLabel`, 메신저의 `from`·`to`, `CHAT 이름 내용`의 받는 사람, `result`의 `player`가 모두 그 이름이다. `state.members[].member`가 true면 회원이다.
- 같은 회원으로 같은 방에 두 번(다른 창) 들어갈 수 없고, 방에 같은 이름이 있으면 들어갈 수 없다.
- 퇴근 정산 때 회원의 결과만 저장된다. `result.recorded`(저장됐는지)와 `result.recordNotice`(안내 문구)가 온다.

```json
{"type":"records",
 "summary":{"games":12,"soloGames":5,"multiGames":7,"multiWins":2,"bestReturnPercent":31.5,"averageReturnPercent":3.2,
            "totalProfitWon":4520000,"bankrupt":1},
 "recent":[{"mode":"multi","minutes":10,"playerCount":3,"rank":1,"profitWon":1250000,"returnPercent":12.5,
            "bankrupt":false,"finishedAt":"2026-11-28T03:12:45Z"}, …]}
```

`recent`는 최근 20판(최근 것부터)이다. `rank`는 그 판의 순위이고, 순위를 저장하기 전(V3 이전)에 끝난 판은 null이다. 판이 없으면 `bestReturnPercent`·`averageReturnPercent`가 null이다.

### 방

| 명령 | 언제 | 설명 |
|---|---|---|
| `CREATE` | 방 밖 | 방을 만들고 방장이 된다. |
| `JOIN 123456` | 방 밖 | 초대 코드로 입장한다(시작 전에만, 최대 4명). |
| `SOLO 10` | 방 밖 | 혼자 하는 방을 만들고 바로 시작한다. 회원만 명예의 전당에 오르고, 비회원은 해 볼 수만 있다. 근무 시간은 5·10·15·20분 중 하나(생략하면 10분). 다시 하기(`RESTART` → `START`)도 된다. |
| `RESUME 123456 열쇠` | 방 밖 | 새로고침이나 연결 끊김 뒤 같은 자리로 돌아간다. 열쇠는 `state.token`이다. 실패하면 `resumeFailed`가 온다. |
| `LENGTH 15` | 대기실, 방장 | 근무 시간(분, 1~60)을 정한다. |
| `START` | 대기실, 방장 | 2명 이상이면 시작한다. |
| `RESTART` | 퇴근 뒤, 방장 | 같은 방의 대기실로 돌아간다. |
| `LEAVE` | 언제나 | 방을 나간다. 그 연결에 `closed`가 온다. 방장이 대기실에서 나가면 방이 닫힌다. 팀 방의 방장이 게임 중이나 퇴근 뒤에 보내면 방을 닫지 않고 판을 끝내 모두 같은 방 대기실로 돌린다(`RESTART`처럼 startedAt이 null, round +1인 `state`가 오고 `closed`는 오지 않는다). |

### 화면

| 명령 | 설명 |
|---|---|
| `SWITCH` | Tab 키. 업무 창 ↔ 주식 창, 메신저 창에서는 업무 창으로 간다. 결과로 `view`가 온다. 훈계 중에는 안 된다. |
| `MESSENGER` | 메신저 창을 연다. 결과로 `view`가 온다. |
| `NEWS HISTORY` | 이번 판의 뉴스·찌라시 전체를 `newsHistory`로 받는다(훈계 중에는 안 된다). |

### 업무

| 명령 | 설명 |
|---|---|
| `WORK 업무번호 답` | 업무 창에서만 낼 수 있다. 점심시간에는 잠긴다. 답은 업무 종류에 따라 엑셀은 합계 숫자(쉼표 허용), 결재는 `APPROVE`/`REJECT`, 메일은 폴더 이름(`고객 문의`처럼 띄어쓰기 포함)이다. 정답이면 `work`(다음 업무)가 오고, 틀리면 종류별 안내가 `error`로 온다. 이미 낸 번호면 무시된다. |

### 주식 (주식 창에서만)

| 명령 | 설명 |
|---|---|
| `BUY MOON 10` / `SELL MOON 10` | 수량 시장가 주문. 바로 체결되고 `work`, `fill`, `myTrades`가 온다. 거래 비용(`catalog.feeBps`)이 붙는다. |
| `BUY MOON 10 x3` | 신용 매수(배수는 `catalog.margin.leverages` 중 하나). 1/배수만 현금으로 내고 나머지는 그 종목의 신용 빚이 된다. |
| `BUYWON MOON 50000` | 금액(내 돈 기준) 안에서 최대한 산다. 끝에 `x3`을 붙이면 그 금액의 3배어치를 신용으로 산다. |
| `SELLWON MOON 10000` | 그 금액 이상을 받는 데 필요한 만큼 판다. |
| `LIMIT BUY MOON 980 30` / `LIMIT SELL MOON 1100 20` | 지정가 주문을 건다(가격, 수량). 개수 제한은 없다(안전장치 100건). 매수는 가격×수량과 거래 비용을 묶는다. 결과로 `work`(묶인 현금)와 `orders`가 온다. 조건이 맞으면 언제든(화면과 상관없이) 체결되고, 그때 `fill`(`limit: true`)·`orders`·`work`·`myTrades`가 온다. |
| `CANCEL 7` | 내 지정가 주문을 취소한다. |
| `LOAN 50000` | 비상 대출(한 판에 한 번, 1원~상한). 훈계 중이 아니면 어느 창에서나 된다. 모두에게 `notice`가 간다. |

### 음성 대화 (방 안 언제나, 솔로 제외)

음성은 브라우저끼리 WebRTC로 직접 주고받는다. 서버는 참여 상태를 `state.members`에 싣고, 연결 정보(offer·answer·ICE 후보)를 받는 사람에게 그대로 전달만 한다. 확인용 화면(index.html)의 `voiceJoin`·`syncVoice`·`onVoiceSignal`이 참고 구현이다.

| 명령 | 설명 |
|---|---|
| `VOICE JOIN` | 음성에 참여한다. 모두에게 `state`가 가고 `members[].voice`가 true가 된다. |
| `VOICE LEAVE` | 음성에서 나간다. 연결이 끊기거나 방을 나가도 저절로 빠진다(재접속 뒤에는 다시 JOIN). |
| `VOICE MUTE` / `VOICE UNMUTE` | 음소거 상태를 모두에게 알린다(`members[].muted`). 실제로 소리를 끄는 것은 화면이 트랙을 끄는 것이다. |
| `VOICE SIGNAL 이름 {json}` | 음성에 참여한 같은 방 사람에게 연결 정보를 보낸다. JSON 객체, 16,000자 이내, 1초에 60건까지. 받는 쪽에는 `voiceSignal`이 온다. |

연결 순서(권장): `state`를 받을 때마다 `voice`가 true이고 `online`인 다른 사람과 연결이 없으면 `RTCPeerConnection`을 만든다. 내 이름이 사전순으로 앞서면 offer를 만들어 보내고, 아니면 상대의 offer를 기다린다. offer를 받으면 answer를 보내고, ICE 후보는 오는 대로 서로 보낸다. 목록에서 빠진 사람의 연결은 닫는다.

### 메신저

| 명령 | 설명 |
|---|---|
| `CHAT ALL 내용` | 단체방. 게임 중에는 메신저 창에서만, 대기실과 퇴근 뒤에는 언제나 보낼 수 있다. 200자 이하, 0.4초 간격. |
| `CHAT P2 내용` | 귓속말(보낸 사람과 받는 사람에게만 간다). |
| `MIC 35` | 내 마이크 소리 크기(0~100 정수). 브라우저에서 재서 바뀔 때마다 보낸다(0.1초 간격 정도). 대기실·게임 중·퇴근 뒤 언제나 된다. 게임 중 50 이상이 1초 이어지면 부장님이 그 사람에게 걸어간다(GAME_DESIGN.md "부장님이 소리를 듣는다"). 같은 크기가 이어지면 다시 보내지 않아도 서버가 매초 마지막 값으로 잰다. |
| `MIC OFF` | 마이크를 껐다. 연결이 끊기거나 방을 나가도 같은 처리를 한다. |
| `LEAK 내용` | 익명 찌라시(게임 중, 메신저 창에서, 한 사람이 한 판에 세 번까지 45초 간격, 60자). 모두에게 찌라시로 뜨고 가격은 움직이지 않는다. |
| `LEAK_ARTICLE {"headline":"제목","body":"본문"}` | 제목 60자, 본문 300자 이내. `LEAK`와 같은 횟수·간격 제한을 적용한다(`catalog.leak`의 `perRound`, `gapSeconds`). 너무 빠르면 "N초 뒤에 다시 뿌릴 수 있습니다.", 다 쓰면 "…3번까지…" 오류가 온다. 본문은 `market.newsBody`와 `newsHistory.items[].body`로 전달한다. |

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
- `solo`가 true면 솔로 방이다. 메신저·익명 찌라시가 없고(`CHAT`, `LEAK`, `MESSENGER`는 error), 아무도 들어올 수 없으며, `LENGTH`는 5·10·15·20분만 된다.
- `members`는 입장 순서다. 메신저의 받는 사람 목록으로 쓴다. `offline`은 연결이 끊긴 플레이어 UUID다.

### `catalog` — 종목 목록과 대출 조건

게임 시작과 재접속 때 온다. **종목 목록은 판마다 다르다.** 서버가 판마다 일반 종목 12개를 무작위로 상장하고(기초 종목이 뽑힌 레버리지 종목은 함께), 이 판의 종목만 보낸다. `market`의 `prices`·`pools`·`books`·`candles`와 `chart`도 같은 종목만 담는다. 종목 목록을 고정해 두지 말고 이 메시지로 매 판 다시 그린다.

`sector`는 업종이다(예: 은행, 항공, 반도체). 시세표에 종목 이름과 함께 보여 주면 금리·유가 같은 외부 뉴스가 어떤 종목에 닿는지 읽기 쉽다. 레버리지 종목은 기초 종목의 업종이 온다.

```json
{"type":"catalog",
 "stocks":[{"id":"MINT","name":"민트식품","sector":"식품"},{"id":"MOON","name":"달나라로봇","sector":"로봇"},
           {"id":"OURS","name":"우리회사","sector":"IT 서비스"},{"id":"SSCAR","name":"삼성자동차","sector":"자동차"}, …,
           {"id":"MOON3X","name":"달나라로봇 3X","sector":"로봇","underlying":"MOON","leverage":"3.0"},
           {"id":"OURSINV","name":"우리회사 인버스","sector":"IT 서비스","underlying":"OURS","leverage":"-1.0"}],
 "loan":{"interestPercent":20,"maxWon":10000000},
 "feeBps":20,
 "margin":{"leverages":[2,3,5],"interestBpsPerMinute":10,"maintenancePercent":10}}
```

- `underlying`·`leverage`가 있으면 레버리지·인버스 종목이다(배수는 문자열). 차트에 기초 종목의 뉴스도 표시하면 된다.
- `feeBps`는 거래 비용(1bp = 0.01%)이다. 매수는 대금 + ⌈대금×feeBps÷10000⌉을 내고, 매도는 대금 − 비용을 받는다.
- `margin`은 신용 매수 조건이다. 담보 비율이 `maintenancePercent` 아래로 떨어지면 서버가 반대매매한다.

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
 "coughBeat":-1,
 "boss":{"mood":"annoyed","atDesk":true,"line":"영 시원찮네.","lineSecond":312}}
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
| `portfolio` | 보유 종목별 원가·평균 매수가·청산가·손익, 신용 빚(`debtWon`)과 담보 비율(`marginRatioPercent`, 빚이 없으면 100), 확정 손익. 청산가는 "지금 혼자 전부 팔면 받을 금액(거래 비용 뺀 뒤)"이고, 퇴근 정산도 이 값으로 한다. |
| `suspicion` | 내 업무 게이지(의심, 0~100). 나에게만 온다. 40 이상 주황, 70 이상 빨강으로 보여주면 된다. |
| `colleagues` | 동료 목록 `[{"label":"P1","meeting":false}, …]`. 훈계 중인지만 알려준다(동료의 게이지는 보내지 않는다). 내 것은 없다. |
| `boss` | 부장님 상태. `mood`는 부장님 손익으로 정한 기분(`furious` 격노 −8% 이하, `annoyed` 짜증 −3% 이하, `normal`, `pleased` +3% 이상, `elated` +8% 이상). `atDesk`가 true면 자기 자리(서쪽 −5.3, 0)에 앉아 있다(순찰 사이, 발소리 없음). `line`은 매매 뒤 혼잣말로 6초 동안만 오고(그 밖에는 null), `lineSecond`는 말한 판 경과 초다. 기분은 숫자로 보여 주지 말고 소리·말로만 드러낸다. |
| `micLevels` | 마이크를 켠 사람(나 포함)의 소리 크기 `{"P1":35,"P2":0}`. 마이크를 켜지 않은 사람은 없다. 바로바로 바뀌는 값은 `mic` 메시지로 온다. |
| `newsNumber`, `newsKind`, `headline`, `newsBody`, `newsSecond`, `newsSymbols` | 가장 최근의 속보·찌라시. `newsNumber`가 커질 때만 팝업을 띄운다. 재접속 직후에는 띄우지 않는다. |
| `beat`, `walking`, `step` | 부장님 순찰. 2초 박자(`beat`)마다 바뀌고, `walking`이면 `step.from → step.to`로 걷는 발소리를 낸다. `listener`는 내 자리의 위치와 바라보는 방향(`forwardZ`)이다. 단위는 미터. 같은 `beat`는 한 번만 재생한다. |
| `coughBeat` | 의심이 70을 넘은 박자. 값이 커지면 부장님 쪽(`step.to`)에서 헛기침 소리를 낸다(나에게만 온다). |

### `meeting` — 훈계 중

```json
{"type":"meeting","newsNumber":3,"reason":"market"}
```

`reason`은 `market`(주식 창 적발), `messenger`(메신저 창 적발), `idle`(업무 방치), `mood`(부장님이 크게 잃어 격노해서 잘못 없이 불려 감)이다. 부장님이 크게 벌어 신났을 때는 한 사람당 한 번 적발을 봐주고, 그 사람에게만 `notice`("부장님이 기분이 좋아서 이번 한 번은 봐줬어요…")가 온다. 퇴근 결과의 `trades`에는 부장님 매매도 `player`가 "부장님"으로 들어간다(플레이어는 이 이름을 쓸 수 없다). 훈계 중에는 컴퓨터를 볼 수 없으므로 시세·팝업이 오지 않는다. `newsNumber`로 놓친 속보 번호만 맞춰 두고, 끝나면 다시 `market`(view `WORK`)이 온다.

### `view` — 화면 전환 결과

```json
{"type":"view","view":"MARKET"}
```

### `work` — 업무와 잔고

```json
{"type":"work","desk":{"rewardWon":35000,
 "task":{"number":4,"kind":"approval","title":"전자결재 · 국내 출장비",
         "data":{"docNo":"결재-2923","drafter":"박사원","category":"국내 출장비","item":"제주 박람회 참가",
                 "rule":"국내 출장비는 하루 30만 원 이하만 승인한다.","amountWon":298000}},
 "balance":{"cashWon":8945600,"workIncomeWon":105000,"completedTasks":3,"loanRepayWon":0,"reservedWon":475000,"marginDebtWon":844290}}}
```

업무 `kind`별 `data`:

| kind | data | 답(`WORK 번호 답`) |
|---|---|---|
| `sheet` | `sheet`(시트 이름), `unit`(`천원`), `rows`(`[{"name","amount"}]`), `targetCell`(예: `B7`) | 합계 숫자 |
| `approval` | `docNo`, `drafter`, `category`, `item`, `rule`(규정 문장), `amountWon`, (식대면) `people` | `APPROVE` 또는 `REJECT` |
| `mail` | `from`, `subject`, `body`, `folders`(고를 폴더 목록) | 폴더 이름 |

`cashWon`은 지금 쓸 수 있는 현금이다(신용 빚을 다 못 갚으면 음수일 수 있다). 지정가 매수에 묶인 돈(`reservedWon`)은 따로 센다. `loanRepayWon`은 퇴근 때 갚을 대출 원리금, `marginDebtWon`은 신용 빚 합계다. 업무 번호가 바뀔 때만 업무 창을 다시 그리면 된다(매매 때도 잔고 때문에 `work`가 온다).

### `fill` — 체결

```json
{"type":"fill","symbol":"MOON","side":"신용 3배 매수","quantity":50,"amount":1266436,"feeWon":2528,
 "borrowedWon":844290,"debtRepaidWon":0,"limit":false,"forced":false}
```

`side`는 `매수`, `매도`, `지정가 매수`, `지정가 매도`, `신용 N배 매수`, `반대매매`이다. `amount`는 매수면 낸 돈(대금+비용), 매도면 받은 돈(대금−비용)이다. `borrowedWon`은 신용으로 빌린 돈, `debtRepaidWon`은 매도 대금에서 먼저 갚은 신용 빚이다. `limit: true`면 걸어 둔 지정가가 체결된 것, `forced: true`면 반대매매다. 둘 다 알림을 띄운다.

### `orders` — 내 지정가 주문 목록

```json
{"type":"orders","orders":[{"id":7,"symbol":"MINT","side":"매수","limitWon":950,"quantity":5,"reservedWon":4750,"placedSecond":12}]}
```

주문을 걸고, 취소하고, 체결될 때와 재접속할 때 전체 목록이 온다.

### `myTrades` — 이번 판 내 거래

```json
{"type":"myTrades","trades":[{"second":1,"player":"P2","symbol":"MOON","side":"매수","quantity":10,"amountWon":10139,"returnPercent":null,"profitWon":null}]}
```

`returnPercent`는 그 거래의 수익률(%, 소수 첫째 자리), `profitWon`은 같은 기준의 손익(원)이다. 매도는 체결 때 평균 매수가(거래 비용 포함) 대비 실현 수익률이 들어 있고, 매수는 판 중에는 `null`이다가 퇴근 결과(`result.trades`)에서 "퇴근 때 시세로 팔았다면"의 수익률로 채워진다.

### `newsHistory` — 알림창

```json
{"type":"newsHistory","items":[{"number":1,"second":2,"kind":"rumor","symbols":[],"headline":"…","body":null}, …]}
```

속보는 `headline`이 "종목 이름 · 제목"(여러 종목 뉴스는 제목만)이고, `body`는 본문이다(없으면 null).

### `voiceConfig` — 음성 연결에 쓸 서버

`VOICE JOIN`을 보낸 사람에게만, `state`보다 먼저 온다. `RTCPeerConnection({iceServers})`에 그대로 넣는다. TURN(중계)이 들어 있으면 휴대폰 데이터망에서도 연결된다(README "음성 대화와 TURN").

```json
{"type":"voiceConfig","iceServers":[{"urls":"stun:stun.l.google.com:19302"},
  {"urls":["turn:relay.example.com:3478"],"username":"u","credential":"p"}]}
```

### `voiceSignal` — 음성 연결 정보

```json
{"type":"voiceSignal","from":"김대리","data":{"sdp":{"type":"offer","sdp":"v=0…"}}}
{"type":"voiceSignal","from":"김대리","data":{"candidate":{"candidate":"candidate:…","sdpMid":"0","sdpMLineIndex":0}}}
```

`data`는 보낸 사람이 `VOICE SIGNAL`에 넣은 JSON 그대로다. `state.members[]`에는 `voice`(참여 여부)와 `muted`(음소거)가 들어 있다.

### `mic` — 소리 크기

누가 `MIC`를 보내면 같은 방 모두(보낸 사람 포함)에게 온다. 한 사람당 0.15초에 한 번까지만 바로 오고, 그 사이 값은 `market.micLevels`로 따라잡는다. `on`이 false면 그 사람이 마이크를 껐거나 연결이 끊긴 것이다.

```json
{"type":"mic","player":"P2","level":42,"on":true}
{"type":"mic","player":"P2","level":0,"on":false}
```

### `profile`, `profileFailed`, `guest`, `loggedOut` — 회원·비회원

```json
{"type":"profile","member":true,"name":"김대리","customization":{"hair":"red"},"token":"Q2x…"}
{"type":"profileFailed","message":"로그인이 풀렸습니다. 다시 로그인하세요."}
{"type":"guest","name":"손님","notice":"비회원은 판 결과와 꾸미기가 저장되지 않고 명예의 전당에도 오르지 않습니다. 기록을 남기려면 회원 가입하세요."}
{"type":"loggedOut"}
```

`token`은 `SIGNUP`·`LOGIN` 때만 온다. 이 기기의 자동 로그인 열쇠이니 localStorage에 보관하고 다른 사람에게 보여 주면 안 된다. 잃어버려도 이름과 비밀번호로 다시 로그인하면 된다.

### `leaderboard` — 솔로 명예의 전당

근무 시간(5·10·15·20분)별 상위 10명이다. 최고 수익률 순이고, 판 수와 평균 수익률을 함께 준다. 수익률 = (퇴근 순자산 − 시작 자금) ÷ 시작 자금 × 100.

```json
{"type":"leaderboard","boards":[
  {"minutes":5,"entries":[{"rank":1,"name":"김대리","bestReturnPercent":12.34,"games":7,"averageReturnPercent":3.1}, …]},
  {"minutes":10,"entries":[]}, …]}
```

### `notice` — 사내 소식

```json
{"type":"notice","message":"P2이(가) 비상 대출 10,000,000원을 받았습니다."}
{"type":"notice","message":"P2의 달나라로봇 신용 포지션이 반대매매됐습니다."}
```

### `chat`, `chatHistory`, `leakSent` — 메신저

```json
{"type":"chat","message":{"id":3,"round":1,"second":15,"from":"P2","to":null,"text":"달나라 지금 사야 함","system":false}}
{"type":"chat","message":{"id":4,"round":1,"second":21,"from":"사내 알림","to":null,"text":"P2 님이 주식하다 부장님께 불려 갔어요.","system":true}}
{"type":"chatHistory","messages":[{"id":1,"second":-1,"from":"P2","to":null,"text":"안녕하세요"}, …]}
{"type":"leakSent","remaining":2,"gapSeconds":45,"message":"익명 찌라시를 뿌렸습니다. … 이번 판에 2번 더 뿌릴 수 있어요(45초 뒤부터)."}
```

`to`가 null이면 단체방, 이름이면 귓속말이다. `second`가 −1이면 게임 밖(대기실·퇴근 뒤) 메시지다. `chatHistory`는 입장과 재접속 때 오며, 내가 볼 수 있는 것(단체방과 내 귓속말, 최근 300개)만 담긴다. 익명 찌라시는 다른 사람에게 `market`의 찌라시로 보인다. 메신저 기록은 방이 유지되는 동안 판이 바뀌어도 남는다. `round`는 몇 번째 판의 메시지인지(같은 방에서 다시 하면 1씩 늘어난다)라서, 화면은 `round`가 바뀌는 곳에 구분선을 긋는다.

`system`이 true면 서버가 보낸 사내 알림이다(`from`은 "사내 알림"). 팀 방에서 누가 훈계로 불려 가면(주식 창·메신저 창 적발, 업무 방치) 그 순간 단체방에 이유와 함께 온다. 화면은 사람이 쓴 말과 다르게 보여 주면 된다.

### `result` — 퇴근 결과

```json
{"type":"result","you":"P2","startingCashWon":100000,
 "standings":[{"rank":1,"player":"P1","netWorthWon":100000,"cashWon":100000,"stockValueWon":0,"loanRepayWon":0,"workIncomeWon":0,"completedTasks":0,"holdings":{}},
              {"rank":2,"player":"P2","netWorthWon":97890,"cashWon":95456,"stockValueWon":14434,"loanRepayWon":12000,"workIncomeWon":350,"completedTasks":1,"holdings":{"MINT":5,"MOON":10}}],
 "awards":[{"award":"scolded","players":["P2"],"value":2},{"award":"biggestLoss","players":["P2"],"value":-2110},{"award":"hardestWorker","players":["P2"],"value":1}],
 "trades":[{"second":17,"player":"P2","symbol":"MINT","side":"지정가 매수","quantity":5,"amountWon":4755,"returnPercent":3.2,"profitWon":152}, …],
 "news":[…],
 "leaks":[{"player":"P2","newsNumber":1,"text":"우리회사 회장님 은퇴설"}]}
```

순자산 = 현금 + 주식 청산가 − 대출 원리금 − 신용 빚이다. `startingCashWon`은 출근 때 받은 현금으로, 순자산에서 빼면 이번 판 손익이다. `standings`의 각 줄에는 `marginDebtWon`과 `bankrupt`(순자산 음수면 true)가 더 있다. 같은 금액이면 같은 순위다. 퇴근 뒤 재접속해도 다시 온다.

`awards`는 오늘의 직원상이다. `scolded`는 훈계(적발·업무 방치)를 가장 많이 받은 사람(`value` = 횟수), `biggestLoss`는 가장 많이 잃은 사람(`value` = 손익, 음수), `hardestWorker`는 업무를 가장 많이 한 사람(`value` = 건수)이다. 동점이면 `players`에 모두 들어간다. 해당하는 사람이 없으면(아무도 안 혼남, 아무도 안 잃음, 업무 0건) 그 상은 빠진다. 혼자 하기에서도 오지만 화면이 보여 줄지는 정한다.

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
