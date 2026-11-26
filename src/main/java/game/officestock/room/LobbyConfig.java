package game.officestock.room;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class LobbyConfig implements WebSocketConfigurer {
    private final LobbySocket socket;
    private final String[] allowedOrigins;

    /**
     * @param allowedOrigins WebSocket 연결을 받아 줄 화면 주소(쉼표로 구분, * 패턴 가능).
     *                       따로 만든 프론트엔드의 개발 서버나 배포 주소를 여기에 넣는다.
     */
    public LobbyConfig(LobbySocket socket, @Value("${game.allowed-origins}") String[] allowedOrigins) {
        this.socket = socket;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/lobby").setAllowedOriginPatterns(allowedOrigins);
    }
}
