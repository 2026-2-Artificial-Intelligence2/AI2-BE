package game.officestock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class StockCatalogConfig {
    /** 기본은 classpath:market/stocks.json. 다른 목록으로 실험할 때는 game.stock-catalog=file:경로 로 바꾼다. */
    @Bean
    public StockCatalog stockCatalog(ObjectMapper json,
            @Value("${game.stock-catalog}") Resource file) throws IOException {
        try (var in = file.getInputStream()) {
            return json.readValue(in, StockCatalog.class);
        }
    }
}
