package game.officestock.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import game.officestock.market.Market;
import game.officestock.market.NewsCatalog;
import game.officestock.market.StockCatalog;
import game.officestock.market.StockPool;
import game.officestock.player.WorkDesk;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, Lobby> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json;

    private final long startingCashWon;
    private final long workRewardWon;
    private final long sessionSeconds;
    private final PlaytestLog playtest;
    private final StockCatalog catalog;
    private final NewsCatalog news;
    /** 화면이 종목 코드를 이름으로 바꿀 수 있게 게임 시작 때 보내는 목록. */
    private final List<Map<String, String>> catalogMessage;

    public LobbySocket(ObjectMapper json, PlaytestLog playtest, StockCatalog catalog, NewsCatalog news,
            @Value("${game.starting-cash-won}") long startingCashWon,
            @Value("${game.work-reward-won}") long workRewardWon,
            @Value("${game.session-seconds}") long sessionSeconds) {
        if (startingCashWon < 0 || workRewardWon <= 0 || sessionSeconds <= 0) {
            throw new IllegalArgumentException("시작 자금, 업무 수당, 근무 시간 설정을 확인하세요.");
        }
        this.sessionSeconds = sessionSeconds;
        this.startingCashWon = startingCashWon;
        this.workRewardWon = workRewardWon;
        this.json = json;
        this.playtest = playtest;
        this.catalog = catalog;
        this.news = news;
        this.catalogMessage = catalog.stocks().stream()
                .map(s -> Map.of("id", s.id(), "name", s.name())).toList();
    }

    private static final class Lobby {
        final String code;
        final GameRoom game;
        final ReentrantLock lock = new ReentrantLock(true);
        final Map<UUID, WebSocketSession> clients = new HashMap<>();

        final Map<UUID, WorkDesk> desks = new HashMap<>();
        /** 기록용 이름(P1, P2…). 입장 순서를 따른다. */
        final Map<UUID, String> labels = new HashMap<>();
        final Market market;
        final Patrol patrol = new Patrol();
        final long sessionTicks;
        final List<Settlement.TradeRecord> trades = new ArrayList<>();
        long tick;
        boolean finished;
        Thread marketClock;

        Lobby(String code, UUID host, StockCatalog catalog, NewsCatalog news, long sessionSeconds) {
            this.code = code;
            this.game = new GameRoom(host);
            this.market = new Market(catalog, news, new SplittableRandom(), sessionSeconds);
            this.sessionTicks = sessionSeconds / Market.TICK_SECONDS;
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
                    lobby.game.start(member.player(), Instant.now());
                    lobby.patrol.start(lobby.game.snapshot().players());
                    broadcast(lobby);
                    var players = lobby.game.snapshot().players();
                    long now = System.nanoTime();
                    for (var entry : lobby.clients.entrySet()) {
                        lobby.labels.putIfAbsent(entry.getKey(), "P" + (players.indexOf(entry.getKey()) + 1));
                        var desk = lobby.desks.computeIfAbsent(entry.getKey(),
                                id -> new WorkDesk(startingCashWon, workRewardWon, now));
                        send(entry.getValue(), Map.of("type", "catalog", "stocks", catalogMessage));
                        sendDesk(entry.getValue(), desk);
                    }
                    broadcastMarket(lobby);
                    if (lobby.marketClock == null) {
                        lobby.marketClock = Thread.startVirtualThread(() -> runMarket(lobby));
                    }
                } finally {
                    lobby.lock.unlock();
                }
            } else if (command.equals("CREATE")) {
                create(session);
            } else if (command.startsWith("JOIN ")) {
                join(session, command.substring(5).strip().toUpperCase(Locale.ROOT));
            } else {
                throw new IllegalStateException("방을 만들거나 초대 코드를 입력하세요.");
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            send(session, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    private void submitWork(WebSocketSession session, Membership member, String command)
            throws IOException {
        var desk = member.lobby().desks.get(member.player());
        if (desk == null) throw new IllegalStateException("게임 시작 후 업무를 제출하세요.");
        if (member.lobby().patrol.seats.get(member.player()).view != Patrol.View.WORK) throw new IllegalStateException("업무 창에서 제출하세요.");
        var fields = command.split("\\s+");
        if (fields.length != 3) throw new IllegalArgumentException("업무 번호와 답을 입력하세요.");
        WorkDesk.TaskTiming timing;
        try {
            timing = desk.submit(Long.parseLong(fields[1]), Integer.parseInt(fields[2]), System.nanoTime());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("업무 번호와 답은 정수여야 합니다.");
        }
        if (timing != null) {
            playtest.task(member.lobby().code, member.lobby().labels.get(member.player()), timing);
        }
        sendDesk(session, desk);
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
        broadcastMarket(lobby);
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
            lobby.patrol.advance(lobby.tick / TICKS_PER_PATROL_BEAT);
            // 훈계 시작·종료는 순찰 박자에 일어나므로 이때 업무 시간 기록에 화면 상태를 알린다.
            long now = System.nanoTime();
            lobby.desks.forEach((id, desk) -> {
                var seat = lobby.patrol.seats.get(id);
                if (seat != null) desk.observeView(seat.view == Patrol.View.WORK, now);
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
        var standings = Settlement.standings(desksByPlayer, lobby.market);
        log.info("방 {} 퇴근 정산: {}", lobby.code, standings);
        desksByPlayer.forEach((label, desk) -> playtest.summary(lobby.code, label, desk.timings()));
        for (var entry : lobby.clients.entrySet()) {
            send(entry.getValue(), Map.of("type", "result", "you", lobby.labels.get(entry.getKey()),
                    "standings", standings, "trades", lobby.trades,
                    "news", lobby.market.history()));
        }
    }

    private void broadcastMarket(Lobby lobby) throws IOException {
        var history = lobby.market.history();
        var latest = history.isEmpty()
                ? new Market.NewsItem(0, "news", "", "", null) : history.getLast();
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
                send(entry.getValue(), Map.of("type", "meeting", "newsNumber", latest.number()));
                continue;
            }
            var wallet = lobby.desks.get(entry.getKey()).wallet();
            send(entry.getValue(), Map.ofEntries(Map.entry("type", "market"),
                    Map.entry("prices", prices), Map.entry("pools", pools),
                    Map.entry("holdings", wallet.holdings()), Map.entry("headline", latest.headline()),
                    Map.entry("newsKind", latest.kind()), Map.entry("newsNumber", latest.number()),
                    Map.entry("view", seat.view), Map.entry("beat", lobby.patrol.beat),
                    Map.entry("walking", lobby.patrol.walking),
                    Map.entry("step", lobby.patrol.step(seat)),
                    Map.entry("remainingSeconds",
                            (lobby.sessionTicks - lobby.tick) * Market.TICK_SECONDS)));
        }
    }

    private void create(WebSocketSession session) throws IOException {
        var host = UUID.randomUUID();
        while (true) {
            var code = Integer.toString(100_000 + random.nextInt(900_000));
            var lobby = new Lobby(code, host, catalog, news, sessionSeconds);
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

    private void attach(WebSocketSession session, Lobby lobby, UUID player) {
        lobby.clients.put(player, session);
        session.getAttributes().put(MEMBERSHIP, new Membership(lobby, player));
    }

    private void broadcast(Lobby lobby) throws IOException {
        var state = lobby.game.snapshot();
        for (var entry : lobby.clients.entrySet()) {
            send(entry.getValue(), Map.of("type", "state", "code", lobby.code,
                    "you", entry.getKey(), "room", state));
        }
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        var message = new TextMessage(json.writeValueAsString(payload));
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(message);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
            throws IOException {
        var member = (Membership) session.getAttributes().remove(MEMBERSHIP);
        if (member == null) return;
        var lobby = member.lobby();
        lobby.lock.lock();
        try {
            lobby.clients.remove(member.player());
            var leftDesk = lobby.desks.remove(member.player());
            if (leftDesk != null && !lobby.finished) {
                playtest.summary(lobby.code, lobby.labels.get(member.player()), leftDesk.timings());
            }
            lobby.patrol.seats.remove(member.player());
            if (rooms.get(lobby.code) != lobby) return;
            if (member.player().equals(lobby.game.snapshot().hostId())) {
                rooms.remove(lobby.code, lobby);
                if (lobby.marketClock != null) lobby.marketClock.interrupt();
                if (!lobby.finished) {
                    lobby.desks.forEach((id, desk) ->
                            playtest.summary(lobby.code, lobby.labels.get(id), desk.timings()));
                }
                for (var client : lobby.clients.values()) {
                    client.getAttributes().remove(MEMBERSHIP);
                    send(client, Map.of("type", "closed", "message", "방장이 나가 방이 종료됐습니다."));
                }
            } else {
                lobby.game.leave(member.player());
                broadcast(lobby);
            }
        } finally {
            lobby.lock.unlock();
        }
    }
}
