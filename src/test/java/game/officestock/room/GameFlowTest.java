package game.officestock.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import game.officestock.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 서버를 띄우고 WebSocket으로 한 판을 끝까지 해 본다: 회원 가입·비회원 → 방 만들기·입장 → 시작 → 매매 →
 * 퇴근 정산 → 회원 결과만 저장. 근무 시간은 8초로 줄인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"game.session-seconds=8", "game.playtest-log=build/test-playtest.csv"})
@Import(TestDatabase.class)
class GameFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private final List<Client> clients = new ArrayList<>();

    /** 받은 메시지를 모두 쌓아 두고, 조건에 맞는 메시지를 기다리는 테스트용 접속. */
    final class Client extends TextWebSocketHandler {
        final List<JsonNode> received = new CopyOnWriteArrayList<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            received.add(JSON.readTree(message.getPayload()));
        }

        void send(String command) throws Exception {
            session.sendMessage(new TextMessage(command));
        }

        /** 보낸 뒤 새로 온 메시지 가운데 type이 맞고 조건을 만족하는 첫 메시지. */
        JsonNode request(String command, String type) throws Exception {
            int from = received.size();
            send(command);
            return await(type, m -> true, from);
        }

        JsonNode await(String type, Predicate<JsonNode> condition, int from) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (int i = from; i < received.size(); i++) {
                    var m = received.get(i);
                    if (m.path("type").asText().equals(type) && condition.test(m)) return m;
                    if (m.path("type").asText().equals("error") && !type.equals("error"))
                        throw new AssertionError("서버 오류: " + m.path("message").asText());
                }
                Thread.sleep(20);
            }
            throw new AssertionError(type + " 메시지가 오지 않았습니다. 받은 것: " + received.stream()
                    .map(m -> m.path("type").asText()).toList());
        }
    }

    private Client connect() throws Exception {
        var client = new Client();
        // 시작할 때 오는 차트 메시지가 수백 KB라 받는 버퍼를 넉넉히 둔다(기본 8KB면 클라이언트가 연결을 끊는다).
        var container = jakarta.websocket.ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(4 * 1024 * 1024);
        client.session = new StandardWebSocketClient(container)
                .execute(client, "ws://localhost:" + port + "/ws/lobby").get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    @AfterEach
    void close() throws Exception {
        for (var c : clients) if (c.session.isOpen()) c.session.close();
    }

    @Test
    void 텍스트_마이크_오류와_ID가_붙은_요청_응답을_구분한다() throws Exception {
        var player = connect();
        assertThat(player.await("hello", m -> true, 0).path("requestIds").asBoolean()).isTrue();

        // 기존 클라이언트와 동일한 텍스트 명령.
        player.request("CREATE", "state");

        int from = player.received.size();
        player.send("MIC 101");
        player.send("""
                {"requestId":"duration-1","command":"LENGTH 5"}
                """);
        var micError = player.await("error", m -> true, from);
        assertThat(micError.path("command").asText()).isEqualTo("MIC");
        assertThat(micError.has("requestId")).isFalse();
        from = player.received.indexOf(micError) + 1;
        player.await("state", m -> m.path("sessionSeconds").asInt() == 300, from);
        player.await("ack", m -> m.path("requestId").asText().equals("duration-1"), from);

        var invalidDuration = player.request("""
                {"requestId":"duration-2","command":"LENGTH 99"}
                """, "error");
        assertThat(invalidDuration.path("command").asText()).isEqualTo("LENGTH");
        assertThat(invalidDuration.path("requestId").asText()).isEqualTo("duration-2");

        var voiceError = player.request("VOICE SIGNAL P2 {}", "error");
        assertThat(voiceError.path("command").asText()).isEqualTo("VOICE SIGNAL");
        assertThat(voiceError.has("requestId")).isFalse();
        assertThat(player.received).noneMatch(m -> m.path("type").asText().equals("ack")
                && m.path("requestId").asText().equals("duration-2"));
    }

    @Test
    void 회원과_비회원이_한_판을_끝까지_하고_회원_결과만_저장된다() throws Exception {
        var tag = Long.toString(System.nanoTime() % 100_000);
        var member = connect();
        var guest = connect();

        var profile = member.request("SIGNUP 테스터" + tag + " secret1", "profile");
        var token = profile.path("token").asText();
        assertThat(token).isNotBlank();
        var guestMessage = guest.request("GUEST 손님" + tag, "guest");
        assertThat(guestMessage.path("notice").asText()).contains("이 기기");

        var state = member.request("CREATE", "state");
        var code = state.path("code").asText();
        int before = member.received.size();
        guest.send("JOIN " + code);
        state = member.await("state", m -> m.path("members").size() == 2, before);
        assertThat(state.path("members").findValuesAsText("label")).containsExactly("테스터" + tag, "손님" + tag);
        assertThat(state.path("members").get(0).path("member").asBoolean()).isTrue();
        assertThat(state.path("members").get(1).path("member").asBoolean()).isFalse();

        before = member.received.size();
        member.send("START");
        var catalog = member.await("catalog", m -> true, before);
        member.await("market", m -> true, before);
        var symbol = catalog.path("stocks").get(0).path("id").asText();
        assertThat(catalog.path("stocks").get(0).path("sector").asText()).isNotBlank();

        member.request("SWITCH", "view");
        var fill = member.request("BUY " + symbol + " 3", "fill");
        assertThat(fill.toString()).contains(symbol);

        var result = member.await("result", m -> true, 0);
        var guestResult = guest.await("result", m -> true, 0);
        // 함께 하기 결과에는 명예의 전당 올리기가 없다(혼자 하기만).
        assertThat(result.has("share")).isFalse();
        assertThat(guestResult.has("share")).isFalse();
        assertThat(guest.request("SHARE abcdefghijklmnop0123 손님", "error").path("message").asText()).contains("혼자 하기");
        assertThat(result.path("standings").size()).isEqualTo(2);
        assertThat(result.path("awards").isArray()).isTrue();

        Integer saved = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_result r JOIN profile p ON p.id = r.profile_id
                WHERE p.name = ? AND r.mode = 'multi' AND r.player_count = 2 AND r.session_seconds = 8
                """, Integer.class, "테스터" + tag);
        assertThat(saved).isEqualTo(1);

        // 다른 접속에서 열쇠로 자동 로그인
        var again = connect();
        assertThat(again.request("AUTOLOGIN " + token, "profile").path("name").asText()).isEqualTo("테스터" + tag);
    }

    @Test
    void 솔로는_바로_시작하고_메신저와_입장이_막힌다() throws Exception {
        var tag = Long.toString(System.nanoTime() % 100_000);
        var player = connect();
        player.request("SIGNUP 솔로" + tag + " secret1", "profile");
        var state = player.request("SOLO 5", "state");
        var started = state.path("room").path("startedAt").isNull()
                ? player.await("state", m -> !m.path("room").path("startedAt").isNull(), 0) : state;
        assertThat(started.path("solo").asBoolean()).isTrue();
        assertThat(started.path("sessionSeconds").asLong()).isEqualTo(300);

        assertThat(player.request("CHAT ALL 안녕", "error").path("message").asText()).contains("메신저");
        var other = connect();
        assertThat(other.request("JOIN " + started.path("code").asText(), "error").path("message").asText())
                .contains("시작한");
        assertThat(player.request("SOLO 7", "error").path("message").asText()).contains("이미 방");
    }

    @Test
    void 음성_대화는_참여한_사람끼리만_연결_정보를_전달하고_끊기면_빠진다() throws Exception {
        var tag = Long.toString(System.nanoTime() % 100_000);
        var a = connect();
        var b = connect();
        var c = connect();
        a.request("GUEST 가" + tag, "guest");
        b.request("GUEST 나" + tag, "guest");
        c.request("GUEST 다" + tag, "guest");
        var code = a.request("CREATE", "state").path("code").asText();
        b.request("JOIN " + code, "state");
        c.request("JOIN " + code, "state");

        var config = a.request("VOICE JOIN", "voiceConfig");
        assertThat(config.path("iceServers").get(0).path("urls").asText()).startsWith("stun:");
        int from = a.received.size();
        b.send("VOICE JOIN");
        var state = a.await("state", m -> m.path("members").findValuesAsText("voice").stream()
                .filter(Boolean::parseBoolean).count() == 2, from);
        assertThat(state.path("members").get(2).path("voice").asBoolean()).isFalse();

        // 가 → 나: 그대로 전달된다
        from = b.received.size();
        a.send("VOICE SIGNAL 나" + tag + " {\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0\"}}");
        var relayed = b.await("voiceSignal", m -> true, from);
        assertThat(relayed.path("from").asText()).isEqualTo("가" + tag);
        assertThat(relayed.path("data").path("sdp").path("type").asText()).isEqualTo("offer");

        // 참여하지 않은 다에게는 못 보내고, 다는 보낼 수도 없다
        assertThat(a.request("VOICE SIGNAL 다" + tag + " {}", "error").path("message").asText()).contains("참여한 사람");
        assertThat(c.request("VOICE SIGNAL 가" + tag + " {}", "error").path("message").asText()).contains("먼저 음성");
        assertThat(a.request("VOICE SIGNAL 나" + tag + " [1]", "error").path("message").asText()).contains("JSON 객체");

        // 음소거가 모두에게 보인다
        from = c.received.size();
        b.send("VOICE MUTE");
        c.await("state", m -> m.path("members").get(1).path("muted").asBoolean(), from);

        // 나가 연결을 끊으면 음성에서 빠진다
        from = a.received.size();
        b.session.close();
        a.await("state", m -> !m.path("members").get(1).path("voice").asBoolean(), from);
    }

    @Test
    void 솔로_방에는_음성_대화가_없다() throws Exception {
        var player = connect();
        player.request("GUEST 혼자" + System.nanoTime() % 100_000, "guest");
        player.request("SOLO 5", "state");
        assertThat(player.request("VOICE JOIN", "error").path("message").asText()).contains("음성 대화가 없습니다");
    }
}
