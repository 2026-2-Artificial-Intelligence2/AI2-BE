package game.officestock.market;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 종목 목록. 종목을 추가할 때는 resources/market/stocks.json에 항목을 하나 더하면 된다.
 * 서버가 시작할 때 한 번 읽고 검증하며, 잘못된 항목이 있으면 서버가 뜨지 않는다.
 */
public record StockCatalog(List<Stock> stocks) {
    private static final Pattern ID = Pattern.compile("[A-Z0-9_]{1,16}");

    /**
     * @param id 주문·시세 메시지에 쓰는 고정 코드(영문 대문자·숫자·밑줄). 화면에 보이지 않는다.
     * @param name 화면과 뉴스에 보이는 이름
     * @param startPriceWon 시작 기준가. 적정가는 이 값의 1/10~10배 안에서 움직인다.
     * @param poolShares 풀의 주식 수. 클수록 큰 주문에도 가격이 덜 밀린다.
     * @param volatilityPerSecond 평소 흔들림의 초당 표준편차(0.01 = 1%)
     * @param news 이 종목의 뉴스 카드
     */
    public record Stock(String id, String name, long startPriceWon, long poolShares,
                        double volatilityPerSecond, List<News> news) {}

    /** @param changePercent 적정가 변화율(%). 0이 아니고 -100보다 커야 한다. */
    public record News(int changePercent, String headline) {}

    public StockCatalog {
        if (stocks == null || stocks.isEmpty()) throw invalid("종목이 하나 이상 필요합니다.");
        stocks = List.copyOf(stocks);
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        for (var s : stocks) {
            if (s.id() == null || !ID.matcher(s.id()).matches())
                throw invalid("종목 코드는 영문 대문자·숫자·밑줄 1~16자여야 합니다: " + s.id());
            if (!ids.add(s.id())) throw invalid("종목 코드가 겹칩니다: " + s.id());
            if (s.name() == null || s.name().isBlank()) throw invalid(s.id() + ": 이름이 비었습니다.");
            if (!names.add(s.name())) throw invalid("종목 이름이 겹칩니다: " + s.name());
            if (s.startPriceWon() < 10) throw invalid(s.id() + ": 시작 기준가는 10원 이상이어야 합니다.");
            if (s.poolShares() < 10) throw invalid(s.id() + ": 풀 주식 수는 10주 이상이어야 합니다.");
            Math.multiplyExact(s.startPriceWon(), s.poolShares());
            if (!(s.volatilityPerSecond() >= 0 && s.volatilityPerSecond() < 0.2))
                throw invalid(s.id() + ": 흔들림은 0 이상 0.2 미만이어야 합니다.");
            if (s.news() == null || s.news().isEmpty()) throw invalid(s.id() + ": 뉴스가 하나 이상 필요합니다.");
            for (var n : s.news()) {
                if (n.changePercent() == 0 || n.changePercent() <= -100)
                    throw invalid(s.id() + ": 뉴스 변화율은 0이 아니고 -100보다 커야 합니다.");
                if (n.headline() == null || n.headline().isBlank())
                    throw invalid(s.id() + ": 뉴스 제목이 비었습니다.");
            }
        }
    }

    public Stock stock(String id) {
        return stocks.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("stocks.json: " + message);
    }
}
