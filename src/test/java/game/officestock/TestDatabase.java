package game.officestock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/** 테스트마다 Docker로 빈 PostgreSQL을 띄운다(개발용 DB는 건드리지 않는다). Docker가 켜져 있어야 한다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
