package game.officestock.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MarketNews(String symbol, int changePercent, String headline) {
    private static final List<MarketNews> STORIES = List.of(
        new MarketNews("민트식품", 10, "편의점 3사, 민트초코 라면 추가 발주"),
        new MarketNews("민트식품", -8, "신제품 포장 불량으로 출고 지연"),
        new MarketNews("민트식품", 6, "급식 납품 계약 체결"),
        new MarketNews("민트식품", -6, "원재료 수입 단가 상승"),
        new MarketNews("민트식품", 8, "해외 시식 행사에서 초도 물량 완판"),
        new MarketNews("민트식품", -7, "대형마트 판촉 행사 취소"),
        new MarketNews("민트식품", 5, "생산라인 개선으로 포장비 절감"),
        new MarketNews("달나라로봇", 14, "물류 로봇 대규모 공급 계약 체결"),
        new MarketNews("달나라로봇", -12, "시연 중 로봇 팔 멈춰 재점검"),
        new MarketNews("달나라로봇", 9, "야간 배송 로봇 실증 승인"),
        new MarketNews("달나라로봇", -9, "핵심 센서 납품 일정 연기"),
        new MarketNews("달나라로봇", 12, "신형 배터리 주행 시험 통과"),
        new MarketNews("달나라로봇", -11, "주력 모델 무상 수리 확대"),
        new MarketNews("달나라로봇", 7, "창고 자동화 시범 사업 선정"),
        new MarketNews("우리회사", 8, "최대 고객사와 계약 갱신"),
        new MarketNews("우리회사", -7, "분기 납품 일정 차질"),
        new MarketNews("우리회사", 6, "사내 시제품 첫 유료 고객 확보"),
        new MarketNews("우리회사", -5, "신사옥 이전 비용 예상 초과"),
        new MarketNews("우리회사", 9, "해외 지사 첫 흑자 발표"),
        new MarketNews("우리회사", -8, "주요 입찰에서 경쟁사에 밀려"),
        new MarketNews("우리회사", 5, "장기 유지보수 계약 추가 수주")
    );

    public static List<MarketNews> shuffledDeck() {
        var deck = new ArrayList<>(STORIES);
        Collections.shuffle(deck);
        return deck;
    }
}
