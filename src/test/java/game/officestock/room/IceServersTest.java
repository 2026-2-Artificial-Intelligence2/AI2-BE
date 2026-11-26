package game.officestock.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 음성 STUN·TURN 설정(VOICE_ICE_SERVERS) 검사. 잘못 넣으면 서버가 뜨지 않고 무엇이 틀렸는지 알려 준다. */
class IceServersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void STUN과_TURN_목록을_그대로_받는다() {
        var servers = LobbySocket.parseIceServers(json, """
                [{"urls":"stun:stun.l.google.com:19302"},
                 {"urls":["turn:relay.example.com:3478","turns:relay.example.com:443?transport=tcp"],
                  "username":"u","credential":"p"}]""");
        assertThat(servers.size()).isEqualTo(2);
        assertThat(servers.get(1).path("username").asText()).isEqualTo("u");
    }

    @Test
    void TURN에는_아이디와_비밀번호가_있어야_하고_형식이_틀리면_거절한다() {
        assertThatThrownBy(() -> LobbySocket.parseIceServers(json, "[{\"urls\":\"turn:relay.example.com\"}]"))
                .hasMessageContaining("username");
        assertThatThrownBy(() -> LobbySocket.parseIceServers(json, "{}")).hasMessageContaining("배열");
        assertThatThrownBy(() -> LobbySocket.parseIceServers(json, "[{\"x\":1}]")).hasMessageContaining("urls");
        assertThatThrownBy(() -> LobbySocket.parseIceServers(json, "not json")).hasMessageContaining("JSON");
    }
}
