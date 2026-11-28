# 배포: Oracle Cloud 무료 서버 한 대 (mollae.pro)

**지금 운영 중:** https://mollae.pro — Oracle 춘천·서울 리전 `VM.Standard.E2.1.Micro`(x86, 1GB, Ubuntu 24.04), 공인 IP `161.33.38.80`, 배포 폴더 `~/AI2-BE/deploy`(2026-11-28).

서버 한 대에 세 가지를 띄운다.

| 이름 | 하는 일 | 이미지 |
|---|---|---|
| `web` (Caddy) | 화면을 내보내고, 게임 연결(`/ws/lobby`)만 게임 서버로 넘긴다. HTTPS 인증서를 자동으로 받는다. | `ghcr.io/2026-2-artificial-intelligence2/ai2-fe` |
| `server` | 게임 서버. 방은 메모리에만 있으므로 **한 대만** 띄운다. | `ghcr.io/2026-2-artificial-intelligence2/ai2-be` |
| `db` | PostgreSQL. 혼자 하기 기록·명예의 전당. | `postgres:16-alpine` |

**빌드는 GitHub Actions가 한다.** 두 저장소의 main에 올라올 때마다 `.github/workflows/image.yml`이 이미지를 만들어 GitHub 이미지 저장소(ghcr.io)에 올린다. 서버는 받아서 실행만 하므로 메모리 1GB 서버로도 된다(4명 한 판에 셋 합쳐 약 340MB를 쓴다).

화면과 서버가 같은 주소라 따로 맞출 설정이 없다. 음성은 와이파이에서만 되므로 TURN은 두지 않는다.

## 1. Oracle Cloud 가입

1. <https://www.oracle.com/cloud/free/> 에서 가입한다. 카드 인증이 있지만 Always Free 범위만 쓰면 청구되지 않는다.
2. **홈 리전을 서울 또는 춘천으로** 고른다. 무료 서버는 홈 리전에서만 만들 수 있고, 홈 리전은 나중에 바꿀 수 없다.

## 2. 서버 만들기

Compute → Instances → **Create instance**

- **Image:** Canonical Ubuntu 24.04
- **Shape:** AMD `VM.Standard.E2.1.Micro`(1GB, Always Free). ARM `A1.Flex`는 자리가 모자라 "Out of capacity"가 자주 뜬다.
  - A1을 잡았다면 이미지를 ARM용으로도 빌드해야 한다(두 저장소 `image.yml`의 `platforms`에 `linux/arm64`를 더하고 `docker/setup-qemu-action`을 붙인다).
- **Networking:** 새 VCN과 공인 서브넷, **Assign a public IPv4 address** 켜기
- **SSH keys:** "Generate a key pair"로 만들고 **개인 키를 내려받아** 잘 보관한다.

만들어지면 **Public IP**를 적어 둔다(예: `158.180.1.2`).

## 3. 포트 열기(두 군데)

**① 클라우드 방화벽:** 인스턴스 → Networking → Subnet → Security → Default Security List → Add Ingress Rules

| Source CIDR | Protocol | Port |
|---|---|---|
| `0.0.0.0/0` | TCP | 80,443 |
| `0.0.0.0/0` | UDP | 443 |

**② 서버 안 방화벽:** Oracle의 Ubuntu 이미지는 서버 안에서도 막아 둔다. SSH로 들어가서(4번) 실행한다. 규칙은 **"REJECT" 줄보다 앞에** 들어가야 한다(이미지마다 REJECT 줄 번호가 달라서 번호를 찾아 그 자리에 넣는다. Ubuntu 24.04 E2.1.Micro는 5번이었다).

```bash
R=$(sudo iptables -L INPUT -n --line-numbers | awk '/REJECT/ {print $1; exit}')
sudo iptables -I INPUT $R -m state --state NEW -p udp --dport 443 -j ACCEPT
sudo iptables -I INPUT $R -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT $R -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save
sudo iptables -L INPUT -n --line-numbers   # 80·443 줄이 REJECT보다 위에 있는지
```

## 4. 접속, 스왑, Docker

```bash
chmod 600 ~/Downloads/ssh-key-*.key
ssh -i ~/Downloads/ssh-key-*.key ubuntu@158.180.1.2
```

서버 안에서:

```bash
# 메모리가 1GB라 스왑 2GB를 만든다(잠깐 메모리가 몰려도 버티게).
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Docker(Ubuntu 패키지)
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker ubuntu
exit   # 다시 접속해야 docker를 sudo 없이 쓸 수 있다
```

## 5. 도메인 연결 (mollae.pro)

도메인을 산 곳의 **DNS 관리**에서 레코드 두 개를 넣는다. 값은 서버의 Public IP다.

| 타입 | 이름(호스트) | 값 | TTL |
|---|---|---|---|
| A | `@` (비워 두는 곳도 있다) | `158.180.1.2` | 기본값(또는 300초) |
| A | `www` | `158.180.1.2` | 기본값(또는 300초) |

- **Cloudflare에서 관리한다면** 두 레코드의 프록시를 끈다(주황 구름 → 회색 구름, "DNS only"). 켜 두면 Caddy가 인증서를 받지 못할 수 있다.
- 반영까지 몇 분~몇십 분 걸린다. 서버에서 `dig +short mollae.pro`가 서버 IP를 보여 주면 된다. **반영된 뒤에** 7번(실행)을 해야 인증서가 한 번에 받아진다.

## 6. 배포 폴더와 이미지 받기 권한

서버에는 이 저장소의 `deploy` 폴더만 있으면 된다(공개 저장소라 로그인 없이 받는다).

```bash
git clone --depth 1 https://github.com/2026-2-Artificial-Intelligence2/AI2-BE.git ~/AI2-BE
cd ~/AI2-BE/deploy
cp .env.example .env
nano .env
```

- `SITE_ADDRESS`: `mollae.pro, www.mollae.pro`(기본값 그대로). www로 들어오면 `mollae.pro`로 넘어간다.
- `DB_PASSWORD`: `openssl rand -hex 24`로 만든 값
- `OPENAI_API_KEY`: 부장님 GPT 판단을 쓰려면 넣는다(비우면 규칙 판단). 판당 약 6원.

`.env`는 커밋하지 않는다.

**화면 이미지는 비공개 저장소(AI2-FE)에서 나와서 받으려면 로그인이 필요하다.** GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)** → Generate new token → 권한은 **`read:packages`만** 체크 → 만든 토큰을 서버에서:

```bash
docker login ghcr.io -u 내GitHub아이디   # 비밀번호 자리에 토큰을 붙여 넣는다
```

## 7. 실행

```bash
cd ~/AI2-BE/deploy
docker compose pull            # 이미지 받기(1~2분)
docker compose up -d
docker compose ps              # 셋 다 running인지
docker compose logs -f server  # "Started OfficeStockApplication"이 보이면 준비 완료
```

브라우저에서 **https://mollae.pro** 로 들어간다. 처음 접속 때 인증서를 받느라 몇 초 걸릴 수 있다.

## 업데이트

main에 머지하면 Actions가 새 이미지를 만든다(저장소 → Actions에서 초록 체크가 뜰 때까지 2~5분). 그다음 서버에서:

```bash
cd ~/AI2-BE/deploy
git pull                       # 배포 설정이 바뀌었을 때
docker compose pull && docker compose up -d
docker image prune -f          # 지난 이미지 정리(디스크 절약)
```

게임 서버 이미지가 바뀌면 다시 켜지면서 **진행 중인 방이 사라진다.** 아무도 하지 않을 때 한다. 화면만 바뀌었으면 `docker compose pull web && docker compose up -d web`으로 웹만 바꾸면 방은 그대로다.

문제가 생겨 이전 판으로 되돌릴 때는 `.env`에 `IMAGE_TAG=커밋해시`를 넣고 `docker compose up -d`(Actions가 커밋마다 해시 태그도 올린다).

## 백업

```bash
docker compose exec db pg_dump -U officestock officestock > backup-$(date +%F).sql
```

## 알아 둘 것

- **쉬는 무료 서버 회수:** Oracle은 Always Free 서버가 7일 동안 거의 쓰이지 않으면 회수할 수 있다고 안내한다. 오래 둘 거면 계정을 Pay As You Go로 올려 두면(무료 한도 안에서는 0원) 이 정책에서 빠진다. 최신 조건은 Oracle 안내를 확인한다.
- **인증서가 안 받아지면:** 80·443 포트가 두 군데(3번) 모두 열렸는지, DNS가 반영됐는지 본다. `docker compose logs web`에 이유가 나온다.
- **메모리:** 게임 서버는 `JAVA_OPTS`(기본 `-Xmx320m …`)로 조절한다. 메모리가 넉넉한 서버면 `.env`에 `JAVA_OPTS=-Xmx1g`.
- **로그:** `docker compose logs --tail 200 server`. 업무 시간 기록은 `playtest` 볼륨에 남는다.
- **내 컴퓨터에서 시험:** 두 저장소를 나란히 받고 `.env`에 `SITE_ADDRESS=:80`, `HTTP_PORT=8088`을 넣은 뒤 `docker compose up -d --build` → http://localhost:8088
