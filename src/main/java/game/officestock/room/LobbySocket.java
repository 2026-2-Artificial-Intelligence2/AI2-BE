package game.officestock.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import game.officestock.market.Market;
import game.officestock.market.NewsCatalog;
import game.officestock.market.StockCatalog;
import game.officestock.market.StockPool;
import game.officestock.player.WorkDesk;
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
    private final PlaytestLog playtest;
    private final StockCatalog catalog;
    private final NewsCatalog news;
    /** 화면이 종목 코드를 이름으로 바꿀 수 있게 게임 시작·재접속 때 보내는 목록. */
    private final List<Map<String, String>> catalogMessage;

    public LobbySocket(ObjectMapper json, PlaytestLog playtest, StockCatalog catalog, NewsCatalog news,
            @Value("${game.starting-cash-won}") long startingCashWon,
            @Value("${game.work-reward-won}") long workRewardWon,
            @Value("${game.session-seconds}") long sessionSeconds,
            @Value("${game.lunch-early-return-chance}") double lunchEarlyReturnChance,
            @Value("${game.loan-interest-percent}") int loanInterestPercent,
            @Value("${game.loan-max-won}") long loanMaxWon) {
        if (startingCashWon < 0 || workRewardWon <= 0 || sessionSeconds <= 0) {
            throw new IllegalArgumentException("시작 자금, 업무 수당, 근무 시간 설정을 확인하세요.");
        }
        this.sessionSeconds = sessionSeconds;
        this.lunchEarlyReturnChance = lunchEarlyReturnChance;
        if (loanInterestPercent < 0) throw new IllegalArgumentException("비상 대출 이자를 확인하세요.");
        this.loanInterestPercent = loanInterestPercent;
        if (loanMaxWon <= 0) throw new IllegalArgumentException("비상 대출 상한을 확인하세요.");
        this.loanMaxWon = loanMaxWon;
        this.startingCashWon = startingCashWon;
        this.workRewardWon = workRewardWon;
        this.json = json;
        this.playtest = playtest;
        this.catalog = catalog;
        this.news = news;
        this.catalogMessage = catalog.stocks().stream()
                .map(s -> Map.of("id", s.id(), "name", s.name())).toList();
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

        // 아래는 한 판의 상태. 같은 방에서 다시 하면 nextRound()로 새로 만든다.
        final Map<UUID, WorkDesk> desks = new HashMap<>();
        /** 기록용 이름(P1, P2…). 게임 시작 때 입장 순서로 정한다. */
        final Map<UUID, String> labels = new HashMap<>();
        Market market;
        Patrol patrol = new Patrol();
        final List<Settlement.TradeRecord> trades = new ArrayList<>();
        List<Settlement.Standing> standings;
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

        Lobby(String code, UUID host, Market market, long sessionSeconds) {
            this.code = code;
            this.game = new GameRoom(host);
            this.market = market;
            this.sessionSeconds = sessionSeconds;
            this.sessionTicks = sessionSeconds / Market.TICK_SECONDS;
        }

        void nextRound(Market freshMarket) {
            round++;
            desks.clear();
            labels.clear();
            market = freshMarket;
            patrol = new Patrol();
            trades.clear();
            standings = null;
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

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws IOException {
        var command = message.getPayload().strip();
        var member = (Membership) session.getAttributes().get(MEMBERSHIP);
        try {
            if (member != null) {
                var lobby = member.lobby();
                lobby.lock.lock();
                try {
                    if (rooms.get(lobby.code) != lobby) {
                        throw new IllegalStateException("종료된 방입니다.");
                    }
                    if (command.equals("LEAVE")) {
                        session.getAttributes().remove(MEMBERSHIP);
                        send(session, Map.of("type", "closed", "message", "방에서 나왔습니다."));
                        leave(lobby, member.player());
                        return;
                    }
                    if (command.equals("RESTART")) {
                        if (!lobby.finished) throw new IllegalStateException("퇴근한 뒤에 다시 할 수 있습니다.");
                        lobby.game.reset(member.player());
                        lobby.nextRound(newMarket(lobby.sessionSeconds));
                        log.info("방 {} {}번째 판 대기", lobby.code, lobby.round);
                        broadcast(lobby);
                        return;
                    }
                    if (command.startsWith("LENGTH ")) {
                        setLength(lobby, member.player(), command.substring(7).strip());
                        return;
                    }
                    if (lobby.finished) {
                        throw new IllegalStateException("퇴근했습니다. 결과를 확인하세요.");
                    }
                    if (command.equals("SWITCH")) {
                        var seat = lobby.patrol.seats.get(member.player());
                        if (seat == null || seat.view == Patrol.View.MEETING)
                            throw new IllegalStateException("현재 화면을 전환할 수 없습니다.");
                        seat.view = seat.view == Patrol.View.WORK ? Patrol.View.MARKET : Patrol.View.WORK;
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
                    if (command.startsWith("BUY ") || command.startsWith("SELL ")) {
                        trade(session, member, command);
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
                create(session);
            } else if (command.startsWith("JOIN ")) {
                join(session, command.substring(5).strip().toUpperCase(Locale.ROOT));
            } else if (command.startsWith("RESUME ")) {
                var fields = command.split("\\s+");
                if (fields.length != 3) throw new IllegalArgumentException("재접속 정보가 올바르지 않습니다.");
                resume(session, fields[1], fields[2]);
            } else {
                throw new IllegalStateException("방을 만들거나 초대 코드를 입력하세요.");
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            send(session, Map.of("type", "error", "message", e.getMessage()));
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
        lobby.sessionSeconds = minutes * 60L;
        broadcast(lobby);
    }

    private void start(Lobby lobby, UUID requester) throws IOException {
        if (lobby.started()) return;
        lobby.game.start(requester, Instant.now());
        lobby.sessionTicks = lobby.sessionSeconds / Market.TICK_SECONDS;
        lobby.market = newMarket(lobby.sessionSeconds);
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
            lobby.labels.putIfAbsent(player, "P" + (players.indexOf(player) + 1));
            lobby.desks.computeIfAbsent(player, id -> new WorkDesk(startingCashWon, workRewardWon, now));
            if (!lobby.clients.containsKey(player)) {
                lobby.desks.get(player).observeView(false, now);
                lobby.patrol.seats.get(player).present = false;
            }
        }
        broadcast(lobby);
        for (var entry : lobby.clients.entrySet()) {
            send(entry.getValue(), Map.of("type", "catalog", "stocks", catalogMessage, "loan", Map.of("interestPercent", loanInterestPercent, "maxWon", loanMaxWon)));
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
        var fields = command.split("\\s+");
        if (fields.length != 3) throw new IllegalArgumentException("업무 번호와 답을 입력하세요.");
        WorkDesk.TaskTiming timing;
        try {
            timing = desk.submit(Long.parseLong(fields[1]), Integer.parseInt(fields[2]), System.nanoTime());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("업무 번호와 답은 정수여야 합니다.");
        }
        if (timing != null) {
            member.lobby().patrol.onWorkDone(member.player());
            playtest.task(member.lobby().logName(), member.lobby().labels.get(member.player()), timing);
        }
        sendDesk(session, desk);
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

    private void trade(WebSocketSession session, Membership member, String command)
            throws IOException {
        var lobby = member.lobby();
        var desk = lobby.desks.get(member.player());
        if (desk == null) throw new IllegalStateException("게임 시작 후 주문하세요.");
        if (lobby.patrol.seats.get(member.player()).view != Patrol.View.MARKET) throw new IllegalStateException("주식 창에서만 거래할 수 있습니다.");

        var fields = command.split("\\s+");
        if (fields.length != 3) throw new IllegalArgumentException("종목과 수량을 확인하세요.");
        var symbol = fields[1];
        var pool = lobby.market.pool(symbol);
        if (pool == null) throw new IllegalArgumentException("등록되지 않은 종목입니다.");

        long quantity;
        try {
            quantity = Long.parseLong(fields[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("수량은 정수여야 합니다.");
        }
        boolean buying = fields[0].equals("BUY");
        long amount;
        long spotBefore = lobby.market.spotPrice(symbol);
        try {
            amount = buying ? desk.wallet().buy(symbol, pool, quantity)
                            : desk.wallet().sell(symbol, pool, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("처리할 수 있는 주문 범위를 초과했습니다.");
        }
        lobby.market.recordTrade(symbol, spotBefore);
        lobby.trades.add(new Settlement.TradeRecord(lobby.tick * Market.TICK_SECONDS,
                lobby.labels.get(member.player()), symbol, buying ? "매수" : "매도", quantity, amount));
        sendDesk(session, desk);
        send(session, Map.of("type", "fill", "symbol", symbol, "quantity", quantity,
                "side", buying ? "매수" : "매도", "amount", amount));
        sendMyTrades(session, lobby, member.player());
        broadcastMarket(lobby);
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
        if (lobby.tick % TICKS_PER_PATROL_BEAT == 0) {
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
    }

    /** 퇴근: 이후 모든 조작을 막고, 순위와 이번 판의 매매 기록을 모두에게 공개한다. */
    private void finish(Lobby lobby) throws IOException {
        lobby.finished = true;
        var desksByPlayer = new LinkedHashMap<String, WorkDesk>();
        for (var player : lobby.game.snapshot().players()) {
            var desk = lobby.desks.get(player);
            if (desk != null) desksByPlayer.put(lobby.labels.get(player), desk);
        }
        lobby.standings = Settlement.standings(desksByPlayer, lobby.market);
        log.info("방 {} 퇴근 정산: {}", lobby.logName(), lobby.standings);
        desksByPlayer.forEach((label, desk) -> playtest.summary(lobby.logName(), label, desk.timings()));
        for (var entry : lobby.clients.entrySet()) sendResult(lobby, entry.getKey(), entry.getValue());
    }

    private void sendResult(Lobby lobby, UUID player, WebSocketSession session) throws IOException {
        send(session, Map.of("type", "result", "you", lobby.labels.get(player),
                "standings", lobby.standings, "trades", lobby.trades,
                "news", lobby.market.history()));
    }

    private void broadcastMarket(Lobby lobby) throws IOException {
        var history = lobby.market.history();
        var latest = history.isEmpty()
                ? new Market.NewsItem(0, "news", List.of(), "", null) : history.getLast();
        var prices = new HashMap<String, Long>();
        var pools = new HashMap<String, StockPool.Reserves>();
        lobby.market.pools().forEach((symbol, pool) -> {
            var reserves = pool.reserves();
            prices.put(symbol, reserves.cashWon() / reserves.shares());
            pools.put(symbol, reserves);
        });
        for (var entry : lobby.clients.entrySet()) {
            var seat = lobby.patrol.seats.get(entry.getKey());
            if (seat.view == Patrol.View.MEETING) {
                send(entry.getValue(), Map.of("type", "meeting", "newsNumber", latest.number(),
                        "reason", seat.meetingReason));
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
                    Map.entry("view", seat.view), Map.entry("beat", lobby.patrol.beat),
                    Map.entry("walking", lobby.patrol.walking),
                    Map.entry("step", lobby.patrol.step(seat)),
                    Map.entry("coughBeat", seat.coughBeat),
                    Map.entry("lunchStartsInSeconds", (lobby.lunchStartTick - lobby.tick) * Market.TICK_SECONDS),
                    Map.entry("lunchEndsInSeconds", (lobby.lunchEndTick - lobby.tick) * Market.TICK_SECONDS),
                    Map.entry("remainingSeconds",
                            (lobby.sessionTicks - lobby.tick) * Market.TICK_SECONDS)));
        }
    }

    private void create(WebSocketSession session) throws IOException {
        var host = UUID.randomUUID();
        while (true) {
            var code = Integer.toString(100_000 + random.nextInt(900_000));
            var lobby = new Lobby(code, host, newMarket(sessionSeconds), sessionSeconds);
            lobby.lock.lock();
            try {
                if (rooms.putIfAbsent(code, lobby) != null) continue;
                attach(session, lobby, host);
                broadcast(lobby);
                return;
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
            var player = UUID.randomUUID();
            lobby.game.join(player);
            attach(session, lobby, player);
            broadcast(lobby);
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
            if (!lobby.started()) return;
            send(session, Map.of("type", "catalog", "stocks", catalogMessage, "loan", Map.of("interestPercent", loanInterestPercent, "maxWon", loanMaxWon)));
            sendDesk(session, lobby.desks.get(player));
            sendMyTrades(session, lobby, player);
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

    private Market newMarket(long seconds) {
        return new Market(catalog, news, new SplittableRandom(), seconds);
    }

    private void attach(WebSocketSession session, Lobby lobby, UUID player) {
        lobby.clients.put(player, session);
        var bytes = new byte[18];
        random.nextBytes(bytes);
        lobby.tokens.put(player, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        session.getAttributes().put(MEMBERSHIP, new Membership(lobby, player));
    }

    private void broadcast(Lobby lobby) throws IOException {
        var state = lobby.game.snapshot();
        var offline = List.copyOf(lobby.offlineSince.keySet());
        for (var entry : lobby.clients.entrySet()) {
            send(entry.getValue(), Map.of("type", "state", "code", lobby.code,
                    "you", entry.getKey(), "token", lobby.tokens.get(entry.getKey()),
                    "room", state, "offline", offline, "round", lobby.round,
                    "sessionSeconds", lobby.sessionSeconds));
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
            long now = System.nanoTime();
            lobby.offlineSince.put(member.player(), now);
            var seat = lobby.patrol.seats.get(member.player());
            if (seat != null) {
                seat.present = false;
                if (seat.view == Patrol.View.MARKET) seat.view = Patrol.View.WORK;
            }
            var desk = lobby.desks.get(member.player());
            if (desk != null) desk.observeView(false, now);
            if (rooms.get(lobby.code) == lobby) broadcast(lobby);
        } finally {
            lobby.lock.unlock();
        }
    }

    /** 스스로 나가거나 대기실에서 오래 돌아오지 않은 경우. 방장이 나가면 방이 닫힌다. */
    private void leave(Lobby lobby, UUID player) throws IOException {
        lobby.clients.remove(player);
        lobby.tokens.remove(player);
        lobby.offlineSince.remove(player);
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
