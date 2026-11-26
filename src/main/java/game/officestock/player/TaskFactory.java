package game.officestock.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 진짜 업무 같은 일거리를 만든다. 엑셀 집계(40%), 전자결재(30%), 메일 분류(30%)를 섞는다.
 * 화면에 보낼 내용(data)과 정답(answer)을 따로 만들고, 정답은 서버에만 둔다.
 */
final class TaskFactory {
    private TaskFactory() {}

    /** @param data 화면에 그릴 내용 @param answer 정답(화면에 보내지 않는다) @param hint 틀렸을 때 보여 줄 말 */
    record Made(String kind, String title, Map<String, Object> data, String answer, String hint) {}

    static Made make(RandomGenerator random) {
        double roll = random.nextDouble();
        if (roll < 0.4) return sheet(random);
        if (roll < 0.7) return approval(random);
        return mail(random);
    }

    // ── 엑셀 집계 ──────────────────────────────────────────
    private static final List<String> CLIENTS = List.of("한빛유통", "새봄마트", "동해물산", "푸른상사", "다온식품",
            "미래로지스", "하나전자", "청솔건설", "가온커머스", "누리테크", "별빛무역", "온새미로");
    private static final List<String> SHEETS = List.of("11월 거래처별 매출", "3분기 매입 내역", "주간 납품 실적",
            "부서별 비품 구매액", "지점별 판매 실적");

    private static Made sheet(RandomGenerator random) {
        // 쫓기면서도 암산으로 몇 초 안에 끝낼 수 있게 2~3행, 100 단위 금액만 쓴다.
        int rows = random.nextInt(2, 4);
        var picked = new ArrayList<>(CLIENTS);
        java.util.Collections.shuffle(picked, random);
        var table = new ArrayList<Map<String, Object>>();
        long sum = 0;
        for (int i = 0; i < rows; i++) {
            long amount = random.nextInt(1, 10) * 100L;
            sum += amount;
            table.add(Map.of("name", picked.get(i), "amount", amount));
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("sheet", SHEETS.get(random.nextInt(SHEETS.size())));
        data.put("unit", "천원");
        data.put("rows", table);
        data.put("targetCell", "B" + (rows + 2));
        return new Made("sheet", "엑셀 · " + data.get("sheet"), data, Long.toString(sum),
                "합계가 맞지 않습니다. 다시 더해 보세요.");
    }

    // ── 전자결재 ──────────────────────────────────────────
    private record Rule(String category, String text, long limitWon, boolean perPerson) {}

    private static final List<Rule> RULES = List.of(
            new Rule("비품 구매", "비품 구매는 한 건 50만 원 이하만 팀장 전결로 승인한다. 넘으면 반려하고 본부장 결재로 올린다.", 500_000, false),
            new Rule("국내 출장비", "국내 출장비는 하루 30만 원 이하만 승인한다.", 300_000, false),
            new Rule("팀 식대", "팀 식대는 1인당 15,000원 이하만 승인한다.", 15_000, true),
            new Rule("교육비", "외부 교육비는 한 건 80만 원 이하만 승인한다.", 800_000, false));
    private static final List<String> DRAFTERS = List.of("김대리", "이주임", "박사원", "최과장", "정대리", "한사원");
    private static final Map<String, List<String>> ITEMS = Map.of(
            "비품 구매", List.of("모니터 2대", "사무용 의자 3개", "복합기 토너", "노트북 거치대 5개", "회의실 화이트보드"),
            "국내 출장비", List.of("부산 거래처 미팅", "대전 공장 점검", "광주 지점 교육", "제주 박람회 참가"),
            "팀 식대", List.of("분기 마감 회식", "신입 환영 점심", "야근 저녁 식사", "프로젝트 종료 회식"),
            "교육비", List.of("엑셀 고급 과정", "리더십 워크숍", "데이터 분석 입문", "영어 회화 과정"));

    private static Made approval(RandomGenerator random) {
        var rule = RULES.get(random.nextInt(RULES.size()));
        var items = ITEMS.get(rule.category());
        var data = new LinkedHashMap<String, Object>();
        data.put("docNo", "결재-" + (2600 + random.nextInt(400)));
        data.put("drafter", DRAFTERS.get(random.nextInt(DRAFTERS.size())));
        data.put("category", rule.category());
        data.put("item", items.get(random.nextInt(items.size())));
        data.put("rule", rule.text());
        boolean ok;
        if (rule.perPerson()) {
            int people = random.nextInt(4, 13);
            // 1인당 금액이 한도 근처에 오도록 만든다(딱 맞는 경우도 나온다).
            long perPerson = Math.round(rule.limitWon() * (0.75 + random.nextDouble() * 0.5) / 100) * 100;
            long total = perPerson * people;
            data.put("people", people);
            data.put("amountWon", total);
            ok = perPerson <= rule.limitWon();
        } else {
            long amount = Math.round(rule.limitWon() * (0.6 + random.nextDouble() * 0.8) / 1_000) * 1_000;
            data.put("amountWon", amount);
            ok = amount <= rule.limitWon();
        }
        return new Made("approval", "전자결재 · " + rule.category(), data, ok ? "APPROVE" : "REJECT",
                "규정과 맞지 않습니다. 금액과 한도를 다시 보세요.");
    }

    // ── 메일 분류 ─────────────────────────────────────────
    private record Mail(String folder, String from, String subject, String body) {}

    static final List<String> FOLDERS = List.of("고객 문의", "사내 공지", "결재 요청", "스팸");
    private static final List<Mail> MAILS = List.of(
            new Mail("고객 문의", "구매팀 <buy@hanbit.co.kr>", "지난주 주문 건 배송 일정 문의드립니다", "안녕하세요, 지난주 발주한 물량의 입고 예정일을 알 수 있을까요?"),
            new Mail("고객 문의", "새봄마트 김점장", "견적서 재발행 요청", "단가가 바뀌었다고 들었습니다. 수정된 견적서를 다시 보내 주실 수 있나요?"),
            new Mail("고객 문의", "동해물산 박과장", "반품 절차가 궁금합니다", "일부 제품에 파손이 있어 반품하려 합니다. 절차를 안내해 주세요."),
            new Mail("고객 문의", "다온식품 고객센터", "계약 갱신 관련 문의", "내년 계약 조건을 미리 협의하고 싶습니다. 가능한 일정 알려 주세요."),
            new Mail("사내 공지", "인사팀", "[공지] 연말 건강검진 일정 안내", "올해 건강검진 마감은 12월 20일입니다. 미수검자는 기한 내 예약 바랍니다."),
            new Mail("사내 공지", "총무팀", "[공지] 3층 회의실 공사 안내", "다음 주 월~수 3층 회의실이 공사로 사용 불가합니다."),
            new Mail("사내 공지", "보안팀", "[필독] 비밀번호 변경 주기 안내", "사내 시스템 비밀번호는 90일마다 바꿔야 합니다. 이번 주 안에 변경해 주세요."),
            new Mail("사내 공지", "대표이사실", "송년회 일정 공유", "올해 송년회는 12월 26일 저녁 본사 강당에서 진행합니다."),
            new Mail("결재 요청", "이주임", "[결재 요청] 신규 거래처 등록", "누리테크를 신규 거래처로 등록하려 합니다. 첨부 서류 확인 후 결재 부탁드립니다."),
            new Mail("결재 요청", "최과장", "[결재 요청] 12월 마케팅 예산안", "다음 달 온라인 광고 예산안을 올립니다. 검토 후 승인 부탁드립니다."),
            new Mail("결재 요청", "정대리", "[결재 요청] 연차 사용 신청", "12월 24일 연차 사용을 신청합니다. 업무 인수인계는 완료했습니다."),
            new Mail("결재 요청", "박사원", "[결재 요청] 법인카드 사용 내역 확인", "지난달 법인카드 사용 내역입니다. 확인 후 결재 부탁드립니다."),
            new Mail("스팸", "당첨 안내 <win@lucky-event.biz>", "축하합니다! 최신 스마트폰에 당첨되셨습니다", "아래 링크에서 개인정보를 입력하시면 바로 배송해 드립니다."),
            new Mail("스팸", "투자의신", "원금 보장 주식 리딩방 무료 초대", "지금 들어오시면 수익률 300% 종목을 공짜로 알려 드립니다."),
            new Mail("스팸", "택배 알림 <dl1very@notice.top>", "[긴급] 배송 주소가 확인되지 않습니다", "주소 확인을 위해 첨부 파일을 열고 결제 정보를 입력해 주세요."),
            new Mail("스팸", "해외 왕자", "유산 상속 협조 요청", "막대한 유산을 옮기는 데 귀하의 계좌가 필요합니다. 수수료만 보내 주세요."));

    private static Made mail(RandomGenerator random) {
        var mail = MAILS.get(random.nextInt(MAILS.size()));
        var data = new LinkedHashMap<String, Object>();
        data.put("from", mail.from());
        data.put("subject", mail.subject());
        data.put("body", mail.body());
        data.put("folders", FOLDERS);
        return new Made("mail", "메일 · 받은편지함 정리", data, mail.folder(),
                "다른 폴더가 더 맞습니다. 보낸 사람과 내용을 다시 보세요.");
    }
}
