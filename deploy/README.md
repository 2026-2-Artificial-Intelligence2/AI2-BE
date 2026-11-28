# 배포: Oracle Cloud 무료 서버 한 대

서버 한 대에 세 가지를 띄운다.

| 이름 | 하는 일 |
|---|---|
| `web` (Caddy) | 화면(office-stock-FE를 빌드한 파일)을 내보내고, 게임 연결(`/ws/lobby`)만 게임 서버로 넘긴다. HTTPS 인증서를 자동으로 받는다. |
| `server` | 게임 서버(이 저장소). 방은 메모리에만 있으므로 **한 대만** 띄운다. |
| `db` | PostgreSQL. 혼자 하기 기록·명예의 전당. |

화면과 서버가 같은 주소라 따로 맞출 설정이 없다. 음성은 와이파이에서만 되므로 TURN은 두지 않는다.

## 1. Oracle Cloud 가입

1. <https://www.oracle.com/cloud/free/> 에서 가입한다. 카드 인증이 있지만 Always Free 범위만 쓰면 청구되지 않는다.
2. **홈 리전을 서울(South Korea Central) 또는 춘천(South Korea North)으로** 고른다. 무료 서버는 홈 리전에서만 만들 수 있고, 홈 리전은 나중에 바꿀 수 없다.

## 2. 서버 만들기

Compute → Instances → **Create instance**

- **Image:** Canonical Ubuntu 24.04
- **Shape:** Ampere `VM.Standard.A1.Flex`, **2 OCPU · 12GB 메모리**(무료 한도는 합쳐서 4 OCPU · 24GB)
  - "Out of capacity"가 뜨면 다른 Availability Domain을 고르거나 시간을 두고 다시 시도한다(무료 서버가 자주 모자란다).
  - AMD `E2.1.Micro`(1GB)는 Java·DB·화면 빌드를 함께 돌리기엔 작다.
- **Networking:** 새 VCN과 공인 서브넷, **Assign a public IPv4 address** 켜기
- **SSH keys:** "Generate a key pair"로 만들고 **개인 키를 내려받아** 잘 보관한다(잃어버리면 접속할 수 없다).

만들어지면 **Public IP**를 적어 둔다(예: `158.180.1.2`).

## 3. 포트 열기(두 군데)

**① 클라우드 방화벽:** 인스턴스 → Subnet → Security List → Add Ingress Rules

| Source CIDR | Protocol | Port |
|---|---|---|
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |
| `0.0.0.0/0` | UDP | 443 |

**② 서버 안 방화벽:** Oracle의 Ubuntu 이미지는 서버 안에서도 막아 둔다. SSH로 들어가서(아래 4번) 실행한다.

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p udp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 4. 접속과 Docker 설치

```bash
chmod 600 ~/Downloads/ssh-key-*.key
ssh -i ~/Downloads/ssh-key-*.key ubuntu@158.180.1.2
```

서버 안에서:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit   # 다시 접속해야 docker를 sudo 없이 쓸 수 있다
```

## 5. 코드 받기

두 저장소를 **나란히** 받는다(폴더 이름이 중요하다). 화면 저장소(AI2-FE)는 비공개라 GitHub 로그인이 필요하다.

```bash
sudo apt-get install -y gh && gh auth login   # 브라우저 코드로 로그인
mkdir -p ~/office-stock && cd ~/office-stock
gh repo clone 2026-2-Artificial-Intelligence2/AI2-BE office-stock-BE
gh repo clone 2026-2-Artificial-Intelligence2/AI2-FE office-stock-FE
```

## 6. 설정

```bash
cd ~/office-stock/office-stock-BE/deploy
cp .env.example .env
nano .env
```

- `SITE_ADDRESS`: 공인 IP의 점을 하이픈으로 바꾸고 `.sslip.io`를 붙인다. 예: `158.180.1.2` → `158-180-1-2.sslip.io`. 도메인이 있으면 그 도메인(DNS A 레코드를 이 IP로).
- `DB_PASSWORD`: `openssl rand -hex 24`로 만든 값
- `OPENAI_API_KEY`: 부장님 GPT 판단을 쓰려면 넣는다(비우면 규칙 판단). 판당 약 6원.

`.env`는 커밋하지 않는다(.gitignore에 있다).

## 7. 실행

```bash
docker compose up -d --build   # 처음에는 빌드에 5~10분
docker compose ps              # 셋 다 running인지
docker compose logs -f server  # "Started OfficeStockApplication"이 보이면 준비 완료
```

브라우저에서 `https://158-180-1-2.sslip.io`(내 주소)로 들어간다. 처음 접속 때 인증서를 받느라 몇 초 걸릴 수 있다.

## 업데이트

```bash
cd ~/office-stock
git -C office-stock-BE pull && git -C office-stock-FE pull
cd office-stock-BE/deploy && docker compose up -d --build
```

게임 서버를 다시 켜면 **진행 중인 방이 사라진다.** 아무도 하지 않을 때 한다. 화면만 바뀌었으면 `docker compose up -d --build web`으로 웹만 다시 띄우면 방은 그대로다.

## 백업

```bash
docker compose exec db pg_dump -U officestock officestock > backup-$(date +%F).sql
```

## 알아 둘 것

- **쉬는 무료 서버 회수:** Oracle은 Always Free 서버가 7일 동안 거의 쓰이지 않으면(CPU 등 사용률이 아주 낮으면) 회수할 수 있다고 안내한다. 오래 둘 거면 계정을 Pay As You Go로 올려 두면(무료 한도 안에서는 0원) 이 정책에서 빠진다. 최신 조건은 Oracle 안내를 확인한다.
- **인증서가 안 받아지면:** 80·443 포트가 두 군데(3번) 모두 열렸는지, `SITE_ADDRESS`가 맞는지 본다. `docker compose logs web`에 이유가 나온다.
- **메모리:** 게임 서버는 `JAVA_OPTS`(기본 `-Xmx1g`)로 조절한다.
- **로그:** `docker compose logs --tail 200 server`. 업무 시간 기록은 `playtest` 볼륨에 남는다.
