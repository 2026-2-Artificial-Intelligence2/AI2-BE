package game.officestock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/** 뉴스 카드 한 장. symbol은 종목 코드다. 카드 내용은 stocks.json에 있다. */
record MarketNews(String symbol, int changePercent, String headline) {
    /** 모든 종목의 뉴스를 한 덱으로 섞는다. 덱을 다 쓰면 다시 섞는다. */
    static List<MarketNews> shuffledDeck(StockCatalog catalog, RandomGenerator random) {
        var deck = new ArrayList<MarketNews>();
        for (var stock : catalog.stocks()) {
            for (var news : stock.news()) {
                deck.add(new MarketNews(stock.id(), news.changePercent(), news.headline()));
            }
        }
        Collections.shuffle(deck, random);
        return deck;
    }
}
