package game.officestock.market;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsCatalogTest {
    private static final NewsCatalog.Rumors RUMORS = new NewsCatalog.Rumors(List.of("{name} 좋다"), List.of("{name} 나쁘다"));

    private static StockCatalog stocks(String... ids) {
        return new StockCatalog(java.util.Arrays.stream(ids)
                .map(id -> new StockCatalog.Stock(id, id + "이름", 10_000, 1_000, 0.01, List.of(), null, null, "업종"))
                .toList());
    }

    @Test
    void 상장된_종목만_남기고_여러_종목_뉴스는_반응을_줄인다() {
        var catalog = new NewsCatalog(List.of(
                new NewsCatalog.Card("A", 5, null, "A 호재", null),
                new NewsCatalog.Card("B", -5, null, "B 악재", null),
                new NewsCatalog.Card(null, null, new LinkedHashMap<>(Map.of("A", 3, "B", -4)), "외부 사건", null),
                new NewsCatalog.Card(null, null, Map.of("B", 2), "B만 외부", null)), RUMORS);
        var listed = stocks("A");
        var restricted = catalog.restrictTo(listed);
        assertThat(restricted.news()).extracting(NewsCatalog.Card::headline).containsExactly("A 호재", "외부 사건");
        assertThat(restricted.news().get(1).moves()).containsOnlyKeys("A").containsEntry("A", 3);
    }

    @Test
    void 없는_종목의_뉴스나_뉴스_없는_종목은_서버가_뜨지_않는다() {
        var catalog = new NewsCatalog(List.of(new NewsCatalog.Card("A", 5, null, "A 호재", null)), RUMORS);
        assertThatThrownBy(() -> catalog.checkAgainst(stocks("B"))).hasMessageContaining("없는 종목");
        assertThatThrownBy(() -> catalog.checkAgainst(stocks("A", "C"))).hasMessageContaining("뉴스가 하나도 없습니다");
    }

    @Test
    void 찌라시_문구에는_종목_이름_자리가_있어야_한다() {
        assertThatThrownBy(() -> new NewsCatalog(List.of(new NewsCatalog.Card("A", 5, null, "h", null)),
                new NewsCatalog.Rumors(List.of("이름 없음"), List.of("{name} x")))).hasMessageContaining("{name}");
    }
}
