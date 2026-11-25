package game.officestock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/** 뉴스 덱. 카드 내용은 news.json에 있다. */
final class MarketNews {
    private MarketNews() {}

    /** 모든 뉴스 카드를 한 덱으로 섞는다. 덱을 다 쓰면 다시 섞는다. */
    static List<NewsCatalog.Card> shuffledDeck(NewsCatalog news, RandomGenerator random) {
        var deck = new ArrayList<>(news.news());
        Collections.shuffle(deck, random);
        return deck;
    }
}
