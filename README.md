# 부장님 몰래 · 백엔드

친구 2~4명이 같은 사무실에서 부장님 몰래 주식을 사고파는 멀티플레이 게임의 서버다. Spring Boot와 WebSocket으로 만들었다.

| 문서 | 내용 |
|---|---|
| [GAME_DESIGN.md](GAME_DESIGN.md) | 게임 규칙, 수치, 시뮬레이션 근거, 종목·뉴스 추가 방법 |
| [PROTOCOL.md](PROTOCOL.md) | 프론트엔드가 서버와 주고받는 명령과 메시지 |
| [docs/PROGRESS.md](docs/PROGRESS.md) | 지금까지의 진행 순서, 주요 결정과 근거, 검증 결과, 남은 일 |

서버에는 확인용 임시 화면(`src/main/resources/static/index.html`)이 들어 있어서, 서버만 띄워도 브라우저로 바로 플레이할 수 있다. 따로 만드는 프론트엔드는 이 화면과 PROTOCOL.md를 참고하면 된다.

## 실행

JDK 21 이상이 필요하다.

```bash
./gradlew bootRun
```

브라우저에서 <http://localhost:8080>을 열고, 한 사람이 방을 만들어 초대 코드를 나눠 준다. 혼자 시험할 때는 브라우저 탭을 여러 개 열면 된다. 재접속 열쇠는 탭마다 따로 저장된다.

짧은 판으로 시험하려면 근무 시간을 줄인다.

```bash
./gradlew bootRun --args='--game.session-seconds=60'
```

실행 파일 하나로 만들어 쓸 수도 있다.

```bash
./gradlew bootJar
```

```bash
java -jar build/libs/office-stock.jar
```

## 친구들과 하기

방은 서버 메모리에만 있으므로, 서버를 끄거나 다시 켜면 진행 중인 방이 모두 사라진다. 서버는 **한 대만** 띄운다.

### 같은 와이파이

서버를 띄운 컴퓨터의 IP로 접속한다. macOS에서는 아래 명령으로 IP를 볼 수 있고, 친구들은 `http://그IP:8080`으로 들어온다. 방화벽이 8080 포트를 막으면 허용해 준다.

```bash
ipconfig getifaddr en0
```

### 인터넷 너머 (빠른 방법: 터널)

내 컴퓨터에서 서버를 띄운 채 터널 도구로 잠깐 인터넷에 연다. 플레이테스트 한두 판에 가장 간단하다.

- **Cloudflare Tunnel:** 계정 없이 쓸 수 있다. `cloudflared`를 설치하고 아래를 실행하면 `https://….trycloudflare.com` 주소가 나온다. 그 주소를 친구들에게 보내면 된다.

  ```bash
  cloudflared tunnel --url http://localhost:8080
  ```

- **ngrok:** 가입하고 인증 토큰을 등록한 뒤 아래를 실행해 나온 주소를 공유한다.

  ```bash
  ngrok http 8080
  ```

화면과 WebSocket이 같은 주소로 나가므로 따로 설정할 것은 없다. 터널을 닫거나 컴퓨터가 잠들면 접속이 끊긴다.

#### 따로 만든 프론트엔드(office-stock-FE)로 플레이하기

프론트엔드는 자기 주소의 `/ws/lobby`를 백엔드로 넘겨주므로(Vite 프록시), **프론트엔드 주소 하나만 터널로 열면** 게임 연결까지 된다. 터미널 세 개에서 차례로 실행한다.

1. 백엔드 (이 폴더)

   ```bash
   ./gradlew bootRun
   ```

2. 프론트엔드 빌드와 실행 (office-stock-FE 폴더). 개발 서버(`npm run dev`)도 되지만, 터널 너머에서는 빌드한 화면이 훨씬 빨리 뜬다.

   ```bash
   npm run build && npm run preview
   ```

3. 터널. 나온 `https://….trycloudflare.com` 주소를 친구들에게 보낸다. 개발 서버로 열었다면 4173 대신 5173을 쓴다.

   ```bash
   cloudflared tunnel --url http://localhost:4173
   ```

이를 위해 두 가지를 설정해 두었다.
- 프론트엔드 `vite.config.js`: 개발 서버와 preview 모두 `allowedHosts`에 `.trycloudflare.com`을 넣었다(Vite는 모르는 주소로 들어온 접속을 403으로 막는다).
- 백엔드 `game.allowed-origins`: `https://*.trycloudflare.com`을 넣었다. 그래서 터널 주소의 화면에서 온 게임 연결을 받아 준다. 대신 서버가 켜져 있는 동안에는 다른 trycloudflare 주소의 페이지도 연결할 수 있다. 터널을 안 쓸 때 막으려면 이 항목을 지운다.

백엔드가 8080이 아니면 프론트엔드를 `OFFICE_STOCK_BACKEND=http://127.0.0.1:포트 npm run preview`처럼 띄운다. 프론트 코드나 백엔드를 고치면 각각 다시 빌드·실행해야 반영된다.

### 클라우드에 올리기

`Dockerfile`이 들어 있다. Render, Fly.io처럼 Docker 이미지를 받는 곳에 그대로 올릴 수 있다. 포트는 `PORT` 환경 변수로 받는다(없으면 8080).

```bash
docker build -t office-stock .
```

```bash
docker run -p 8080:8080 office-stock
```

- 인스턴스는 **한 개**로 둔다. 여러 개면 친구들이 서로 다른 서버에 붙어 방을 못 찾는다.
- 무료 요금제 중에는 한동안 접속이 없으면 서버를 재우는 곳이 많다. 서버가 잠들면 방이 사라지므로, 플레이 전에 한 번 접속해 깨워 둔다.
- 플레이테스트 업무 시간 기록(`playtest/work-times.csv`)은 컨테이너 안에 쌓이므로, 남기려면 볼륨을 붙이거나 서버 로그의 `[업무 시간]` 요약을 확인한다.

## 따로 만든 프론트엔드에서 접속하기

서버와 다른 주소에서 띄운 화면이 WebSocket으로 붙으려면 그 주소를 허용해야 한다. 기본값은 `http://localhost:*`, `http://127.0.0.1:*`라서 로컬 개발 서버(Vite 등)는 바로 붙는다. 배포한 프론트엔드 주소는 아래처럼 추가한다.

```bash
java -jar build/libs/office-stock.jar --game.allowed-origins='http://localhost:*,https://office-stock.example.com'
```

## 설정

`src/main/resources/application.properties`의 값을 실행 인자(`--이름=값`)나 환경 변수로 바꿀 수 있다.

| 설정 | 기본값 | 뜻 |
|---|---|---|
| `server.port` | 8080 | 서버 포트 |
| `game.starting-cash-won` | 100000 | 시작 자금 |
| `game.work-reward-won` | 350 | 업무 1건 수당 |
| `game.session-seconds` | 900 | 근무 시간 기본값(방장이 대기실에서 5~20분 중에 바꿀 수 있다) |
| `game.lunch-early-return-chance` | 0.2 | 점심 때 부장님이 일찍 돌아올 확률 |
| `game.loan-interest-percent` | 20 | 비상 대출 이자(%) |
| `game.loan-max-won` | 100000 | 비상 대출 상한 |
| `game.allowed-origins` | `http://localhost:*,http://127.0.0.1:*` | WebSocket을 허용할 화면 주소 |
| `game.playtest-log` | `playtest/work-times.csv` | 업무 시간 기록 파일 |
| `game.stocks-per-game` | `12` | 한 판에 무작위로 상장하는 일반 종목 수(0이면 전부). 기초 종목이 뽑힌 레버리지 종목은 따로 더해진다. |
| `game.stock-catalog` | `classpath:market/stocks.json` | 종목 목록 |
| `game.news-catalog` | `classpath:market/news.json` | 뉴스와 찌라시 문구 |

종목과 뉴스는 자바 코드를 고치지 않고 `src/main/resources/market/`의 JSON만 고치면 된다. 방법은 GAME_DESIGN.md의 "종목과 뉴스 추가하기"에 있다.
