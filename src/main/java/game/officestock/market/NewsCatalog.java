package game.officestock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 뉴스 카드와 찌라시 문구. 뉴스를 추가할 때는 resources/market/news.json의 news 배열에 항목을 더하면 된다.
 * 종목 목록과 맞춰 보는 검증은 {@link #checkAgainst(StockCatalog)}가 서버 시작 때 한다.
 */
public record NewsCatalog(List<Card> news, Rumors rumors) {
    /** 찌라시 문구에서 종목 이름이 들어갈 자리. */
    public static final String NAME = "{name}";

    /**
     * 뉴스 카드 한 장. 한 종목 뉴스는 stock과 changePercent를, 여러 종목 뉴스는 effects를 쓴다(둘 중 하나만).
     *
     * @param stock 한 종목 뉴스의 종목 코드(stocks.json의 id). 제목 앞에 종목 이름이 붙는다.
     * @param changePercent 한 종목 뉴스의 적정가 변화율(%)
     * @param effects 여러 종목 뉴스의 종목 코드별 변화율(%). 제목은 그대로 보인다.
     * @param headline 제목. 속보 팝업과 알림창에 보인다.
     * @param body 본문(선택). 팝업에는 한 줄, 알림창에는 전체가 보인다.
     */
    public record Card(String stock, Integer changePercent, Map<String, Integer> effects,
                       String headline, String body) {
        public Card {
            if (body != null && body.isBlank()) body = null;
            if (effects != null) effects = Collections.unmodifiableMap(new LinkedHashMap<>(effects));
        }

        /** 종목 코드별 변화율. 한 종목 뉴스도 같은 모양으로 돌려준다. */
        public Map<String, Integer> moves() {
            return effects != null ? effects : Map.of(stock, changePercent);
        }

        public boolean multiStock() {
            return effects != null;
        }
    }

    /** 찌라시 문구. 각 문장에 {name}이 있어야 한다. good은 호재, bad는 악재 소문이다. */
    public record Rumors(List<String> good, List<String> bad) {}

    public NewsCatalog {
        if (news == null || news.isEmpty()) throw invalid("뉴스가 하나 이상 필요합니다.");
        news = List.copyOf(news);
        var seen = new HashSet<String>();
        for (int i = 0; i < news.size(); i++) {
            var card = news.get(i);
            var where = (i + 1) + "번째 뉴스(" + (card.headline() == null ? "제목 없음" : card.headline()) + ")";
            boolean single = card.stock() != null || card.changePercent() != null;
            if (single == (card.effects() != null))
                throw invalid(where + ": stock과 changePercent, 또는 effects 중 한 가지만 써야 합니다.");
            if (single) {
                if (card.stock() == null || card.stock().isBlank()) throw invalid(where + ": stock이 비었습니다.");
                if (card.changePercent() == null) throw invalid(where + ": changePercent가 없습니다.");
            } else if (card.effects().isEmpty()) {
                throw invalid(where + ": effects에 종목이 하나 이상 필요합니다.");
            }
            for (var move : card.moves().entrySet()) {
                if (move.getValue() == null || move.getValue() == 0 || move.getValue() <= -100)
                    throw invalid(where + ": " + move.getKey() + "의 변화율은 0이 아니고 -100보다 커야 합니다.");
            }
            if (card.headline() == null || card.headline().isBlank()) throw invalid(where + ": headline이 비었습니다.");
            if (!seen.add(new TreeSet<>(card.moves().keySet()) + "\u0000" + card.headline()))
                throw invalid(where + ": 같은 종목에 같은 제목의 뉴스가 이미 있습니다.");
        }
        if (rumors == null) throw invalid("rumors가 없습니다.");
        rumors = new Rumors(checkedPhrases("rumors.good", rumors.good()), checkedPhrases("rumors.bad", rumors.bad()));
    }

    private static List<String> checkedPhrases(String field, List<String> phrases) {
        if (phrases == null || phrases.isEmpty()) throw invalid(field + ": 문구가 하나 이상 필요합니다.");
        for (var phrase : phrases) {
            if (phrase == null || !phrase.contains(NAME))
                throw invalid(field + ": 문구마다 " + NAME + "이 있어야 합니다: " + phrase);
        }
        return List.copyOf(phrases);
    }

    /** 모든 뉴스가 있는 종목을 가리키고, 모든 종목에 뉴스가 하나 이상 있는지 확인한다. */
    public NewsCatalog checkAgainst(StockCatalog stocks) {
        for (var card : news) {
            for (var id : card.moves().keySet()) {
                if (stocks.stock(id) == null)
                    throw invalid("없는 종목 코드의 뉴스입니다: " + id + " · " + card.headline());
                if (stocks.stock(id).derived())
                    throw invalid("레버리지 종목(" + id + ")에는 뉴스를 쓰지 않습니다. 기초 종목의 뉴스를 따릅니다: " + card.headline());
            }
        }
        for (var stock : stocks.stocks()) {
            if (stock.derived()) continue;
            if (cardsFor(stock.id()).isEmpty())
                throw invalid(stock.id() + "(" + stock.name() + ")의 뉴스가 하나도 없습니다.");
        }
        return this;
    }

    /** 이 종목을 움직이는 카드(여러 종목 뉴스 포함). */
    public List<Card> cardsFor(String stockId) {
        return news.stream().filter(card -> card.moves().containsKey(stockId)).toList();
    }

    /** 종목별 덱 구성 요약. 서버 시작 로그에 남겨 뉴스 균형을 보기 쉽게 한다. */
    public List<String> summary(StockCatalog stocks) {
        var lines = new ArrayList<String>();
        var counts = new LinkedHashMap<String, int[]>();
        for (var stock : stocks.stocks()) if (!stock.derived()) counts.put(stock.id(), new int[4]);
        for (var card : news) {
            card.moves().forEach((id, change) -> {
                var c = counts.get(id);
                c[change > 0 ? 0 : 1]++;
                c[2] += change;
                if (card.multiStock()) c[3]++;
            });
        }
        long multi = news.stream().filter(Card::multiStock).count();
        if (multi > 0) lines.add("여러 종목 뉴스 " + multi + "장");
        long withoutBody = news.stream().filter(card -> card.body() == null).count();
        if (withoutBody > 0) lines.add("본문 없는 뉴스 " + withoutBody + "장 (알림창에 제목만 보인다)");
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            var c = e.getValue();
            int total = c[0] + c[1];
            lines.add(String.format("%s(%s) %d장(여러 종목 뉴스 %d): 호재 %d · 악재 %d · 평균 %+.1f%%",
                    e.getKey(), stocks.stock(e.getKey()).name(), total, c[3], c[0], c[1],
                    (double) c[2] / total));
        }
        return lines;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("news.json: " + message);
    }
}
