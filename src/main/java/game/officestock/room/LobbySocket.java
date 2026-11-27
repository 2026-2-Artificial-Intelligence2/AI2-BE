package game.officestock.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import game.officestock.market.Market;
import game.officestock.market.NewsCatalog;
import game.officestock.market.PriceChart;
import game.officestock.market.StockCatalog;
import game.officestock.market.StockPool;
import game.officestock.player.PlayerWallet;
import game.officestock.player.WorkDesk;
import game.officestock.profile.ProfileService;
import game.officestock.profile.ResultService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class LobbySocket extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LobbySocket.class);
    /** 순찰은 시세 갱신과 별개로 2초 간격(한 걸음 구간)으로 움직인다. */
    private static final long PATROL_BEAT_MILLIS = 2_000;
    private static final long TICKS_PER_PATROL_BEAT = PATROL_BEAT_MILLIS / Market.TICK_MILLIS;
    private static final String MEMBERSHIP = "membership";
    /** 시작 전 대기실에서 연결이 끊긴 사람의 자리를 지켜 주는 시간. 넘으면 퇴장 처리한다. */
    private static final long LOBBY_GRACE_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** 지정가 주문 수는 규칙상 제한하지 않는다. 실수로 쏟아지는 것만 막는 안전장치다. */
    private static final int MAX_OPEN_ORDERS = 100;
    /** 메신저: 메시지 길이, 연속 전송 간격, 방에 남기는 기록 수, 익명 찌라시 길이. */
    private static final int MAX_CHAT_LENGTH = 200;
    private static final long CHAT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(400);
    private static final int MAX_CHAT_HISTORY = 300;
    private static final int MAX_LEAK_LENGTH = 60;
    private static final int MAX_LEAK_BODY_LENGTH = 300;
    /** 방장이 고를 수 있는 근무 시간 범위(분). 화면은 5·10·15·20분을 보여준다. */
    private static final int MIN_SESSION_MINUTES = 1;
    private static final int MAX_SESSION_MINUTES = 60;
    /** 점심시간은 근무 시간의 40% 지점에서 시작해 10% 동안. 15분 판이면 6:00~7:30. */
    private static final double LUNCH_START_FRACTION = 0.4;
    private static final double LUNCH_LENGTH_FRACTION = 0.1;
    /** 일찍 복귀하면 공지된 점심 끝보다 이만큼 먼저 돌아온다. */
    private static final long EARLY_RETURN_SECONDS = 20;
    /** 시작한 방에서 모두의 연결이 끊긴 채 이 시간이 지나면 방을 정리한다. */
    private static final long EMPTY_ROOM_GRACE_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Map<String, Lobby> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json;
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("room-reaper").unstarted(r));

    private final long startingCashWon;
    private final long workRewardWon;
    private final long sessionSeconds;
    private final double lunchEarlyReturnChance;
    /** 비상 대출: 누구나 한 판에 한 번, 상한 안에서 원하는 금액을 빌리고 퇴근 때 원금에 이자를 더해 뺀다. */
    private final int loanInterestPercent;
    private final long loanMaxWon;
    /** 거래 비용(bp). 사고팔 때 각각 붙는다. */
    private final int tradeFeeBps;
    /** 신용 매수: 고를 수 있는 배수, 1분 이자(bp), 유지 비율(%). 담보 비율이 유지 비율 아래면 반대매매. */
    private static final List<Integer> MARGIN_LEVERAGES = List.of(2, 3, 5);
    private final int marginInterestBpsPerMinute;
    private final int marginMaintenancePercent;
    private final PlaytestLog playtest;
    private final StockCatalog catalog;
    private final NewsCatalog news;
    /** 한 판에 상장하는 일반 종목 수. 0이면 전부. */
    private final int stocksPerGame;
    /** 음성 연결에 쓸 STUN·TURN 서버 목록(브라우저 RTCPeerConnection의 iceServers 그대로). */
    private final com.fasterxml.jackson.databind.JsonNode voiceIceServers;
    private final ProfileService profiles;
    private final ResultService results;
    private final BossBrain bossBrain;
    /** 세션에 붙여 두는 로그인한 프로필(ProfileService.View). */
    private static final String PROFILE = "profile";

    public LobbySocket(ObjectMapper json, PlaytestLog playtest, StockCatalog catalog, NewsCatalog news,
            ProfileService profiles, ResultService results, BossBrain bossBrain,
            @Value("${game.starting-cash-won}") long startingCashWon,
            @Value("${game.work-reward-won}") long workRewardWon,
            @Value("${game.session-seconds}") long sessionSeconds,
            @Value("${game.lunch-early-return-chance}") double lunchEarlyReturnChance,
            @Value("${game.loan-interest-percent}") int loanInterestPercent,
            @Value("${game.loan-max-won}") long loanMaxWon,
            @Value("${game.trade-fee-bps}") int tradeFeeBps,
            @Value("${game.margin-interest-bps-per-minute}") int marginInterestBpsPerMinute,
            @Value("${game.margin-maintenance-percent}") int marginMaintenancePercent,
            @Value("${game.stocks-per-game:12}") int stocksPerGame,
            @Value("${VOICE_ICE_SERVERS:${game.voice-ice-servers}}") String voiceIceServers) {
        if (startingCashWon < 0 || workRewardWon <= 0 || sessionSeconds <= 0) {
            throw new IllegalArgumentException("시작 자금, 업무 수당, 근무 시간 설정을 확인하세요.");
        }
        this.sessionSeconds = sessionSeconds;
        this.lunchEarlyReturnChance = lunchEarlyReturnChance;
        if (loanInterestPercent < 0) throw new IllegalArgumentException("비상 대출 이자를 확인하세요.");
        this.loanInterestPercent = loanInterestPercent;
        if (loanMaxWon <= 0) throw new IllegalArgumentException("비상 대출 상한을 확인하세요.");
        this.loanMaxWon = loanMaxWon;
        if (tradeFeeBps < 0 || tradeFeeBps > 1_000) throw new IllegalArgumentException("거래 비용 설정을 확인하세요.");
        this.tradeFeeBps = tradeFeeBps;
        if (marginInterestBpsPerMinute < 0 || marginMaintenancePercent < 1 || marginMaintenancePercent >= 50)
            throw new IllegalArgumentException("신용 매수 설정을 확인하세요.");
        this.marginInterestBpsPerMinute = marginInterestBpsPerMinute;
        this.marginMaintenancePercent = marginMaintenancePercent;
        this.startingCashWon = startingCashWon;
        this.workRewardWon = workRewardWon;
        this.json = json;
        this.playtest = playtest;
        this.catalog = catalog;
        this.news = news;
        if (stocksPerGame < 0) throw new IllegalArgumentException("한 판 상장 종목 수를 확인하세요.");
        this.stocksPerGame = stocksPerGame;
        this.voiceIceServers = parseIceServers(json, voiceIceServers);
        this.profiles = profiles;
        this.results = results;
        this.bossBrain = bossBrain;
        reaper.scheduleWithFixedDelay(this::reapRooms, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopReaper() {
        reaper.shutdownNow();
    }

    private static final class Lobby {
        final String code;
        final GameRoom game;
        final ReentrantLock lock = new ReentrantLock(true);
        /** 지금 연결된 플레이어의 세션. 연결이 끊긴 플레이어는 여기서 빠지고 offlineSince에 들어간다. */
        final Map<UUID, WebSocketSession> clients = new HashMap<>();
        /** 재접속용 비밀 열쇠. 플레이어 UUID는 방 안에 공개되므로 따로 둔다. */
        final Map<UUID, String> tokens = new HashMap<>();
        final Map<UUID, Long> offlineSince = new HashMap<>();

        /** 이번 판 근무 시간. 대기실에서 방장이 고르고, 시작할 때 이 길이로 시장을 만든다. */
        long sessionSeconds;
        long sessionTicks;
        /** 같은 방에서 몇 번째 판인가. 기록(업무 시간 CSV, 로그)에서 판을 구분한다. */
        int round = 1;
        /**
         * 화면과 기록에 쓰는 이름(P1, P2…). 입장할 때 정하고 다시 하기를 해도 바꾸지 않는다.
         * 누가 나가도 다른 사람의 이름은 그대로다.
         */
        final Map<UUID, String> labels = new HashMap<>();
        int nextLabel = 1;
        /** 마이크 소리 크기(0~100). 브라우저가 재서 보내고, 연결이 끊기면 지운다. */
        final Map<UUID, Integer> micLevels = new HashMap<>();
        /** 음성 대화에 참여한 사람과 음소거 여부. 연결이 끊기거나 나가면 빠진다(다시 참여해야 한다). */
        final Map<UUID, Boolean> voiceMuted = new HashMap<>();
        /** 사람별 음성 연결 정보 전달 횟수(1초 창). 너무 많이 보내면 막는다. */
        final Map<UUID, long[]> voiceSignalWindow = new HashMap<>();
        /** 사람별로 소리 크기를 마지막으로 방에 알린 시각(나노초). 너무 잦은 알림을 줄인다. */
        final Map<UUID, Long> micSentAt = new HashMap<>();
        /** 사내 메신저 기록(최근 MAX_CHAT_HISTORY개). 같은 방에서 다시 해도 남고, 메시지마다 판 번호가 붙는다. */
        final List<ChatMessage> chats = new ArrayList<>();
        /** 메시지 번호. 방이 유지되는 동안 계속 늘어난다(화면이 번호로 안 읽은 메시지를 센다). */
        int chatSeq;
        final Map<UUID, Long> lastChatNanos = new HashMap<>();

        // 아래는 한 판의 상태. 같은 방에서 다시 하면 nextRound()로 새로 만든다.
        final Map<UUID, WorkDesk> desks = new HashMap<>();
        /** 걸려 있는 지정가 주문(먼저 건 순서). */
        final List<PendingOrder> orders = new ArrayList<>();
        int nextOrderId = 1;
        /** 이번 판에 익명 찌라시를 뿌린 사람과 내용. 퇴근 결과에서 공개한다. */
        final List<Leak> leaks = new ArrayList<>();
        Market market;
        Patrol patrol = new Patrol();
        /** 같은 시장에서 매매하는 부장님. 판이 시작될 때 만든다. */
        Boss boss;
        /** 이번 판에 Claude에게 부장님 판단을 물은 횟수(상한이 있다). */
        int bossCalls;
        final List<Settlement.TradeRecord> trades = new ArrayList<>();
        List<Settlement.Standing> standings;
        List<Settlement.Award> awards = List.of();
        long tick;
        boolean finished;
        Thread marketClock;
        /** 공지된 점심시간 [시작, 끝)과 부장님이 실제로 돌아오는 틱(일찍 복귀하면 끝보다 이르다). */
        long lunchStartTick;
        long lunchEndTick;
        long bossReturnTick;

        boolean lunchTime() {
            return tick >= lunchStartTick && tick < lunchEndTick;
        }

        boolean bossAway() {
            return tick >= lunchStartTick && tick < bossReturnTick;
        }

        /** 혼자 하는 방. 메신저·익명 찌라시가 없고, 시장에 보이지 않는 세력이 들어오고, 결과가 명예의 전당에 오른다. */
        final boolean solo;
        /** 플레이어별 프로필 id. 이름을 정하지 않고 들어온 사람은 없다(결과를 저장하지 않는다). */
        final Map<UUID, UUID> profileIds = new HashMap<>();

        Lobby(String code, UUID host, Market market, long sessionSeconds, boolean solo) {
            this.code = code;
            this.solo = solo;
            this.game = new GameRoom(host, solo);
            this.market = market;
            this.sessionSeconds = sessionSeconds;
            this.sessionTicks = sessionSeconds / Market.TICK_SECONDS;
        }

        void nextRound(Market freshMarket) {
            round++;
            desks.clear();
            leaks.clear();
            orders.clear();
            market = freshMarket;
            patrol = new Patrol();
            boss = null;
            bossCalls = 0;
            trades.clear();
            standings = null;
            awards = List.of();
            tick = 0;
            finished = false;
            marketClock = null;
        }

        /** 기록용 방 이름. 두 번째 판부터 "123456-2"처럼 판 번호를 붙인다. */
        String logName() {
            return round == 1 ? code : code + "-" + round;
        }

        boolean started() {
            return game.snapshot().startedAt() != null;
        }
    }

    private record Membership(Lobby lobby, UUID player) {}

    /** @param to 받는 사람 이름, 단체방이면 null */
    /**
     * @param round 몇 번째 판의 메시지인가(같은 방에서 다시 하면 늘어난다). 화면이 판 사이에 구분선을 긋는다.
     * @param system 서버가 보낸 사내 알림(동료가 면담에 불려 감 등). from은 {@link #SYSTEM_SENDER}다.
     */
    private record ChatMessage(int id, int round, long second, String from, String to, String text, boolean system) {}

    /** 사내 알림을 보내는 이름. 이름은 2자 이상 영문·한글·숫자·_·-라 공백이 든 이 이름과 겹치지 않는다. */
    static final String SYSTEM_SENDER = "사내 알림";
    private static final Map<String, String> CALLED_NOTICE = Map.of(
            "market", "%s 님이 주식하다 부장님께 불려 갔어요.",
            "messenger", "%s 님이 메신저를 하다 부장님께 불려 갔어요.",
            "idle", "%s 님이 일을 안 해서 부장님께 불려 갔어요.",
            "mood", "%s 님이 기분 나쁜 부장님께 괜히 불려 갔어요.");

    private record Leak(String player, int newsNumber, String text) {}

    /** 걸어 둔 지정가 주문. reservedWon은 매수 주문에 묶은 현금(가격×수량). */
    private record PendingOrder(int id, UUID player, String symbol, boolean buy, long limitWon, long quantity,
                                long reservedWon, long placedSecond) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        send(session, Map.of("type", "hello", "requestIds", true));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        var command = message.getPayload().strip();
        String requestId = null;
        if (command.startsWith("{")) {
            try {
                var request = json.readTree(command);
                var id = request.path("requestId");
                if (!id.isTextual() || !id.textValue().matches("[A-Za-z0-9_-]{1,64}")) {
                    sendError(session, null, "", "요청 ID 형식이 올바르지 않습니다.");
                    return;
                }
                requestId = id.textValue();
                var text = request.path("command");
                if (!text.isTextual() || text.textValue().isBlank()) {
                    sendError(session, requestId, "", "명령을 입력하세요.");
                    return;
                }
                command = text.textValue().strip();
            } catch (JsonProcessingException e) {
                sendError(session, null, "", "요청 형식이 올바르지 않습니다.");
                return;
            }
        }

        try {
            handleCommand(session, command);
            if (requestId != null) send(session, Map.of("type", "ack", "requestId", requestId));
        } catch (IllegalStateException | IllegalArgumentException e) {
            sendError(session, requestId, command, e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String requestId, String command, String message) throws IOException {
        var response = new LinkedHashMap<String, Object>();
        response.put("type", "error");
        response.put("message", message == null ? "요청을 처리할 수 없습니다." : message);
        String verb = command.split(" ", 2)[0];
        if (verb.matches("[A-Z_]{1,20}")) {
            response.put("command", command.startsWith("VOICE SIGNAL ") ? "VOICE SIGNAL" : verb);
        }
        if (requestId != null) response.put("requestId", requestId);
        send(session, response);
    }

    private void handleCommand(WebSocketSession session, String command) throws IOException {
        var member = (Membership) session.getAttributes().get(MEMBERSHIP);
        if (command.startsWith("PROFILE ")) {
            profileCommand(session, command.substring(8).strip(), member != null);
            return;
        }
        if (command.equals("RECORDS")) {
            var profile = (ProfileService.View) session.getAttributes().get(PROFILE);
            if (profile == null) throw new IllegalStateException("회원만 전적을 볼 수 있습니다. 로그인하세요.");
            var records = new LinkedHashMap<String, Object>(results.records(profile.id()));
            records.put("type", "records");
            send(session, records);
            return;
        }
        if (command.startsWith("SIGNUP ") || command.startsWith("LOGIN ") || command.startsWith("AUTOLOGIN ")
                || command.equals("LOGOUT") || command.startsWith("GUEST ") || command.startsWith("RESET ")) {
            if (member != null) throw new IllegalStateException("방에서 나온 뒤에 로그인·로그아웃할 수 있습니다.");
            accountCommand(session, command);
            return;
        }
        if (command.equals("LEADERBOARD")) {
            send(session, Map.of("type", "leaderboard", "boards", results.leaderboard()));
            return;
        }
        if (member != null) {
            var lobby = member.lobby();
            lobby.lock.lock();
            try {
                if (rooms.get(lobby.code) != lobby) {
                    throw new IllegalStateException("종료된 방입니다.");
                }
                if (command.equals("LEAVE")) {
                    if (!lobby.solo && lobby.started() && member.player().equals(lobby.game.snapshot().hostId())) {
                        backToLobby(lobby);
                        return;
                    }
                    session.getAttributes().remove(MEMBERSHIP);
                    send(session, Map.of("type", "closed", "message", "방에서 나왔습니다."));
                    leave(lobby, member.player());
                    return;
                }
                if (command.equals("RESTART")) {
                    if (!lobby.finished) throw new IllegalStateException("퇴근한 뒤에 다시 할 수 있습니다.");
                    lobby.game.reset(member.player());
                    lobby.nextRound(newMarket(lobby.sessionSeconds, lobby.solo));
                    log.info("방 {} {}번째 판 대기", lobby.code, lobby.round);
                    broadcast(lobby);
                    return;
                }
                if (command.startsWith("VOICE ")) {
                    voiceCommand(lobby, member.player(), command.substring(6));
                    return;
                }
                if (command.startsWith("MIC ")) {
                    setMicLevel(lobby, member.player(), command.substring(4).strip());
                    return;
                }
                if (command.startsWith("LENGTH ")) {
                    setLength(lobby, member.player(), command.substring(7).strip());
                    return;
                }
                if (lobby.solo && (command.startsWith("CHAT ") || command.startsWith("LEAK")
                        || command.equals("MESSENGER"))) {
                    throw new IllegalStateException("솔로 게임에는 메신저가 없습니다.");
                }
                if (command.startsWith("CHAT ")) {
                    chat(lobby, member.player(), command.substring(5));
                    return;
                }
                if (lobby.finished) {
                    throw new IllegalStateException("퇴근했습니다. 결과를 확인하세요.");
                }
                if (command.startsWith("LEAK_ARTICLE ")) {
                    var article = parseLeakArticle(command.substring("LEAK_ARTICLE ".length()));
                    leak(lobby, member.player(), article.headline(), article.body());
                    return;
                }
                if (command.startsWith("LEAK ")) {
                    leak(lobby, member.player(), command.substring(5).strip(), null);
                    return;
                }
                if (command.equals("SWITCH") || command.equals("MESSENGER")) {
                    var seat = lobby.patrol.seats.get(member.player());
                    if (seat == null || seat.view == Patrol.View.MEETING)
                        throw new IllegalStateException("현재 화면을 전환할 수 없습니다.");
                    // Tab(SWITCH): 업무 창 ↔ 주식 창, 메신저 창에서는 업무 창으로. MESSENGER: 메신저 창 열기.
                    if (command.equals("MESSENGER")) seat.view = Patrol.View.MESSENGER;
                    else seat.view = seat.view == Patrol.View.WORK ? Patrol.View.MARKET : Patrol.View.WORK;
                    lobby.desks.get(member.player())
                            .observeView(seat.view == Patrol.View.WORK, System.nanoTime());
                    send(session, Map.of("type", "view", "view", seat.view));
                    return;
                }
                if (command.equals("NEWS HISTORY")) {
                    if (!lobby.desks.containsKey(member.player())) {
                        throw new IllegalStateException("게임 시작 후 알림창을 열 수 있습니다.");
                    }
                    if (lobby.patrol.seats.get(member.player()).view == Patrol.View.MEETING)
                        throw new IllegalStateException("훈계가 끝나면 확인할 수 있습니다.");
                    send(session, Map.of("type", "newsHistory", "items", lobby.market.history()));
                    return;
                }
                if (command.startsWith("LOAN ")) {
                    takeLoan(lobby, member.player(), session, command.substring(5).strip());
                    return;
                }
                if (command.startsWith("WORK ")) {
                    submitWork(session, member, command);
                    return;
                }
                if (command.startsWith("BUY ") || command.startsWith("SELL ")
                        || command.startsWith("BUYWON ") || command.startsWith("SELLWON ")) {
                    trade(session, member, command);
                    return;
                }
                if (command.startsWith("LIMIT ")) {
                    placeLimit(lobby, member.player(), command);
                    return;
                }
                if (command.startsWith("CANCEL ")) {
                    cancelOrder(lobby, member.player(), command.substring(7).strip());
                    return;
                }
                if (!command.equals("START")) {
                    throw new IllegalStateException("이미 방에 입장했습니다.");
                }
                start(lobby, member.player());
            } finally {
                lobby.lock.unlock();
            }
        } else if (command.equals("CREATE")) {
            create(session, false, sessionSeconds);
        } else if (command.equals("SOLO") || command.startsWith("SOLO ")) {
            solo(session, command.substring(4).strip());
        } else if (command.startsWith("JOIN ")) {
            join(session, command.substring(5).strip().toUpperCase(Locale.ROOT));
        } else if (command.startsWith("RESUME ")) {
            var fields = command.split("\\s+");
            if (fields.length != 3) throw new IllegalArgumentException("재접속 정보가 올바르지 않습니다.");
            resume(session, fields[1], fields[2]);
        } else {
            throw new IllegalStateException("방을 만들거나 초대 코드를 입력하세요.");
        }
    }

    /** 대기실에서 방장이 근무 시간(분)을 고른다. 점심시간과 장 마감 구간은 이 길이에 비례한다. */
    private void setLength(Lobby lobby, UUID requester, String minutesText) throws IOException {
        if (!requester.equals(lobby.game.snapshot().hostId()))
            throw new IllegalStateException("방장만 근무 시간을 정할 수 있습니다.");
        if (lobby.started()) throw new IllegalStateException("시작한 뒤에는 근무 시간을 바꿀 수 없습니다.");
        int minutes;
        try {
            minutes = Integer.parseInt(minutesText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("근무 시간은 분 단위 정수여야 합니다.");
        }
        if (minutes < MIN_SESSION_MINUTES || minutes > MAX_SESSION_MINUTES)
            throw new IllegalArgumentException("근무 시간은 " + MIN_SESSION_MINUTES + "~" + MAX_SESSION_MINUTES + "분 사이로 정하세요.");
        if (lobby.solo && !ResultService.BOARD_MINUTES.contains(minutes))
            throw new IllegalArgumentException("솔로 게임은 " + ResultService.BOARD_MINUTES + "분 중에서 고르세요.");
        lobby.sessionSeconds = minutes * 60L;
        broadcast(lobby);
    }

    private void start(Lobby lobby, UUID requester) throws IOException {
        if (lobby.started()) return;
        lobby.game.start(requester, Instant.now());
        lobby.sessionTicks = lobby.sessionSeconds / Market.TICK_SECONDS;
        lobby.market = newMarket(lobby.sessionSeconds, lobby.solo);
        lobby.lunchStartTick = Math.round(lobby.sessionTicks * LUNCH_START_FRACTION);
        lobby.lunchEndTick = lobby.lunchStartTick + Math.round(lobby.sessionTicks * LUNCH_LENGTH_FRACTION);
        boolean early = random.nextDouble() < lunchEarlyReturnChance;
        lobby.bossReturnTick = early
                ? Math.max(lobby.lunchStartTick, lobby.lunchEndTick - EARLY_RETURN_SECONDS / Market.TICK_SECONDS)
                : lobby.lunchEndTick;
        log.info("방 {} 점심 {}~{}초, 부장님 {}", lobby.logName(), lobby.lunchStartTick * Market.TICK_SECONDS,
                lobby.lunchEndTick * Market.TICK_SECONDS, early ? "일찍 복귀" : "제때 복귀");
        var players = lobby.game.snapshot().players();
        lobby.patrol.start(players);
        long now = System.nanoTime();
        // 잠시 연결이 끊긴 사람도 참가자다. 돌아오면 자기 책상에서 이어서 한다.
        for (var player : players) {
            lobby.desks.computeIfAbsent(player, id -> new WorkDesk(startingCashWon, workRewardWon, tradeFeeBps, now));
            if (!lobby.clients.containsKey(player)) {
                lobby.desks.get(player).observeView(false, now);
                lobby.patrol.seats.get(player).present = false;
            }
        }
        broadcast(lobby);
        for (var entry : lobby.clients.entrySet()) {
            sendChart(entry.getValue(), lobby);
            send(entry.getValue(), Map.of("type", "catalog", "stocks", catalogMessage(lobby), "loan", Map.of("interestPercent", loanInterestPercent, "maxWon", loanMaxWon),
                    "feeBps", tradeFeeBps, "margin", Map.of("leverages", MARGIN_LEVERAGES,
                            "interestBpsPerMinute", marginInterestBpsPerMinute,
                            "maintenancePercent", marginMaintenancePercent)));
            sendDesk(entry.getValue(), lobby.desks.get(entry.getKey()));
        }
        broadcastMarket(lobby);
        if (lobby.marketClock == null) {
            lobby.marketClock = Thread.startVirtualThread(() -> runMarket(lobby));
        }
    }

    private void submitWork(WebSocketSession session, Membership member, String command)
            throws IOException {
        var desk = member.lobby().desks.get(member.player());
        if (desk == null) throw new IllegalStateException("게임 시작 후 업무를 제출하세요.");
        if (member.lobby().patrol.seats.get(member.player()).view != Patrol.View.WORK) throw new IllegalStateException("업무 창에서 제출하세요.");
        if (member.lobby().lunchTime()) throw new IllegalStateException("점심시간에는 업무 창이 잠겨 있습니다.");
        // "WORK 번호 답". 답에는 띄어쓰기가 있을 수 있다(예: 메일 폴더 "고객 문의").
        var fields = command.split("\\s+", 3);
        if (fields.length != 3) throw new IllegalArgumentException("업무 번호와 답을 입력하세요.");
        WorkDesk.TaskTiming timing;
        try {
            timing = desk.submit(Long.parseLong(fields[1]), fields[2], System.nanoTime());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("업무 번호를 확인하세요.");
        }
        if (timing != null) {
            member.lobby().patrol.onWorkDone(member.player());
            playtest.task(member.lobby().logName(), member.lobby().labels.get(member.player()), timing);
        }
        sendDesk(session, desk);
    }

    /**
     * 사내 메신저. "ALL 내용"은 단체방, "P2 내용"은 P2에게 귓속말. 게임 중에는 메신저 창에서만 보낼 수 있고,
     * 대기실과 퇴근 뒤에는 자유롭게 보낼 수 있다.
     */
    private void chat(Lobby lobby, UUID player, String rest) throws IOException {
        boolean playing = lobby.started() && !lobby.finished;
        if (playing && lobby.patrol.seats.get(player).view != Patrol.View.MESSENGER)
            throw new IllegalStateException("메신저 창에서 보내세요.");
        int space = rest.indexOf(' ');
        if (space < 0) throw new IllegalArgumentException("받는 사람과 내용을 입력하세요.");
        String target = rest.substring(0, space);
        String text = cleanText(rest.substring(space + 1), MAX_CHAT_LENGTH);
        long now = System.nanoTime();
        Long last = lobby.lastChatNanos.get(player);
        if (last != null && now - last < CHAT_INTERVAL_NANOS) throw new IllegalStateException("너무 빠르게 보내고 있습니다.");
        String from = lobby.labels.get(player);
        UUID recipient = null;
        if (!target.equals("ALL")) {
            recipient = lobby.labels.entrySet().stream()
                    .filter(e -> e.getValue().equals(target) && lobby.game.snapshot().players().contains(e.getKey()))
                    .map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("받는 사람을 찾을 수 없습니다."));
            if (recipient.equals(player)) throw new IllegalArgumentException("자기 자신에게는 보낼 수 없습니다.");
        }
        lobby.lastChatNanos.put(player, now);
        var message = new ChatMessage(++lobby.chatSeq, lobby.round,
                playing ? lobby.tick * Market.TICK_SECONDS : -1, from, recipient == null ? null : target, text, false);
        lobby.chats.add(message);
        if (lobby.chats.size() > MAX_CHAT_HISTORY) lobby.chats.removeFirst();
        for (var entry : lobby.clients.entrySet()) {
            if (recipient == null || entry.getKey().equals(player) || entry.getKey().equals(recipient)) {
                send(entry.getValue(), Map.of("type", "chat", "message", message));
            }
        }
    }

    /** 방금 훈계로 불려 간 동료를 단체방에 사내 알림으로 알린다. 솔로 방에는 메신저가 없다. */
    private void announceMeetings(Lobby lobby) throws IOException {
        // 기분이 좋아 봐준 사람에게만 알린다(누가 봐줬는지는 다른 사람에게 알리지 않는다).
        for (var player : lobby.patrol.justForgiven) {
            var session = lobby.clients.get(player);
            if (session != null) send(session, Map.of("type", "notice",
                    "message", "부장님이 기분이 좋아서 이번 한 번은 봐줬어요. 다음엔 없어요!"));
        }
        lobby.patrol.justForgiven.clear();
        if (lobby.patrol.justCalled.isEmpty()) return;
        var called = List.copyOf(lobby.patrol.justCalled);
        lobby.patrol.justCalled.clear();
        if (lobby.solo) return;
        for (var player : called) {
            var reason = lobby.patrol.seats.get(player).meetingReason;
            var text = CALLED_NOTICE.getOrDefault(reason, "%s 님이 부장님께 불려 갔어요.").formatted(lobby.labels.get(player));
            var message = new ChatMessage(++lobby.chatSeq, lobby.round,
                    lobby.tick * Market.TICK_SECONDS, SYSTEM_SENDER, null, text, true);
            lobby.chats.add(message);
            if (lobby.chats.size() > MAX_CHAT_HISTORY) lobby.chats.removeFirst();
            for (var session : lobby.clients.values()) send(session, Map.of("type", "chat", "message", message));
        }
    }

    /** 내가 볼 수 있는 메신저 기록(단체방과 내가 주고받은 귓속말). 입장·재접속 때 보낸다. */
    private void sendChatHistory(WebSocketSession session, Lobby lobby, UUID player) throws IOException {
        var me = lobby.labels.get(player);
        send(session, Map.of("type", "chatHistory", "messages", lobby.chats.stream()
                .filter(m -> m.to() == null || m.to().equals(me) || m.from().equals(me)).toList()));
    }

    /** 익명 찌라시: 한 판에 한 번, 내가 쓴 문장을 진짜 찌라시처럼 모두의 화면에 뿌린다. 가격은 움직이지 않는다. */
    private void leak(Lobby lobby, UUID player, String headline, String body) throws IOException {
        if (!lobby.started()) throw new IllegalStateException("게임 시작 후 뿌릴 수 있습니다.");
        if (lobby.patrol.seats.get(player).view != Patrol.View.MESSENGER)
            throw new IllegalStateException("메신저 창에서 뿌리세요.");
        var label = lobby.labels.get(player);
        if (lobby.leaks.stream().anyMatch(l -> l.player().equals(label)))
            throw new IllegalStateException("익명 찌라시는 한 판에 한 번만 뿌릴 수 있습니다.");
        var item = lobby.market.addPlayerRumor(cleanText(headline, MAX_LEAK_LENGTH),
                body == null ? null : cleanText(body, MAX_LEAK_BODY_LENGTH));
        lobby.leaks.add(new Leak(label, item.number(), item.headline()));
        log.info("방 {} {} 익명 찌라시: {}", lobby.logName(), label, item.headline());
        send(lobby.clients.get(player), Map.of("type", "leakSent", "message", "익명 찌라시를 뿌렸습니다. 누가 뿌렸는지는 퇴근 뒤 공개됩니다."));
        broadcastMarket(lobby);
    }

    private record LeakArticle(String headline, String body) {}

    private LeakArticle parseLeakArticle(String raw) {
        try {
            var article = json.readTree(raw);
            if (article != null && article.path("headline").isTextual() && article.path("body").isTextual()) {
                return new LeakArticle(article.get("headline").textValue(), article.get("body").textValue());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // The client receives the same format error for malformed JSON and missing fields.
        }
        throw new IllegalArgumentException("찌라시 제목과 본문을 확인하세요.");
    }

    /** 앞뒤 공백과 제어 문자를 지우고 길이를 확인한다. */
    private static String cleanText(String raw, int maxLength) {
        var text = raw.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty()) throw new IllegalArgumentException("내용을 입력하세요.");
        if (text.codePointCount(0, text.length()) > maxLength)
            throw new IllegalArgumentException(maxLength + "자 이내로 입력하세요.");
        return text;
    }

    private void takeLoan(Lobby lobby, UUID player, WebSocketSession session, String amountText)
            throws IOException {
        var desk = lobby.desks.get(player);
        if (desk == null) throw new IllegalStateException("게임 시작 후 신청하세요.");
        if (lobby.patrol.seats.get(player).view == Patrol.View.MEETING)
            throw new IllegalStateException("훈계가 끝나면 신청하세요.");
        long amount;
        try {
            amount = Long.parseLong(amountText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("대출 금액은 원 단위 정수로 입력하세요.");
        }
        if (amount <= 0) throw new IllegalArgumentException("대출 금액은 1원 이상이어야 합니다.");
        if (amount > loanMaxWon)
            throw new IllegalArgumentException("비상 대출은 최대 " + String.format("%,d", loanMaxWon) + "원까지 받을 수 있습니다.");
        // 원리금 = 원금 + 이자(올림).
        long repay = amount + (amount / 100 * loanInterestPercent
                + (amount % 100 * loanInterestPercent + 99) / 100);
        var wallet = desk.wallet();
        try {
            wallet.takeLoan(amount, repay);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("처리할 수 있는 금액 범위를 넘었습니다.");
        }
        var label = lobby.labels.get(player);
        log.info("방 {} {} 비상 대출 {}원 (상환 {}원)", lobby.logName(), label, amount, repay);
        sendDesk(session, desk);
        for (var client : lobby.clients.values()) {
            send(client, Map.of("type", "notice",
                    "message", label + "이(가) 비상 대출 " + String.format("%,d", amount) + "원을 받았습니다."));
        }
        broadcastMarket(lobby);
    }

    private void sendDesk(WebSocketSession session, WorkDesk desk) throws IOException {
        send(session, Map.of("type", "work", "desk", desk.state()));
    }

    /**
     * 시장가 주문. "BUY 종목 수량"·"SELL 종목 수량"은 수량으로, "BUYWON 종목 금액"은 그 금액 안에서 최대한,
     * "SELLWON 종목 금액"은 그 금액 이상을 받는 데 필요한 만큼 판다. 주식 창에서만 할 수 있다.
     */
    private void trade(WebSocketSession session, Membership member, String command)
            throws IOException {
        var lobby = member.lobby();
        var desk = lobby.desks.get(member.player());
        if (desk == null) throw new IllegalStateException("게임 시작 후 주문하세요.");
        if (lobby.patrol.seats.get(member.player()).view != Patrol.View.MARKET) throw new IllegalStateException("주식 창에서만 거래할 수 있습니다.");

        var fields = command.split("\\s+");
        if (fields.length != 3 && fields.length != 4) throw new IllegalArgumentException("종목과 수량을 확인하세요.");
        var symbol = fields[1];
        var pool = lobby.market.pool(symbol);
        if (pool == null) throw new IllegalArgumentException("등록되지 않은 종목입니다.");
        long number = parsePositive(fields[2], "수량과 금액은 1 이상의 정수여야 합니다.");
        boolean buying = fields[0].startsWith("BUY");
        // 신용 매수 배수: 마지막에 "x3"처럼 붙인다. 매수에만 쓸 수 있다.
        int leverage = 1;
        if (fields.length == 4) {
            if (!buying || !fields[3].matches("x\\d")) throw new IllegalArgumentException("신용 배수는 매수에 x2처럼 붙입니다.");
            leverage = Integer.parseInt(fields[3].substring(1));
            if (!MARGIN_LEVERAGES.contains(leverage))
                throw new IllegalArgumentException("신용 배수는 " + MARGIN_LEVERAGES + " 중에서 고르세요.");
        }
        long quantity = number;
        if (fields[0].endsWith("WON")) {
            var wallet = desk.wallet();
            if (buying) {
                // 금액은 내 돈 기준이다. 신용이면 그 배수만큼 산다. 거래 비용까지 그 안에 들어오게 한다.
                long own = Math.min(number, wallet.balance().cashWon());
                long budget = Math.multiplyExact(own, (long) leverage);
                quantity = pool.maxBuyable(budget * 10_000 / (10_000 + tradeFeeBps));
                while (quantity > 0 && PlayerWallet.ownShare(pool.quoteBuy(quantity)
                        + wallet.feeFor(pool.quoteBuy(quantity)), leverage) > own) quantity--;
                if (quantity <= 0) throw new IllegalArgumentException("그 금액으로는 1주도 살 수 없습니다.");
            } else {
                long sellable = wallet.sellable(symbol);
                if (sellable <= 0) throw new IllegalArgumentException("팔 수 있는 주식이 없습니다.");
                quantity = pool.minSellFor(number, sellable);
            }
        }
        execute(lobby, member.player(), symbol, buying, quantity, false, leverage, null);
        checkMargin(lobby, member.player());
        broadcastMarket(lobby);
    }

    /** 체결 하나를 처리하고 본인에게 알린다. 화면 확인은 부르는 쪽이 한다(지정가는 안 보고 있어도 체결된다). */
    private long execute(Lobby lobby, UUID player, String symbol, boolean buying, long quantity, boolean limit)
            throws IOException {
        return execute(lobby, player, symbol, buying, quantity, limit, 1, null);
    }

    /** @param label 거래 기록에 쓸 구분(null이면 매수·매도·지정가 등으로 정한다. 예: "반대매매") */
    private long execute(Lobby lobby, UUID player, String symbol, boolean buying, long quantity, boolean limit,
                         int leverage, String label) throws IOException {
        var desk = lobby.desks.get(player);
        var pool = lobby.market.pool(symbol);
        PlayerWallet.Fill fill;
        long spotBefore = lobby.market.spotPrice(symbol);
        try {
            fill = buying ? desk.wallet().buy(symbol, pool, quantity, leverage)
                          : desk.wallet().sell(symbol, pool, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("처리할 수 있는 주문 범위를 초과했습니다.");
        }
        long amount = fill.netWon(buying);
        lobby.market.recordTrade(symbol, spotBefore, quantity);
        String side = label != null ? label
                : (limit ? "지정가 " : "") + (leverage > 1 ? "신용 " + leverage + "배 " : "") + (buying ? "매수" : "매도");
        lobby.trades.add(new Settlement.TradeRecord(lobby.tick * Market.TICK_SECONDS,
                lobby.labels.get(player), symbol, side, quantity, amount));
        var client = lobby.clients.get(player);
        if (client != null) {
            sendDesk(client, desk);
            send(client, Map.ofEntries(Map.entry("type", "fill"), Map.entry("symbol", symbol),
                    Map.entry("quantity", quantity), Map.entry("side", side), Map.entry("amount", amount),
                    Map.entry("feeWon", fill.feeWon()), Map.entry("limit", limit),
                    Map.entry("borrowedWon", fill.borrowedWon()), Map.entry("debtRepaidWon", fill.debtRepaidWon()),
                    Map.entry("forced", label != null)));
            sendMyTrades(client, lobby, player);
        }
        return amount;
    }

    private static long parsePositive(String text, String message) {
        try {
            long value = Long.parseLong(text);
            if (value <= 0) throw new IllegalArgumentException(message);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 지정가 주문 걸기: "LIMIT BUY 종목 가격 수량" / "LIMIT SELL 종목 가격 수량". 주식 창에서만 걸 수 있다.
     * 매수는 가격×수량만큼 현금을, 매도는 그 주식을 묶는다. 평균 체결가가 가격 이하(매수)·이상(매도)이 되는
     * 순간 한 번에 전부 체결된다(일부 체결 없음). 걸어 둔 뒤에는 안 보고 있어도 체결된다.
     */
    private void placeLimit(Lobby lobby, UUID player, String command) throws IOException {
        var desk = lobby.desks.get(player);
        if (desk == null) throw new IllegalStateException("게임 시작 후 주문하세요.");
        if (lobby.patrol.seats.get(player).view != Patrol.View.MARKET)
            throw new IllegalStateException("지정가 주문은 주식 창에서 걸 수 있습니다.");
        var fields = command.split("\\s+");
        if (fields.length != 5 || !(fields[1].equals("BUY") || fields[1].equals("SELL")))
            throw new IllegalArgumentException("지정가 주문 형식을 확인하세요.");
        boolean buying = fields[1].equals("BUY");
        var symbol = fields[2];
        var pool = lobby.market.pool(symbol);
        if (pool == null) throw new IllegalArgumentException("등록되지 않은 종목입니다.");
        long price = parsePositive(fields[3], "가격은 1원 이상의 정수여야 합니다.");
        long quantity = parsePositive(fields[4], "수량은 1주 이상의 정수여야 합니다.");
        if (quantity >= pool.reserves().shares()) throw new IllegalArgumentException("주문할 수 없는 수량입니다.");
        long open = lobby.orders.stream().filter(o -> o.player().equals(player)).count();
        if (open >= MAX_OPEN_ORDERS)
            throw new IllegalStateException("걸어 둔 지정가 주문이 너무 많습니다(" + MAX_OPEN_ORDERS + "건). 일부를 취소하세요.");
        long reserved = 0;
        try {
            if (buying) {
                // 거래 비용까지 묶어 두어야 체결 때 모자라지 않는다.
                long gross = Math.multiplyExact(price, quantity);
                reserved = gross + desk.wallet().feeFor(gross);
                desk.wallet().reserveCash(reserved);
            } else {
                Math.multiplyExact(price, quantity); // 체결 확인 때 가격×수량을 계산하므로 미리 범위를 본다
                desk.wallet().lockShares(symbol, quantity);
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("처리할 수 있는 주문 범위를 초과했습니다.");
        }
        lobby.orders.add(new PendingOrder(lobby.nextOrderId++, player, symbol, buying, price, quantity, reserved,
                lobby.tick * Market.TICK_SECONDS));
        sendDesk(lobby.clients.get(player), desk);
        sendOrders(lobby, player);
        processOrders(lobby);
        broadcastMarket(lobby);
    }

    private void cancelOrder(Lobby lobby, UUID player, String idText) throws IOException {
        if (lobby.patrol.seats.get(player).view != Patrol.View.MARKET)
            throw new IllegalStateException("지정가 주문은 주식 창에서 취소할 수 있습니다.");
        long id = parsePositive(idText, "주문 번호를 확인하세요.");
        var order = lobby.orders.stream().filter(o -> o.id() == id && o.player().equals(player)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("취소할 주문이 없습니다."));
        release(lobby, order);
        lobby.orders.remove(order);
        sendDesk(lobby.clients.get(player), lobby.desks.get(player));
        sendOrders(lobby, player);
    }

    /** 묶어 둔 현금·주식을 돌려준다. */
    private static void release(Lobby lobby, PendingOrder order) {
        var desk = lobby.desks.get(order.player());
        if (desk == null) return;
        if (order.buy()) desk.wallet().releaseCash(order.reservedWon());
        else desk.wallet().unlockShares(order.symbol(), order.quantity());
    }

    /** 걸린 지정가 주문을 먼저 건 순서대로 확인해, 조건이 맞으면 체결한다. 매초와 주문을 건 직후에 부른다. */
    private void processOrders(Lobby lobby) throws IOException {
        for (var order : List.copyOf(lobby.orders)) {
            var pool = lobby.market.pool(order.symbol());
            boolean ready = order.buy()
                    ? pool.quoteBuy(order.quantity()) <= order.limitWon() * order.quantity()
                    : pool.quoteSell(order.quantity()) >= order.limitWon() * order.quantity();
            if (!ready) continue;
            lobby.orders.remove(order);
            release(lobby, order);
            try {
                execute(lobby, order.player(), order.symbol(), order.buy(), order.quantity(), true);
            } catch (IllegalArgumentException e) {
                log.warn("방 {} 지정가 주문 {} 체결 실패: {}", lobby.logName(), order.id(), e.getMessage());
            }
            sendOrders(lobby, order.player());
        }
    }

    /** 신용 매수: 1분마다 이자를 붙이고, 매초 모든 사람의 담보 비율을 확인한다. */
    private void processMargin(Lobby lobby) throws IOException {
        if (lobby.tick * Market.TICK_SECONDS % 60 == 0) {
            for (var entry : lobby.desks.entrySet()) {
                var wallet = entry.getValue().wallet();
                if (wallet.totalMarginDebt() == 0) continue;
                wallet.accrueMarginInterest(marginInterestBpsPerMinute);
                var client = lobby.clients.get(entry.getKey());
                if (client != null) sendDesk(client, entry.getValue()); // 늘어난 빚을 잔고 줄에 바로 보여 준다
            }
        }
        for (var player : List.copyOf(lobby.desks.keySet())) checkMargin(lobby, player);
    }

    /**
     * 반대매매: 담보 비율이 유지 비율 아래로 떨어진 종목은 그 종목 주식을 전부 팔아 빚을 갚는다.
     * 그 종목에 걸린 지정가 매도는 먼저 취소한다. 판 돈으로 빚을 다 못 갚으면 현금이 음수가 된다(파산).
     */
    private void checkMargin(Lobby lobby, UUID player) throws IOException {
        var desk = lobby.desks.get(player);
        if (desk == null) return;
        var wallet = desk.wallet();
        for (var symbol : wallet.marginSymbols()) {
            if (wallet.marginRatioPercent(symbol, lobby.market.spotPrice(symbol)) >= marginMaintenancePercent) continue;
            for (var order : List.copyOf(lobby.orders)) {
                if (order.player().equals(player) && !order.buy() && order.symbol().equals(symbol)) {
                    release(lobby, order);
                    lobby.orders.remove(order);
                }
            }
            long shares = wallet.holdings().getOrDefault(symbol, 0L);
            long debt = wallet.marginDebt(symbol);
            if (shares <= 0) continue;
            execute(lobby, player, symbol, false, shares, false, 1, "반대매매");
            var label = lobby.labels.get(player);
            var name = catalog.stock(symbol).name();
            log.info("방 {} {} {} 반대매매 (빚 {}원, 현금 {}원)", lobby.logName(), label, symbol, debt,
                    wallet.balance().cashWon());
            for (var client : lobby.clients.values()) {
                send(client, Map.of("type", "notice", "message", label + "의 " + name + " 신용 포지션이 반대매매됐습니다"
                        + (wallet.balance().cashWon() < 0 ? ". 빚을 다 못 갚아 파산 위기입니다!" : ".")));
            }
            sendOrders(lobby, player);
        }
    }

    /** 퇴근이나 퇴장 때 걸린 주문을 모두 취소하고 묶인 것을 돌려준다. */
    private static void cancelAll(Lobby lobby, UUID playerOrNull) {
        for (var order : List.copyOf(lobby.orders)) {
            if (playerOrNull != null && !order.player().equals(playerOrNull)) continue;
            release(lobby, order);
            lobby.orders.remove(order);
        }
    }

    private void sendOrders(Lobby lobby, UUID player) throws IOException {
        var session = lobby.clients.get(player);
        if (session == null) return;
        send(session, Map.of("type", "orders", "orders", lobby.orders.stream()
                .filter(o -> o.player().equals(player))
                .map(o -> Map.of("id", o.id(), "symbol", o.symbol(), "side", o.buy() ? "매수" : "매도",
                        "limitWon", o.limitWon(), "quantity", o.quantity(), "reservedWon", o.reservedWon(),
                        "placedSecond", o.placedSecond()))
                .toList()));
    }

    /** 차트 전체(종목별 봉)와 지금까지의 뉴스 시각. 게임 시작·재접속 때 보내고, 이후로는 시세 메시지로 이어 붙인다. */
    private void sendChart(WebSocketSession session, Lobby lobby) throws IOException {
        send(session, Map.of("type", "chart", "candleSeconds", PriceChart.CANDLE_SECONDS,
                "candles", lobby.market.chart().all(),
                "news", lobby.market.history().stream()
                        .map(n -> Map.of("number", n.number(), "second", n.second(), "kind", n.kind(),
                                "symbols", n.symbols(), "headline", n.headline())).toList(),
                "past", lobby.market.storyNotes()));
    }

    /** 이번 판에서 내가 한 거래. 주문할 때와 재접속할 때 보낸다. */
    private void sendMyTrades(WebSocketSession session, Lobby lobby, UUID player) throws IOException {
        var label = lobby.labels.get(player);
        send(session, Map.of("type", "myTrades",
                "trades", lobby.trades.stream().filter(t -> t.player().equals(label)).toList()));
    }

    private void runMarket(Lobby lobby) {
        try {
            while (true) {
                Thread.sleep(Market.TICK_MILLIS);
                lobby.lock.lockInterruptibly();
                try {
                    if (rooms.get(lobby.code) != lobby) return;
                    advanceMarket(lobby);
                    try {
                        announceMeetings(lobby);
                        processOrders(lobby);
                        processMargin(lobby);
                        if (lobby.tick >= lobby.sessionTicks) {
                            finish(lobby);
                            return;
                        }
                        broadcastMarket(lobby);
                    } catch (IOException e) {
                        log.warn("방 {} 전송 실패", lobby.code, e);
                    }
                } finally {
                    lobby.lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void advanceMarket(Lobby lobby) {
        lobby.tick++;
        // 같은 크기로 계속 떠들면 MIC가 다시 오지 않으므로 매초 지금 크기로 다시 잰다.
        long nowMillis = System.nanoTime() / 1_000_000;
        lobby.micLevels.forEach((id, level) -> lobby.patrol.hearMic(id, level, nowMillis));
        if (lobby.boss == null) lobby.boss = new Boss(tradeFeeBps, new SplittableRandom());
        if (lobby.tick % TICKS_PER_PATROL_BEAT == 0) {
            lobby.patrol.mood = lobby.boss.mood(lobby.market);
            lobby.patrol.setAway(lobby.bossAway(), lobby.tick / TICKS_PER_PATROL_BEAT);
            lobby.patrol.advance(lobby.tick / TICKS_PER_PATROL_BEAT);
            // 훈계 시작·종료는 순찰 박자에 일어나므로 이때 업무 시간 기록에 화면 상태를 알린다.
            // 연결이 끊긴 사람은 업무 시간에 넣지 않는다.
            long now = System.nanoTime();
            lobby.desks.forEach((id, desk) -> {
                var seat = lobby.patrol.seats.get(id);
                if (seat != null) {
                    desk.observeView(seat.view == Patrol.View.WORK && lobby.clients.containsKey(id)
                            && !lobby.lunchTime(), now);
                }
            });
        }
        lobby.market.advance(lobby.tick);
        bossTrades(lobby);
    }

    /**
     * 부장님 매매: 새 뉴스·찌라시를 읽고 판단을 몇 초 뒤로 걸어 두고, 때가 된 판단과 공포 손절을 체결한다.
     * 부장님 매매도 플레이어 매매처럼 가격에 흔적을 남기고 퇴근 결과의 매매 기록에 "부장님"으로 오른다.
     */
    private void bossTrades(Lobby lobby) {
        var boss = lobby.boss;
        var history = lobby.market.history();
        var mood = boss.mood(lobby.market);
        while (boss.seenNews < history.size()) {
            var item = history.get(boss.seenNews++);
            var rule = boss.decide(item, lobby.market.movesOf(item.number()), mood);
            // 플레이어가 뿌린 익명 찌라시(종목 정보 없음)는 Claude가 읽고 판단할 수 있다. 규칙으로는 무시한다.
            if (bossBrain.enabled() && lobby.bossCalls < bossBrain.maxCallsPerRound()) {
                lobby.bossCalls++;
                bossBrain.decide(situation(lobby, item, mood), lobby.market.pools().keySet(), rule)
                        .thenAccept(boss.incoming::addAll);
            } else {
                for (var decision : rule) boss.schedule(decision, lobby.tick);
            }
        }
        // Claude 답(또는 늦어서 대신한 규칙 판단)은 도착한 뒤 곧 실행한다(이미 몇 초 읽은 셈이다).
        for (Boss.Decision decision; (decision = boss.incoming.poll()) != null; ) boss.scheduleSoon(decision, lobby.tick);
        var decisions = new ArrayList<>(boss.due(lobby.tick));
        if (lobby.tick % 10 == 0) decisions.addAll(boss.panics(lobby.market));
        for (var decision : decisions) {
            if (decision.symbol() == null) {   // 주문 없이 혼잣말만
                boss.say(decision.line(), lobby.tick);
                continue;
            }
            var trade = boss.execute(decision, lobby.market);
            if (trade == null) continue;
            lobby.market.recordTrade(trade.symbol(), trade.spotBeforeWon(), trade.quantity());
            lobby.trades.add(new Settlement.TradeRecord(lobby.tick * Market.TICK_SECONDS, Boss.LABEL, trade.symbol(),
                    trade.buy() ? "매수" : "매도", trade.quantity(), trade.amountWon()));
            boss.say(decision.line() != null ? decision.line() : boss.defaultLine(trade, boss.mood(lobby.market)), lobby.tick);
        }
    }

    /** Claude에게 줄 상황: 뉴스, 부장님 지갑, 이번 판 종목과 가격. */
    private static BossBrain.Situation situation(Lobby lobby, Market.NewsItem item, Boss.Mood mood) {
        var market = lobby.market;
        var names = new LinkedHashMap<String, String>();
        var prices = new LinkedHashMap<String, Long>();
        for (var stock : market.catalog().stocks()) {
            names.put(stock.id(), stock.name());
            prices.put(stock.id(), market.spotPrice(stock.id()));
        }
        var wallet = lobby.boss.wallet;
        return new BossBrain.Situation(item.headline(), item.kind().equals("rumor"), lobby.boss.profitPercent(market),
                mood.id(), wallet.balance().cashWon(), new LinkedHashMap<>(wallet.holdings()), names, prices);
    }

    /** 화면에 보내는 부장님 상태: 기분, 자리에 앉아 있는지, 최근 혼잣말(몇 초 동안만). */
    private static Map<String, Object> bossView(Lobby lobby) {
        var view = new LinkedHashMap<String, Object>();
        var boss = lobby.boss;
        view.put("mood", boss == null ? "normal" : boss.mood(lobby.market).id());
        view.put("atDesk", lobby.patrol.atDesk && !lobby.bossAway());
        boolean speaking = boss != null && boss.line != null && lobby.tick - boss.lineTick <= BOSS_LINE_TICKS;
        view.put("line", speaking ? boss.line : null);
        view.put("lineSecond", speaking ? boss.lineTick * Market.TICK_SECONDS : -1);
        return view;
    }

    /** 부장님 혼잣말을 화면에 보여 주는 시간(틱). */
    private static final long BOSS_LINE_TICKS = 6;

    /** 퇴근: 이후 모든 조작을 막고, 순위와 이번 판의 매매 기록을 모두에게 공개한다. */
    private void finish(Lobby lobby) throws IOException {
        lobby.finished = true;
        // 체결되지 않은 지정가 주문은 퇴근과 함께 취소하고 묶인 현금·주식을 돌려준 뒤 정산한다.
        cancelAll(lobby, null);
        var desksByPlayer = new LinkedHashMap<String, WorkDesk>();
        for (var player : lobby.game.snapshot().players()) {
            var desk = lobby.desks.get(player);
            if (desk != null) desksByPlayer.put(lobby.labels.get(player), desk);
        }
        lobby.standings = Settlement.standings(desksByPlayer, lobby.market);
        var meetings = new HashMap<String, Integer>();
        lobby.patrol.seats.forEach((id, seat) -> meetings.put(lobby.labels.get(id), seat.meetings));
        lobby.awards = Settlement.awards(lobby.standings, meetings, startingCashWon);
        log.info("방 {} 퇴근 정산: {}", lobby.logName(), lobby.standings);
        desksByPlayer.forEach((label, desk) -> playtest.summary(lobby.logName(), label, desk.timings()));
        saveResults(lobby);
        for (var entry : lobby.clients.entrySet()) sendResult(lobby, entry.getKey(), entry.getValue());
    }

    /** 이름을 정한 사람의 결과를 저장한다. 솔로 결과는 명예의 전당에 쓰인다. 저장에 실패해도 정산은 그대로 보낸다. */
    private void saveResults(Lobby lobby) {
        var entries = new ArrayList<ResultService.Entry>();
        for (var standing : lobby.standings) {
            lobby.labels.forEach((player, label) -> {
                var profileId = lobby.profileIds.get(player);
                if (profileId != null && label.equals(standing.player()))
                    entries.add(new ResultService.Entry(profileId, standing.netWorthWon(), standing.bankrupt(), standing.rank()));
            });
        }
        if (entries.isEmpty()) return;
        try {
            results.record(lobby.solo ? "solo" : "multi", lobby.sessionSeconds, startingCashWon, lobby.code,
                    lobby.standings.size(), entries);
        } catch (RuntimeException e) {
            log.warn("방 {} 결과 저장 실패", lobby.logName(), e);
        }
    }

    private void sendResult(Lobby lobby, UUID player, WebSocketSession session) throws IOException {
        boolean recorded = lobby.profileIds.containsKey(player);
        send(session, Map.of("type", "result", "you", lobby.labels.get(player),
                "standings", lobby.standings, "awards", lobby.awards, "trades", lobby.trades,
                "news", lobby.market.history(), "leaks", lobby.leaks, "startingCashWon", startingCashWon,
                "recorded", recorded, "recordNotice", recorded
                        ? (lobby.solo ? "기록이 저장되고 명예의 전당에 반영됐습니다." : "기록이 저장됐습니다.")
                        : GUEST_NOTICE));
    }

    private void broadcastMarket(Lobby lobby) throws IOException {
        var history = lobby.market.history();
        var latest = history.isEmpty()
                ? new Market.NewsItem(0, "news", List.of(), "", null, 0) : history.getLast();
        var prices = new HashMap<String, Long>();
        var pools = new HashMap<String, StockPool.Reserves>();
        var books = new HashMap<String, Market.OrderBook>();
        lobby.market.pools().forEach((symbol, pool) -> {
            var reserves = pool.reserves();
            prices.put(symbol, reserves.cashWon() / reserves.shares());
            pools.put(symbol, reserves);
            books.put(symbol, lobby.market.orderBook(symbol));
        });
        var candles = lobby.market.chart().latest();
        // 동료 목록(훈계 중인지). 업무 게이지(의심)는 본인에게만 suspicion으로 보낸다.
        var colleagues = new LinkedHashMap<UUID, Map<String, Object>>();
        lobby.patrol.seats.forEach((id, s) -> colleagues.put(id, Map.of("label", lobby.labels.get(id),
                "meeting", s.view == Patrol.View.MEETING, "seat", s.listener.position())));
        var micLevels = micLevels(lobby);
        for (var entry : lobby.clients.entrySet()) {
            var seat = lobby.patrol.seats.get(entry.getKey());
            if (seat.view == Patrol.View.MEETING) {
                send(entry.getValue(), Map.of("type", "meeting", "newsNumber", latest.number(),
                        "reason", seat.meetingReason, "seat", seat.listener.position()));
                continue;
            }
            var wallet = lobby.desks.get(entry.getKey()).wallet();
            var market = lobby.market;
            send(entry.getValue(), Map.ofEntries(Map.entry("type", "market"),
                    Map.entry("portfolio", wallet.portfolio(market::pool)),
                    Map.entry("prices", prices), Map.entry("pools", pools),
                    Map.entry("holdings", wallet.holdings()), Map.entry("headline", latest.headline()),
                    Map.entry("newsKind", latest.kind()), Map.entry("newsNumber", latest.number()),
                    Map.entry("newsBody", latest.body() == null ? "" : latest.body()),
                    Map.entry("newsSecond", latest.second()), Map.entry("newsSymbols", latest.symbols()),
                    Map.entry("books", books), Map.entry("candles", candles),
                    Map.entry("view", seat.view), Map.entry("beat", lobby.patrol.beat),
                    Map.entry("walking", lobby.patrol.walking),
                    Map.entry("step", lobby.patrol.step(seat)),
                    Map.entry("coughBeat", seat.coughBeat),
                    Map.entry("suspicion", (int) Math.round(seat.suspicion)),
                    Map.entry("colleagues", colleagues.entrySet().stream()
                            .filter(e -> !e.getKey().equals(entry.getKey())).map(Map.Entry::getValue).toList()),
                    Map.entry("micLevels", micLevels),
                    Map.entry("boss", bossView(lobby)),
                    Map.entry("lunchStartsInSeconds", (lobby.lunchStartTick - lobby.tick) * Market.TICK_SECONDS),
                    Map.entry("lunchEndsInSeconds", (lobby.lunchEndTick - lobby.tick) * Market.TICK_SECONDS),
                    Map.entry("remainingSeconds",
                            (lobby.sessionTicks - lobby.tick) * Market.TICK_SECONDS)));
        }
    }

    /** SOLO [분]: 혼자 하는 방을 만들고 바로 시작한다. 회원만 명예의 전당에 오른다(비회원은 기록이 남지 않는다). */
    private void solo(WebSocketSession session, String minutesText) throws IOException {
        int minutes = 10;
        if (!minutesText.isEmpty()) {
            try {
                minutes = Integer.parseInt(minutesText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("근무 시간은 분 단위 정수여야 합니다.");
            }
        }
        if (!ResultService.BOARD_MINUTES.contains(minutes))
            throw new IllegalArgumentException("솔로 게임은 " + ResultService.BOARD_MINUTES + "분 중에서 고르세요.");
        var lobby = create(session, true, minutes * 60L);
        lobby.lock.lock();
        try {
            start(lobby, lobby.game.snapshot().hostId());
        } finally {
            lobby.lock.unlock();
        }
    }

    private Lobby create(WebSocketSession session, boolean solo, long seconds) throws IOException {
        var host = UUID.randomUUID();
        while (true) {
            var code = Integer.toString(100_000 + random.nextInt(900_000));
            var lobby = new Lobby(code, host, newMarket(seconds, solo), seconds, solo);
            lobby.lock.lock();
            try {
                if (rooms.putIfAbsent(code, lobby) != null) continue;
                attach(session, lobby, host);
                broadcast(lobby);
                sendChatHistory(session, lobby, host);
                return lobby;
            } finally {
                lobby.lock.unlock();
            }
        }
    }

    private void join(WebSocketSession session, String code) throws IOException {
        var lobby = rooms.get(code);
        if (lobby == null) throw new IllegalStateException("초대 코드를 확인하세요.");
        lobby.lock.lock();
        try {
            if (rooms.get(code) != lobby) throw new IllegalStateException("종료된 방입니다.");
            var profile = (ProfileService.View) session.getAttributes().get(PROFILE);
            if (profile != null && lobby.profileIds.containsValue(profile.id()))
                throw new IllegalStateException("이미 이 방에 들어와 있습니다(다른 창).");
            var wanted = profile != null ? profile.name() : (String) session.getAttributes().get(GUEST);
            if (wanted != null && lobby.labels.values().stream().anyMatch(wanted::equalsIgnoreCase))
                throw new IllegalStateException("방에 같은 이름이 있습니다. 닉네임을 바꿔 주세요.");
            var player = UUID.randomUUID();
            lobby.game.join(player);
            attach(session, lobby, player);
            broadcast(lobby);
            sendChatHistory(session, lobby, player);
        } finally {
            lobby.lock.unlock();
        }
    }

    /** 새로고침·연결 끊김 뒤 같은 자리로 돌아온다. 다른 창에 연결돼 있었다면 그 창은 끊는다. */
    private void resume(WebSocketSession session, String code, String token) throws IOException {
        var lobby = rooms.get(code);
        if (lobby == null) {
            send(session, Map.of("type", "resumeFailed", "message", "방이 끝나 다시 들어갈 수 없습니다."));
            return;
        }
        lobby.lock.lock();
        try {
            UUID player = lobby.tokens.entrySet().stream()
                    .filter(e -> e.getValue().equals(token)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (rooms.get(code) != lobby || player == null) {
                send(session, Map.of("type", "resumeFailed", "message", "이 방에 다시 들어갈 수 없습니다."));
                return;
            }
            var previous = lobby.clients.put(player, session);
            session.getAttributes().put(MEMBERSHIP, new Membership(lobby, player));
            lobby.offlineSince.remove(player);
            if (previous != null && previous != session) {
                previous.getAttributes().remove(MEMBERSHIP);
                send(previous, Map.of("type", "replaced", "message", "다른 창에서 이 자리로 접속해 이 창은 연결을 끊었습니다."));
                previous.close(CloseStatus.POLICY_VIOLATION.withReason("다른 창에서 접속"));
            }
            log.info("방 {} {} 재접속", code, lobby.labels.getOrDefault(player, "대기실 참가자"));
            broadcast(lobby);
            sendChatHistory(session, lobby, player);
            if (!lobby.started()) return;
            sendChart(session, lobby);
            send(session, Map.of("type", "catalog", "stocks", catalogMessage(lobby), "loan", Map.of("interestPercent", loanInterestPercent, "maxWon", loanMaxWon),
                    "feeBps", tradeFeeBps, "margin", Map.of("leverages", MARGIN_LEVERAGES,
                            "interestBpsPerMinute", marginInterestBpsPerMinute,
                            "maintenancePercent", marginMaintenancePercent)));
            sendDesk(session, lobby.desks.get(player));
            sendMyTrades(session, lobby, player);
            sendOrders(lobby, player);
            if (lobby.finished) {
                sendResult(lobby, player, session);
            } else {
                var seat = lobby.patrol.seats.get(player);
                seat.present = true;
                lobby.desks.get(player).observeView(seat.view == Patrol.View.WORK, System.nanoTime());
                broadcastMarket(lobby);
            }
        } finally {
            lobby.lock.unlock();
        }
    }

    /** 판마다 상장 종목을 새로 뽑고, 뉴스 덱도 그 종목에 맞춘다. */
    private Market newMarket(long seconds, boolean solo) {
        var random = new SplittableRandom();
        var listed = catalog.pick(stocksPerGame, random);
        return new Market(listed, listed == catalog ? news : news.restrictTo(listed), random, seconds, solo);
    }

    /** 세션에 붙여 두는 이 기기의 자동 로그인 열쇠(로그아웃 때 지운다)와 비회원 닉네임. */
    private static final String LOGIN_TOKEN = "loginToken", GUEST = "guest";

    /**
     * SIGNUP 이름 비밀번호 / LOGIN 이름 비밀번호 / AUTOLOGIN 열쇠 / LOGOUT / GUEST 닉네임 /
     * RESET 이름 복구코드 새비밀번호. 방 밖에서만 쓴다.
     * 회원은 기록·꾸미기가 남고 명예의 전당에 오른다. 비회원은 닉네임만 쓰고 아무것도 저장하지 않는다.
     */
    private void accountCommand(WebSocketSession session, String command) throws IOException {
        var attributes = session.getAttributes();
        if (command.equals("LOGOUT")) {
            profiles.logout((String) attributes.remove(LOGIN_TOKEN));
            attributes.remove(PROFILE);
            attributes.remove(GUEST);
            send(session, Map.of("type", "loggedOut"));
            return;
        }
        if (command.startsWith("GUEST ")) {
            var name = command.substring(6).strip();
            profiles.checkGuestName(name);
            attributes.remove(PROFILE);
            attributes.remove(LOGIN_TOKEN);
            attributes.put(GUEST, name);
            send(session, Map.of("type", "guest", "name", name, "notice", GUEST_NOTICE));
            return;
        }
        if (command.startsWith("AUTOLOGIN ")) {
            ProfileService.View found;
            var token = command.substring(10).strip();
            try {
                found = profiles.resume(token);
            } catch (IllegalArgumentException e) {
                send(session, Map.of("type", "profileFailed", "message", e.getMessage()));
                return;
            }
            signIn(session, found, token);
            send(session, profileMessage(found, null));
            return;
        }
        if (command.startsWith("RESET ")) {
            var parts = command.substring(6).strip().split(" ", 3);
            if (parts.length < 3) throw new IllegalArgumentException("이름, 복구 코드, 새 비밀번호를 함께 보내세요.");
            var reset = profiles.resetPassword(parts[0], parts[1], parts[2]);
            signIn(session, reset.profile(), reset.token());
            send(session, profileMessage(reset.profile(), reset.token(), reset.recoveryCode()));
            return;
        }
        boolean signup = command.startsWith("SIGNUP ");
        var rest = command.substring(signup ? 7 : 6).strip();
        int space = rest.indexOf(' ');
        if (space < 0) throw new IllegalArgumentException("이름과 비밀번호를 함께 보내세요.");
        var name = rest.substring(0, space);
        var password = rest.substring(space + 1);
        var result = signup ? profiles.signup(name, password) : profiles.login(name, password);
        signIn(session, result.profile(), result.token());
        send(session, profileMessage(result.profile(), result.token(), result.recoveryCode()));
    }

    private static void signIn(WebSocketSession session, ProfileService.View profile, String token) {
        session.getAttributes().remove(GUEST);
        session.getAttributes().put(PROFILE, profile);
        session.getAttributes().put(LOGIN_TOKEN, token);
    }

    /** 비회원에게 보여 줄 안내. */
    private static final String GUEST_NOTICE =
            "비회원은 판 결과와 꾸미기가 저장되지 않고 명예의 전당에도 오르지 않습니다. 기록을 남기려면 회원 가입하세요.";

    /**
     * PROFILE NAME 새이름 / PROFILE CUSTOM {json} / PROFILE PASSWORD 새비밀번호 / PROFILE RECOVERY(복구 코드 새로 만들기)는
     * 회원만, 방 안에서도 쓸 수 있다. PROFILE DELETE 비밀번호(회원 탈퇴)는 방 밖에서만.
     */
    private void profileCommand(WebSocketSession session, String rest, boolean inRoom) throws IOException {
        int space = rest.indexOf(' ');
        var verb = space < 0 ? rest : rest.substring(0, space);
        var arg = space < 0 ? "" : rest.substring(space + 1).strip();
        var current = (ProfileService.View) session.getAttributes().get(PROFILE);
        if (current == null) throw new IllegalStateException("회원만 할 수 있습니다. 로그인하세요.");
        switch (verb) {
            case "NAME", "CUSTOM" -> {
                var updated = verb.equals("NAME") ? profiles.rename(current.id(), arg)
                        : profiles.customize(current.id(), arg);
                session.getAttributes().put(PROFILE, updated);
                send(session, profileMessage(updated, null));
            }
            case "PASSWORD" -> {
                if (space < 0) throw new IllegalArgumentException("새 비밀번호를 함께 보내세요.");
                profiles.changePassword(current.id(), rest.substring(space + 1));
                send(session, Map.of("type", "notice", "message", "비밀번호를 바꿨습니다."));
            }
            case "RECOVERY" -> send(session, Map.of("type", "recoveryCode", "code", profiles.renewRecoveryCode(current.id())));
            case "DELETE" -> {
                if (inRoom) throw new IllegalStateException("방에서 나온 뒤에 탈퇴할 수 있습니다.");
                if (space < 0) throw new IllegalArgumentException("비밀번호를 함께 보내세요.");
                profiles.delete(current.id(), rest.substring(space + 1));
                session.getAttributes().remove(PROFILE);
                session.getAttributes().remove(LOGIN_TOKEN);
                send(session, Map.of("type", "accountDeleted", "message", "회원 탈퇴가 끝났습니다. 기록도 모두 지웠습니다."));
            }
            default -> throw new IllegalArgumentException("PROFILE 명령은 NAME, CUSTOM, PASSWORD, RECOVERY, DELETE 중 하나입니다.");
        }
    }

    private static Map<String, Object> profileMessage(ProfileService.View profile, String token) {
        return profileMessage(profile, token, null);
    }

    /** @param recoveryCode 새로 만든 복구 코드(가입·복구 때만). 화면이 한 번 보여 주고 버린다. */
    private static Map<String, Object> profileMessage(ProfileService.View profile, String token, String recoveryCode) {
        var message = new LinkedHashMap<String, Object>();
        message.put("type", "profile");
        message.put("member", true);
        message.put("name", profile.name());
        message.put("customization", profile.customization());
        if (token != null) message.put("token", token);
        if (recoveryCode != null) message.put("recoveryCode", recoveryCode);
        return message;
    }

    /** 화면이 종목 코드를 이름으로 바꿀 수 있게 게임 시작·재접속 때 보내는 이번 판 종목 목록. */
    private static List<Map<String, String>> catalogMessage(Lobby lobby) {
        var catalog = lobby.market.catalog();
        return catalog.stocks().stream()
                .map(s -> s.derived()
                        ? Map.of("id", s.id(), "name", s.name(), "sector", catalog.sectorOf(s),
                                "underlying", s.underlying(), "leverage", String.valueOf(s.leverage()))
                        : Map.of("id", s.id(), "name", s.name(), "sector", catalog.sectorOf(s))).toList();
    }

    private void attach(WebSocketSession session, Lobby lobby, UUID player) {
        lobby.clients.put(player, session);
        // 회원은 회원 이름, 비회원은 닉네임, 둘 다 없으면 P1, P2…로 부른다(이름 규칙이 P숫자를 막아 겹치지 않는다).
        var profile = (ProfileService.View) session.getAttributes().get(PROFILE);
        var guest = (String) session.getAttributes().get(GUEST);
        int number = lobby.nextLabel++;
        lobby.labels.put(player, profile != null ? profile.name() : guest != null ? guest : "P" + number);
        if (profile != null) lobby.profileIds.put(player, profile.id());
        var bytes = new byte[18];
        random.nextBytes(bytes);
        lobby.tokens.put(player, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        session.getAttributes().put(MEMBERSHIP, new Membership(lobby, player));
    }

    private void broadcast(Lobby lobby) throws IOException {
        var state = lobby.game.snapshot();
        var offline = List.copyOf(lobby.offlineSince.keySet());
        // 메신저 받는 사람 목록과 화면 표시용. 입장 순서대로.
        var members = state.players().stream().map(id -> Map.of(
                "label", lobby.labels.get(id), "host", id.equals(state.hostId()),
                "online", lobby.clients.containsKey(id), "member", lobby.profileIds.containsKey(id),
                "voice", lobby.voiceMuted.containsKey(id), "muted", lobby.voiceMuted.getOrDefault(id, false))).toList();
        for (var entry : lobby.clients.entrySet()) {
            send(entry.getValue(), Map.ofEntries(Map.entry("type", "state"), Map.entry("code", lobby.code),
                    Map.entry("you", entry.getKey()), Map.entry("token", lobby.tokens.get(entry.getKey())),
                    Map.entry("room", state), Map.entry("offline", offline), Map.entry("round", lobby.round),
                    Map.entry("sessionSeconds", lobby.sessionSeconds), Map.entry("members", members),
                    Map.entry("solo", lobby.solo), Map.entry("youLabel", lobby.labels.get(entry.getKey()))));
        }
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        var message = new TextMessage(json.writeValueAsString(payload));
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(message);
        }
    }

    /** 연결이 끊겨도 자리와 자산은 그대로 둔다. 주식 창을 보던 중이면 업무 창으로 돌려 둔다(자리 비움). */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
            throws IOException {
        var member = (Membership) session.getAttributes().remove(MEMBERSHIP);
        if (member == null) return;
        var lobby = member.lobby();
        lobby.lock.lock();
        try {
            if (lobby.clients.get(member.player()) != session) return;
            lobby.clients.remove(member.player());
            clearMic(lobby, member.player());
            lobby.voiceMuted.remove(member.player());
            long now = System.nanoTime();
            lobby.offlineSince.put(member.player(), now);
            var seat = lobby.patrol.seats.get(member.player());
            if (seat != null) {
                seat.present = false;
                if (seat.view.slacking()) seat.view = Patrol.View.WORK;
            }
            var desk = lobby.desks.get(member.player());
            if (desk != null) desk.observeView(false, now);
            if (rooms.get(lobby.code) == lobby) broadcast(lobby);
        } finally {
            lobby.lock.unlock();
        }
    }

    /** 음성 연결 정보 한 건의 최대 길이(SDP는 보통 수 KB). */
    private static final int MAX_VOICE_SIGNAL_CHARS = 16_000;
    /** 한 사람이 1초에 보낼 수 있는 음성 연결 정보 수. ICE 후보가 몰려도 넉넉한 값이다. */
    private static final int MAX_VOICE_SIGNALS_PER_SECOND = 60;

    /**
     * 음성 대화. 음성은 브라우저끼리 WebRTC로 직접 주고받고, 서버는 연결 정보(offer·answer·ICE 후보)만 전달한다.
     * VOICE JOIN / VOICE LEAVE / VOICE MUTE / VOICE UNMUTE: 참여 상태가 바뀌면 state.members에 반영해 모두에게 알린다.
     * VOICE SIGNAL 받는사람 {json}: 같은 방에서 음성에 참여한 사람에게 voiceSignal로 그대로 전달한다.
     * 대기실·게임 중·퇴근 뒤 언제나 쓸 수 있다. 솔로 방에는 없다.
     */
    private void voiceCommand(Lobby lobby, UUID player, String rest) throws IOException {
        if (lobby.solo) throw new IllegalStateException("혼자 하는 방이라 음성 대화가 없습니다.");
        int space = rest.indexOf(' ');
        var verb = space < 0 ? rest.strip() : rest.substring(0, space);
        switch (verb) {
            case "JOIN" -> {
                lobby.voiceMuted.put(player, false);
                // 연결을 맺기 전에 쓸 STUN·TURN 서버를 먼저 알려 준다(state보다 앞에 도착한다).
                var session = lobby.clients.get(player);
                if (session != null) send(session, Map.of("type", "voiceConfig", "iceServers", voiceIceServers));
                broadcast(lobby);
            }
            case "LEAVE" -> {
                if (lobby.voiceMuted.remove(player) != null) broadcast(lobby);
            }
            case "MUTE", "UNMUTE" -> {
                if (!lobby.voiceMuted.containsKey(player)) throw new IllegalStateException("먼저 음성 대화에 참여하세요.");
                lobby.voiceMuted.put(player, verb.equals("MUTE"));
                broadcast(lobby);
            }
            case "SIGNAL" -> relayVoiceSignal(lobby, player, space < 0 ? "" : rest.substring(space + 1).strip());
            default -> throw new IllegalArgumentException("VOICE 명령은 JOIN, LEAVE, MUTE, UNMUTE, SIGNAL 중 하나입니다.");
        }
    }

    /**
     * VOICE_ICE_SERVERS(환경 변수) 또는 game.voice-ice-servers 설정. 예:
     * [{"urls":"stun:stun.l.google.com:19302"},{"urls":["turn:a.example.com:3478","turns:a.example.com:443?transport=tcp"],"username":"u","credential":"p"}]
     * 휴대폰 데이터망처럼 직접 연결이 막힌 곳에서는 TURN(중계)이 있어야 목소리가 오간다.
     */
    static com.fasterxml.jackson.databind.JsonNode parseIceServers(ObjectMapper json, String text) {
        com.fasterxml.jackson.databind.JsonNode servers;
        try {
            servers = json.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("음성 ICE 서버 설정(VOICE_ICE_SERVERS)은 JSON 배열이어야 합니다: " + e.getMessage());
        }
        if (servers == null || !servers.isArray() || servers.isEmpty())
            throw new IllegalArgumentException("음성 ICE 서버 설정(VOICE_ICE_SERVERS)은 서버가 하나 이상인 JSON 배열이어야 합니다.");
        for (var server : servers) {
            var urls = server.path("urls");
            boolean ok = urls.isTextual() || (urls.isArray() && !urls.isEmpty());
            if (!ok) throw new IllegalArgumentException("음성 ICE 서버마다 urls가 있어야 합니다: " + server);
            boolean turn = urls.toString().contains("turn:") || urls.toString().contains("turns:");
            if (turn && (!server.hasNonNull("username") || !server.hasNonNull("credential")))
                throw new IllegalArgumentException("TURN 서버에는 username과 credential이 있어야 합니다: " + urls);
        }
        return servers;
    }

    private void relayVoiceSignal(Lobby lobby, UUID player, String rest) throws IOException {
        if (!lobby.voiceMuted.containsKey(player)) throw new IllegalStateException("먼저 음성 대화에 참여하세요.");
        int space = rest.indexOf(' ');
        if (space < 0) throw new IllegalArgumentException("받는 사람과 연결 정보를 함께 보내세요.");
        var toLabel = rest.substring(0, space);
        var payload = rest.substring(space + 1);
        if (payload.length() > MAX_VOICE_SIGNAL_CHARS) throw new IllegalArgumentException("음성 연결 정보가 너무 깁니다.");
        long now = System.nanoTime();
        var window = lobby.voiceSignalWindow.computeIfAbsent(player, id -> new long[]{now, 0});
        if (now - window[0] > 1_000_000_000L) {
            window[0] = now;
            window[1] = 0;
        }
        if (++window[1] > MAX_VOICE_SIGNALS_PER_SECOND) throw new IllegalStateException("음성 연결 정보를 너무 자주 보냅니다.");
        UUID to = null;
        for (var entry : lobby.labels.entrySet()) if (entry.getValue().equals(toLabel)) to = entry.getKey();
        if (to == null || to.equals(player) || !lobby.voiceMuted.containsKey(to))
            throw new IllegalArgumentException("음성 대화에 참여한 사람에게만 보낼 수 있습니다: " + toLabel);
        com.fasterxml.jackson.databind.JsonNode data;
        try {
            data = json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("음성 연결 정보는 JSON 객체여야 합니다.");
        }
        if (data == null || !data.isObject()) throw new IllegalArgumentException("음성 연결 정보는 JSON 객체여야 합니다.");
        var session = lobby.clients.get(to);
        if (session != null)
            send(session, Map.of("type", "voiceSignal", "from", lobby.labels.get(player), "data", data));
    }

    /** 마이크 소리 크기 알림 사이의 최소 간격. 크기가 0이 될 때는 바로 알린다. */
    private static final long MIC_BROADCAST_GAP_NANOS = 150_000_000L;

    /**
     * MIC 0~100: 브라우저가 잰 내 마이크 소리 크기. MIC OFF: 마이크를 껐다.
     * 같은 방 모두(나 포함)에게 mic 메시지로 바로 알린다.
     * 대기실·게임 중·퇴근 뒤 언제든 보낼 수 있다. 너무 잦으면 값만 저장하고, 매초 시세 메시지의 micLevels로 따라잡는다.
     */
    private void setMicLevel(Lobby lobby, UUID player, String value) throws IOException {
        if (value.equals("OFF")) {
            clearMic(lobby, player);
            return;
        }
        int level;
        try {
            level = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("소리 크기는 0~100 사이의 정수여야 합니다.");
        }
        if (level < 0 || level > 100) throw new IllegalArgumentException("소리 크기는 0~100 사이의 정수여야 합니다.");
        Integer previous = lobby.micLevels.put(player, level);
        if (!lobby.finished) lobby.patrol.hearMic(player, level, System.nanoTime() / 1_000_000);
        if (previous != null && previous == level) return;
        long now = System.nanoTime();
        Long sentAt = lobby.micSentAt.get(player);
        if (level != 0 && sentAt != null && now - sentAt < MIC_BROADCAST_GAP_NANOS) return;
        lobby.micSentAt.put(player, now);
        broadcastMic(lobby, player, level, true);
    }

    /** 마이크를 끄거나 연결이 끊기거나 나가면 소리 크기를 지우고 꺼졌다고(on: false) 알린다. */
    private void clearMic(Lobby lobby, UUID player) throws IOException {
        lobby.micSentAt.remove(player);
        if (lobby.micLevels.remove(player) != null) broadcastMic(lobby, player, 0, false);
    }

    private void broadcastMic(Lobby lobby, UUID player, int level, boolean on) throws IOException {
        var message = Map.of("type", "mic", "player", lobby.labels.get(player), "level", level, "on", on);
        for (var client : lobby.clients.values()) send(client, message);
    }

    /** 이름(P1…)별 지금 마이크 소리 크기. 마이크를 켜지 않은 사람은 빠진다. */
    private static Map<String, Integer> micLevels(Lobby lobby) {
        var levels = new java.util.TreeMap<String, Integer>();
        lobby.micLevels.forEach((id, level) -> levels.put(lobby.labels.get(id), level));
        return levels;
    }

    /** 스스로 나가거나 대기실에서 오래 돌아오지 않은 경우. 방장이 나가면 방이 닫힌다. */
    private void leave(Lobby lobby, UUID player) throws IOException {
        lobby.clients.remove(player);
        clearMic(lobby, player);
        lobby.voiceMuted.remove(player);
        lobby.tokens.remove(player);
        lobby.offlineSince.remove(player);
        cancelAll(lobby, player);
        var leftDesk = lobby.desks.remove(player);
        if (leftDesk != null && !lobby.finished) {
            playtest.summary(lobby.logName(), lobby.labels.get(player), leftDesk.timings());
        }
        lobby.patrol.seats.remove(player);
        if (rooms.get(lobby.code) != lobby) return;
        if (player.equals(lobby.game.snapshot().hostId())) {
            close(lobby, "방장이 나가 방이 종료됐습니다.");
        } else {
            lobby.game.leave(player);
            broadcast(lobby);
        }
    }

    /**
     * 방장이 게임 중이나 퇴근 뒤에 나가기를 누른 경우. 방을 닫지 않고 이번 판을 끝내 모두 같은 방 대기실로 돌린다.
     * 대기실에서 나가기를 누르면 leave()에서 지금처럼 방이 닫힌다.
     */
    private void backToLobby(Lobby lobby) throws IOException {
        if (lobby.marketClock != null) lobby.marketClock.interrupt();
        if (!lobby.finished) {
            lobby.desks.forEach((id, desk) -> playtest.summary(lobby.logName(), lobby.labels.get(id), desk.timings()));
        }
        lobby.game.reset(lobby.game.snapshot().hostId());
        lobby.nextRound(newMarket(lobby.sessionSeconds, lobby.solo));
        log.info("방 {} 방장이 나가 {}번째 판 대기실로 돌아감", lobby.code, lobby.round);
        broadcast(lobby);
    }

    private void close(Lobby lobby, String reason) throws IOException {
        rooms.remove(lobby.code, lobby);
        if (lobby.marketClock != null) lobby.marketClock.interrupt();
        if (!lobby.finished) {
            lobby.desks.forEach((id, desk) -> playtest.summary(lobby.logName(), lobby.labels.get(id), desk.timings()));
        }
        for (var client : lobby.clients.values()) {
            client.getAttributes().remove(MEMBERSHIP);
            send(client, Map.of("type", "closed", "message", reason));
        }
        lobby.tokens.clear();
        log.info("방 {} 종료: {}", lobby.code, reason);
    }

    /** 대기실에서 돌아오지 않는 사람을 내보내고, 모두 떠난 지 오래된 방을 정리한다. */
    private void reapRooms() {
        long now = System.nanoTime();
        for (var lobby : rooms.values()) {
            lobby.lock.lock();
            try {
                if (rooms.get(lobby.code) != lobby) continue;
                if (!lobby.started()) {
                    for (var entry : List.copyOf(lobby.offlineSince.entrySet())) {
                        if (now - entry.getValue() < LOBBY_GRACE_NANOS) continue;
                        leave(lobby, entry.getKey());
                        if (rooms.get(lobby.code) != lobby) break;
                    }
                } else if (lobby.clients.isEmpty() && lobby.offlineSince.values().stream()
                        .allMatch(since -> now - since >= EMPTY_ROOM_GRACE_NANOS)) {
                    close(lobby, "모두 나간 지 오래되어 방을 정리했습니다.");
                }
            } catch (IOException | RuntimeException e) {
                log.warn("방 {} 정리 실패", lobby.code, e);
            } finally {
                lobby.lock.unlock();
            }
        }
    }
}
