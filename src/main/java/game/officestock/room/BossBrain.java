package game.officestock.room;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 부장님의 머리(선택): 뉴스가 나올 때 Claude에게 매매 판단과 혼잣말을 묻는다.
 * 서버 환경 변수 ANTHROPIC_API_KEY가 있을 때만 켜지고, 없거나 늦거나 이상한 답이 오면 규칙 판단을 그대로 쓴다.
 * 순찰·판정처럼 승패에 바로 닿는 것은 서버 규칙이 정하고, 여기서는 매매와 대사만 받는다.
 */
@Component
public class BossBrain {
    private static final Logger log = LoggerFactory.getLogger(BossBrain.class);

    /** 모델이 돌려줄 형식. 주문은 이번 판에 상장된 종목만, 비율은 0~1. */
    record Order(@JsonPropertyDescription("종목 코드(목록에 있는 것만)") String symbol,
                 @JsonPropertyDescription("buy 또는 sell") String action,
                 @JsonPropertyDescription("buy면 현금의, sell이면 보유 주식의 비율(0~1)") double fraction) {}

    record Reply(@JsonPropertyDescription("이번 뉴스에 낼 주문. 안 하면 빈 목록") List<Order> orders,
                 @JsonPropertyDescription("부장님 혼잣말(한국어, 40자 이내, 직장인 풍자 톤)") String line) {}

    /** 판단에 필요한 상황. */
    record Situation(String headline, boolean rumor, double profitPercent, String mood, long cashWon,
                     Map<String, Long> holdings, Map<String, String> stockNames, Map<String, Long> prices) {}

    private static final String SYSTEM = """
            너는 사무실 주식 게임 "부장님 몰래"의 부장님이다. 직원들 몰래 너도 근무 중에 주식을 한다.
            뉴스나 찌라시 한 건을 읽고, 이번에 낼 주문과 혼잣말 한마디를 정한다.
            - 찌라시는 가짜일 수 있다. 속보는 대체로 맞지만 이미 가격에 반영됐을 수 있다.
            - 손해가 크면 조급해져서 크게 지르거나 던지고, 수익이 크면 여유가 생긴다.
            - 주문은 목록에 있는 종목 코드만 쓴다. 공매도는 없으니 가진 주식만 팔 수 있다.
            - 혼잣말은 한국어로 짧게, 회사 상사가 모니터 보며 중얼거리는 말투로. 욕설·차별·실존 인물 이름은 쓰지 않는다.
            """;

    private final String model;
    private final int maxCallsPerRound;
    private final AnthropicClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BossBrain(@Value("${game.boss-llm-model:claude-haiku-5-5}") String model,
                     @Value("${game.boss-llm-max-calls:40}") int maxCallsPerRound) {
        this.model = model;
        this.maxCallsPerRound = maxCallsPerRound;
        var key = System.getenv("ANTHROPIC_API_KEY");
        this.client = key == null || key.isBlank() ? null : AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofSeconds(8))
                .maxRetries(0)   // 늦으면 규칙 판단으로 넘어가므로 다시 묻지 않는다.
                .build();
        log.info("부장님 매매 판단: {}", client == null ? "규칙(ANTHROPIC_API_KEY 없음)" : "Claude " + model);
    }

    boolean enabled() {
        return client != null;
    }

    int maxCallsPerRound() {
        return maxCallsPerRound;
    }

    /**
     * 판단을 비동기로 묻는다. 실패·시간 초과면 fallback(규칙 판단)으로 끝난다. 게임 스레드를 막지 않는다.
     * @param listed 이번 판 종목 코드(검증용)
     */
    CompletableFuture<List<Boss.Decision>> decide(Situation situation, java.util.Set<String> listed,
                                                  List<Boss.Decision> fallback) {
        if (client == null) return CompletableFuture.completedFuture(fallback);
        return CompletableFuture.supplyAsync(() -> ask(situation, listed), executor)
                .completeOnTimeout(null, 10, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("부장님 판단 실패, 규칙으로 대신함: {}", e.toString());
                    return null;
                })
                .thenApply(decisions -> decisions == null ? fallback : decisions);
    }

    private List<Boss.Decision> ask(Situation s, java.util.Set<String> listed) {
        var stocks = new StringBuilder();
        s.stockNames().forEach((id, name) -> stocks.append("- ").append(id).append(' ').append(name)
                .append(" 현재가 ").append(s.prices().getOrDefault(id, 0L)).append("원")
                .append(s.holdings().getOrDefault(id, 0L) > 0 ? " (보유 " + s.holdings().get(id) + "주)" : "")
                .append('\n'));
        var prompt = """
                [%s] %s

                내 상황: 현금 %,d원, 오늘 손익 %+.1f%%, 기분 %s
                종목:
                %s""".formatted(s.rumor() ? "찌라시" : "속보", s.headline(), s.cashWon(), s.profitPercent(),
                s.mood(), stocks);
        StructuredMessageCreateParams<Reply> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(SYSTEM)
                .outputConfig(Reply.class)
                .addUserMessage(prompt)
                .build();
        var reply = client.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst().orElse(null);
        if (reply == null) return null;
        return validate(reply, listed);
    }

    /** 모델 답을 검사한다: 목록에 없는 종목·이상한 행동은 버리고 비율은 0~1로 자른다. 대사는 짧게. */
    static List<Boss.Decision> validate(Reply reply, java.util.Set<String> listed) {
        var decisions = new ArrayList<Boss.Decision>();
        String line = reply.line() == null ? null : reply.line().strip();
        if (line != null && (line.isEmpty() || line.length() > 60)) line = line.isEmpty() ? null : line.substring(0, 60);
        if (reply.orders() != null) {
            for (var order : reply.orders()) {
                if (order == null || order.symbol() == null || !listed.contains(order.symbol())) continue;
                boolean buy = "buy".equalsIgnoreCase(order.action());
                if (!buy && !"sell".equalsIgnoreCase(order.action())) continue;
                double fraction = Double.isFinite(order.fraction()) ? Math.max(0, Math.min(buy ? 0.5 : 1, order.fraction())) : 0;
                if (fraction <= 0) continue;
                decisions.add(new Boss.Decision(order.symbol(), buy, fraction, decisions.isEmpty() ? line : null));
            }
        }
        // 주문이 없어도 혼잣말은 남긴다(종목 없는 빈 판단).
        if (decisions.isEmpty() && line != null) decisions.add(new Boss.Decision(null, false, 0, line));
        return decisions;
    }
}
