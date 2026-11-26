package game.officestock.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import game.officestock.market.Market;
import game.officestock.market.NewsCatalog;
import game.officestock.market.PriceChart;
import game.officestock.market.StockCatalog;
import game.officestock.market.StockPool;
import game.officestock.player.PlayerWallet;
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
    /** 지정가 주문 수는 규칙상 제한하지 않는다. 실수로 쏟아지는 것만 막는 안전장치다. */
    private static final int MAX_OPEN_ORDERS = 100;
    /** 메신저: 메시지 길이, 연속 전송 간격, 방에 남기는 기록 수, 익명 찌라시 길이. */
    private static final int MAX_CHAT_LENGTH = 200;
    private static final long CHAT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(400);
    private static final int MAX_CHAT_HISTORY = 300;
    private static final int MAX_LEAK_LENGTH = 60;
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
            @Value("${game.loan-max-won}") long loanMaxWon,
            @Value("${game.trade-fee-bps}") int tradeFeeBps) {
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
        /**
         * 화면과 기록에 쓰는 이름(P1, P2…). 입장할 때 정하고 다시 하기를 해도 바꾸지 않는다.
         * 누가 나가도 다른 사람의 이름은 그대로다.
         */
        final Map<UUID, String> labels = new HashMap<>();
        int nextLabel = 1;
        /** 사내 메신저 기록. 방이 유지되는 동안 판이 바뀌어도 남는다(최근 MAX_CHAT_HISTORY개). */
        final List<ChatMessage> chats = new ArrayList<>();
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
            leaks.clear();
            orders.clear();
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

    /** @param to 받는 사람 이름, 단체방이면 null */
    private record ChatMessage(int id, long second, String from, String to, String text) {}

    private record Leak(String player, int newsNumber, String text) {}

    /** 걸어 둔 지정가 주문. reservedWon은 매수 주문에 묶은 현금(가격×수량). */
    private record PendingOrder(int id, UUID player, String symbol, boolean buy, long limitWon, long quantity,
                                long reservedWon, long placedSecond) {}

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
                    if (command.startsWith("CHAT ")) {
                        chat(lobby, member.player(), command.substring(5));
                        return;
                    }
                    if (lobby.finished) {
                        throw new IllegalStateException("퇴근했습니다. 결과를 확인하세요.");
                    }
                    if (command.startsWith("LEAK ")) {
                        leak(lobby, member.player(), command.substring(5).strip());
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
            lobby.desks.computeIfAbsent(player, id -> new WorkDesk(startingCashWon, workRewardWon, tradeFeeBps, now));
            if (!lobby.clients.containsKey(player)) {
                lobby.desks.get(player).observeView(false, now);
                lobby.patrol.seats.get(player).present = false;
            }
        }
        broadcast(lobby);
        for (var entry : lobby.clients.entrySet()) {
            sendChart(entry.getValue(), lobby);
            send(entry.getValue(), Map.of("type", "catalog", "stocks", catalogMessage, "loan", Map.of("interestPercent", loanInterestPercent, "maxWon", loanMaxWon),
                    "feeBps", tradeFeeBps));
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
        var message = new ChatMessage(lobby.chats.isEmpty() ? 1 : lobby.chats.getLast().id() + 1,
                playing ? lobby.tick * Market.TICK_SECONDS : -1, from, recipient == null ? null : target, text);
        lobby.chats.add(message);
        if (lobby.chats.size() > MAX_CHAT_HISTORY) lobby.chats.removeFirst();
        for (var entry : lobby.clients.entrySet()) {
            if (recipient == null || entry.getKey().equals(player) || entry.getKey().equals(recipient)) {
                send(entry.getValue(), Map.of("type", "chat", "message", message));
            }
        }
    }

    /** 내가 볼 수 있는 메신저 기록(단체방과 내가 주고받은 귓속말). 입장·재접속 때 보낸다. */
    private void sendChatHistory(WebSocketSession session, Lobby lobby, UUID player) throws IOException {
        var me = lobby.labels.get(player);
        send(session, Map.of("type", "chatHistory", "messages", lobby.chats.stream()
                .filter(m -> m.to() == null || m.to().equals(me) || m.from().equals(me)).toList()));
    }

    /** 익명 찌라시: 한 판에 한 번, 내가 쓴 문장을 진짜 찌라시처럼 모두의 화면에 뿌린다. 가격은 움직이지 않는다. */
    private void leak(Lobby lobby, UUID player, String text) throws IOException {
        if (!lobby.started()) throw new IllegalStateException("게임 시작 후 뿌릴 수 있습니다.");
        if (lobby.patrol.seats.get(player).view != Patrol.View.MESSENGER)
            throw new IllegalStateException("메신저 창에서 뿌리세요.");
        var label = lobby.labels.get(player);
        if (lobby.leaks.stream().anyMatch(l -> l.player().equals(label)))
            throw new IllegalStateException("익명 찌라시는 한 판에 한 번만 뿌릴 수 있습니다.");
        var item = lobby.market.addPlayerRumor(cleanText(text, MAX_LEAK_LENGTH));
        lobby.leaks.add(new Leak(label, item.number(), item.headline()));
        log.info("방 {} {} 익명 찌라시: {}", lobby.logName(), label, item.headline());
        send(lobby.clients.get(player), Map.of("type", "leakSent", "message", "익명 찌라시를 뿌렸습니다. 누가 뿌렸는지는 퇴근 뒤 공개됩니다."));
        broadcastMarket(lobby);
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
        if (fields.length != 3) throw new IllegalArgumentException("종목과 수량을 확인하세요.");
        var symbol = fields[1];
        var pool = lobby.market.pool(symbol);
        if (pool == null) throw new IllegalArgumentException("등록되지 않은 종목입니다.");
        long number = parsePositive(fields[2], "수량과 금액은 1 이상의 정수여야 합니다.");
        boolean buying = fields[0].startsWith("BUY");
        long quantity = number;
        if (fields[0].endsWith("WON")) {
            var wallet = desk.wallet();
            if (buying) {
                long budget = Math.min(number, wallet.balance().cashWon());
                // 거래 비용까지 그 금액 안에 들어오게 한다.
                quantity = pool.maxBuyable(budget * 10_000 / (10_000 + tradeFeeBps));
                while (quantity > 0 && pool.quoteBuy(quantity) + wallet.feeFor(pool.quoteBuy(quantity)) > budget) quantity--;
                if (quantity <= 0) throw new IllegalArgumentException("그 금액으로는 1주도 살 수 없습니다.");
            } else {
                long sellable = wallet.sellable(symbol);
                if (sellable <= 0) throw new IllegalArgumentException("팔 수 있는 주식이 없습니다.");
                quantity = pool.minSellFor(number, sellable);
            }
        }
        execute(lobby, member.player(), symbol, buying, quantity, false);
        broadcastMarket(lobby);
    }

    /** 체결 하나를 처리하고 본인에게 알린다. 화면 확인은 부르는 쪽이 한다(지정가는 안 보고 있어도 체결된다). */
    private long execute(Lobby lobby, UUID player, String symbol, boolean buying, long quantity, boolean limit)
            throws IOException {
        var desk = lobby.desks.get(player);
        var pool = lobby.market.pool(symbol);
        PlayerWallet.Fill fill;
        long spotBefore = lobby.market.spotPrice(symbol);
        try {
            fill = buying ? desk.wallet().buy(symbol, pool, quantity)
                          : desk.wallet().sell(symbol, pool, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("처리할 수 있는 주문 범위를 초과했습니다.");
        }
        long amount = fill.netWon(buying);
        lobby.market.recordTrade(symbol, spotBefore, quantity);
        String side = (limit ? "지정가 " : "") + (buying ? "매수" : "매도");
        lobby.trades.add(new Settlement.TradeRecord(lobby.tick * Market.TICK_SECONDS,
                lobby.labels.get(player), symbol, side, quantity, amount));
        var client = lobby.clients.get(player);
        if (client != null) {
            sendDesk(client, desk);
            send(client, Map.of("type", "fill", "symbol", symbol, "quantity", quantity,
                    "side", side, "amount", amount, "feeWon", fill.feeWon(), "limit", limit));
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
                        processOrders(lobby);
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
        // 체결되지 않은 지정가 주문은 퇴근과 함께 취소하고 묶인 현금·주식을 돌려준 뒤 정산한다.
        cancelAll(lobby, null);
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
                "news", lobby.market.history(), "leaks", lobby.leaks));
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
        // 동료들의 업무 게이지(의심). 본인 것은 보내지 않는다: 내 게이지는 남에게만 보인다.
        var gauges = new LinkedHashMap<UUID, Map<String, Object>>();
        lobby.patrol.seats.forEach((id, s) -> gauges.put(id, Map.of("label", lobby.labels.get(id),
                "suspicion", (int) Math.round(s.suspicion), "meeting", s.view == Patrol.View.MEETING)));
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
                    Map.entry("newsSecond", latest.second()), Map.entry("newsSymbols", latest.symbols()),
                    Map.entry("books", books), Map.entry("candles", candles),
                    Map.entry("view", seat.view), Map.entry("beat", lobby.patrol.beat),
                    Map.entry("walking", lobby.patrol.walking),
                    Map.entry("step", lobby.patrol.step(seat)),
                    Map.entry("coughBeat", seat.coughBeat),
                    Map.entry("colleagues", gauges.entrySet().stream()
                            .filter(e -> !e.getKey().equals(entry.getKey())).map(Map.Entry::getValue).toList()),
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
                sendChatHistory(session, lobby, host);
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
            send(session, Map.of("type", "catalog", "stocks", catalogMessage, "loan", Map.of("interestPercent", loanInterestPercent, "maxWon", loanMaxWon),
                    "feeBps", tradeFeeBps));
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

    private Market newMarket(long seconds) {
        return new Market(catalog, news, new SplittableRandom(), seconds);
    }

    private void attach(WebSocketSession session, Lobby lobby, UUID player) {
        lobby.clients.put(player, session);
        lobby.labels.put(player, "P" + lobby.nextLabel++);
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
                "online", lobby.clients.containsKey(id))).toList();
        for (var entry : lobby.clients.entrySet()) {
            send(entry.getValue(), Map.of("type", "state", "code", lobby.code,
                    "you", entry.getKey(), "token", lobby.tokens.get(entry.getKey()),
                    "room", state, "offline", offline, "round", lobby.round,
                    "sessionSeconds", lobby.sessionSeconds, "members", members,
                    "youLabel", lobby.labels.get(entry.getKey())));
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
                if (seat.view.slacking()) seat.view = Patrol.View.WORK;
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
