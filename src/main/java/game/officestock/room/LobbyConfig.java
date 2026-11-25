package game.officestock.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class LobbyConfig implements WebSocketConfigurer {
    private final LobbySocket socket;

    public LobbyConfig(LobbySocket socket) {
        this.socket = socket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/lobby");
    }
}
