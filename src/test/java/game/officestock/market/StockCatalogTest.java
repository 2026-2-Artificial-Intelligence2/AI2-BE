package game.officestock.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockCatalogTest {
    private static StockCatalog.Stock plain(String id, String sector) {
        return new StockCatalog.Stock(id, id + "이름", 10_000, 1_000, 0.01, List.of(), null, null, sector);
    }

    private static StockCatalog catalog() {
        var stocks = new ArrayList<StockCatalog.Stock>();
        for (int i = 0; i < 20; i++) stocks.add(plain("S" + i, "업종" + i));
        stocks.add(new StockCatalog.Stock("S0X3", "S0 3X", 10_000, 1_000, 0, List.of(), "S0", 3.0, null));
        return new StockCatalog(stocks);
    }

    @Test
    void 판마다_일반_종목_n개를_뽑고_레버리지는_기초_종목이_뽑혔을_때만_나온다() {
        var full = catalog();
        for (int seed = 0; seed < 50; seed++) {
            var listed = full.pick(12, new SplittableRandom(seed));
            var regular = listed.stocks().stream().filter(s -> !s.derived()).toList();
            assertThat(regular).hasSize(12);
            boolean baseListed = listed.stock("S0") != null;
            assertThat(listed.stock("S0X3") != null).isEqualTo(baseListed);
        }
    }

    @Test
    void 개수가_0이거나_전부면_목록을_그대로_쓴다() {
        var full = catalog();
        assertThat(full.pick(0, new SplittableRandom(1))).isSameAs(full);
        assertThat(full.pick(99, new SplittableRandom(1))).isSameAs(full);
    }

    @Test
    void 레버리지_종목은_기초_종목의_업종을_따른다() {
        var full = catalog();
        assertThat(full.sectorOf(full.stock("S0X3"))).isEqualTo("업종0");
    }

    @Test
    void 일반_종목은_업종이_꼭_있어야_한다() {
        assertThatThrownBy(() -> new StockCatalog(List.of(plain("A", null))))
                .hasMessageContaining("업종");
    }

    @Test
    void 코드가_겹치면_서버가_뜨지_않는다() {
        assertThatThrownBy(() -> new StockCatalog(List.of(plain("A", "x"), plain("A", "y"))))
                .hasMessageContaining("겹칩니다");
    }
}
