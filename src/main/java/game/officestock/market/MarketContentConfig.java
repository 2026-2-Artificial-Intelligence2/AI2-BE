package game.officestock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * 시장 콘텐츠(종목·뉴스)를 서버 시작 때 읽는다.
 * 기본은 classpath:market/stocks.json, classpath:market/news.json이고,
 * 다른 파일로 실험할 때는 game.stock-catalog / game.news-catalog=file:경로 로 바꾼다.
 */
@Configuration
public class MarketContentConfig {
    private static final Logger log = LoggerFactory.getLogger(MarketContentConfig.class);

    @Bean
    public StockCatalog stockCatalog(ObjectMapper json,
            @Value("${game.stock-catalog}") Resource file) throws IOException {
        try (var in = file.getInputStream()) {
            return json.readValue(in, StockCatalog.class);
        }
    }

    @Bean
    public NewsCatalog newsCatalog(ObjectMapper json, StockCatalog stocks,
            @Value("${game.news-catalog}") Resource file) throws IOException {
        NewsCatalog news;
        try (var in = file.getInputStream()) {
            news = json.readValue(in, NewsCatalog.class).checkAgainst(stocks);
        }
        log.info("뉴스 덱 {}장, 찌라시 문구 호재 {} · 악재 {}", news.news().size(),
                news.rumors().good().size(), news.rumors().bad().size());
        news.summary(stocks).forEach(line -> log.info("  {}", line));
        return news;
    }
}
