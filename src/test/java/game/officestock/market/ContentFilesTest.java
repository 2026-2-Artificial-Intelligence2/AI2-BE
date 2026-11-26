package game.officestock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 stocks.json·news.json 검사. 뉴스나 종목을 고친 뒤 균형이 무너지거나 규칙을 어기면 여기서 걸린다.
 * (서버도 시작할 때 형식을 검사하지만, 균형은 로그로만 보여 주므로 여기서 기준을 고정한다.)
 */
class ContentFilesTest {
    private static StockCatalog stocks;
    private static NewsCatalog news;

    @BeforeAll
    static void load() throws IOException {
        var json = new ObjectMapper();
        try (var s = ContentFilesTest.class.getResourceAsStream("/market/stocks.json");
             var n = ContentFilesTest.class.getResourceAsStream("/market/news.json")) {
            stocks = json.readValue(s, StockCatalog.class);
            news = json.readValue(n, NewsCatalog.class).checkAgainst(stocks);
        }
    }

    @Test
    void 일반_종목마다_업종과_뉴스가_충분하다() {
        for (var stock : stocks.stocks()) {
            if (stock.derived()) continue;
            assertThat(stock.sector()).as(stock.id()).isNotBlank();
            assertThat(news.cardsFor(stock.id())).as(stock.id() + " 뉴스 수").hasSizeGreaterThanOrEqualTo(6);
        }
    }

    @Test
    void 종목별_뉴스_평균_변화율이_한쪽으로_쏠리지_않는다() {
        var sum = new HashMap<String, Integer>();
        var count = new HashMap<String, Integer>();
        for (var card : news.news()) card.moves().forEach((id, change) -> {
            sum.merge(id, change, Integer::sum);
            count.merge(id, 1, Integer::sum);
        });
        sum.forEach((id, total) -> assertThat((double) total / count.get(id)).as(id + " 평균 변화율")
                .isBetween(-1.0, 1.0));
    }

    @Test
    void 찌라시_문구가_충분하다() {
        assertThat(news.rumors().good()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(news.rumors().bad()).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void 판마다_뽑은_종목에_맞춘_덱이_20분_판_속보보다_넉넉하다() {
        for (int seed = 0; seed < 30; seed++) {
            var listed = stocks.pick(12, new SplittableRandom(seed));
            var deck = news.restrictTo(listed);
            assertThat(deck.news()).hasSizeGreaterThan(80);
            for (var card : deck.news())
                assertThat(card.moves().keySet()).allMatch(id -> listed.stock(id) != null);
        }
    }

    @Test
    void 이십분_판에서_속보가_반복되지_않는다() {
        for (int seed = 0; seed < 20; seed++) {
            var random = new SplittableRandom(seed);
            var listed = stocks.pick(12, random);
            var market = new Market(listed, news.restrictTo(listed), random, 1200);
            for (long t = 1; t <= 1200; t++) market.advance(t);
            var seen = new HashSet<String>();
            List<Market.NewsItem> items = market.history().stream().filter(i -> i.kind().equals("news")).toList();
            assertThat(items.size()).as("20분 판 속보 수").isBetween(28, 50);
            for (var item : items) assertThat(seen.add(item.headline())).as("반복: " + item.headline()).isTrue();
        }
    }
}
