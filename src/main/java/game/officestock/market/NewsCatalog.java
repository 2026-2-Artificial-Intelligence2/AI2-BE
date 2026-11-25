package game.officestock.market;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 뉴스 카드와 찌라시 문구. 뉴스를 추가할 때는 resources/market/news.json의 news 배열에 한 줄을 더하면 된다.
 * 종목 목록과 맞춰 보는 검증은 {@link #checkAgainst(StockCatalog)}가 서버 시작 때 한다.
 */
public record NewsCatalog(List<Card> news, Rumors rumors) {
    /** 찌라시 문구에서 종목 이름이 들어갈 자리. */
    public static final String NAME = "{name}";

    /**
     * @param stock 종목 코드(stocks.json의 id)
     * @param changePercent 적정가 변화율(%). 0이 아니고 -100보다 커야 한다.
     * @param headline 화면에 보이는 제목. 앞에 종목 이름이 자동으로 붙는다.
     */
    public record Card(String stock, int changePercent, String headline) {}

    /** 찌라시 문구. 각 문장에 {name}이 있어야 한다. good은 호재, bad는 악재 소문이다. */
    public record Rumors(List<String> good, List<String> bad) {}

    public NewsCatalog {
        if (news == null || news.isEmpty()) throw invalid("뉴스가 하나 이상 필요합니다.");
        news = List.copyOf(news);
        var headlines = new HashSet<String>();
        for (int i = 0; i < news.size(); i++) {
            var card = news.get(i);
            var where = (i + 1) + "번째 뉴스(" + (card.headline() == null ? "제목 없음" : card.headline()) + ")";
            if (card.stock() == null || card.stock().isBlank()) throw invalid(where + ": stock이 비었습니다.");
            if (card.changePercent() == 0 || card.changePercent() <= -100)
                throw invalid(where + ": changePercent는 0이 아니고 -100보다 커야 합니다.");
            if (card.headline() == null || card.headline().isBlank()) throw invalid(where + ": headline이 비었습니다.");
            if (!headlines.add(card.stock() + "\u0000" + card.headline()))
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
            if (stocks.stock(card.stock()) == null)
                throw invalid("없는 종목 코드의 뉴스입니다: " + card.stock() + " · " + card.headline());
        }
        for (var stock : stocks.stocks()) {
            if (cardsFor(stock.id()).isEmpty())
                throw invalid(stock.id() + "(" + stock.name() + ")의 뉴스가 하나도 없습니다.");
        }
        return this;
    }

    public List<Card> cardsFor(String stockId) {
        return news.stream().filter(card -> card.stock().equals(stockId)).toList();
    }

    /** 종목별 덱 구성 요약. 서버 시작 로그에 남겨 뉴스 균형을 보기 쉽게 한다. */
    public List<String> summary(StockCatalog stocks) {
        var lines = new ArrayList<String>();
        var counts = new LinkedHashMap<String, int[]>();
        for (var stock : stocks.stocks()) counts.put(stock.id(), new int[3]);
        for (var card : news) {
            var c = counts.get(card.stock());
            c[card.changePercent() > 0 ? 0 : 1]++;
            c[2] += card.changePercent();
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            var c = e.getValue();
            int total = c[0] + c[1];
            lines.add(String.format("%s(%s) %d장: 호재 %d · 악재 %d · 평균 %+.1f%%",
                    e.getKey(), stocks.stock(e.getKey()).name(), total, c[0], c[1], (double) c[2] / total));
        }
        return lines;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("news.json: " + message);
    }
}
