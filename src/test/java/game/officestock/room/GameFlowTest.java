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
    void 회원과_비회원이_한_판을_끝까지_하고_회원_결과만_저장된다() throws Exception {
        var tag = Long.toString(System.nanoTime() % 100_000);
        var member = connect();
        var guest = connect();

        var profile = member.request("SIGNUP 테스터" + tag + " secret1", "profile");
        var token = profile.path("token").asText();
        assertThat(token).isNotBlank();
        var guestMessage = guest.request("GUEST 손님" + tag, "guest");
        assertThat(guestMessage.path("notice").asText()).contains("저장되지 않");

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
        assertThat(result.path("recorded").asBoolean()).isTrue();
        var guestResult = guest.await("result", m -> true, 0);
        assertThat(guestResult.path("recorded").asBoolean()).isFalse();
        assertThat(result.path("standings").size()).isEqualTo(2);

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
}
