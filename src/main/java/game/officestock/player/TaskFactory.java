package game.officestock.player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
        return make(random, new ArrayDeque<>());
    }

    /**
     * @param mailDeck 이 사람에게 아직 안 보낸 메일 번호. 비면 다시 섞어 채운다.
     *                 한 사람이 같은 메일을 다시 받는 것은 48통을 다 본 뒤다.
     */
    static Made make(RandomGenerator random, Deque<Integer> mailDeck) {
        double roll = random.nextDouble();
        if (roll < 0.4) return sheet(random);
        if (roll < 0.7) return approval(random);
        return mail(random, mailDeck);
    }

    // ── 엑셀 집계 ──────────────────────────────────────────
    private static final List<String> CLIENTS = List.of("한빛유통", "새봄마트", "동해물산", "푸른상사", "다온식품",
            "미래로지스", "하나전자", "청솔건설", "가온커머스", "누리테크", "별빛무역", "온새미로",
            "바른정밀", "솔빛화학", "대양해운", "한결제약", "새날가구", "은하인쇄", "초록농산", "다솜리테일",
            "한마음식자재", "푸른바다수산", "든솔물류", "아라전기", "보람테크", "늘봄제과", "큰산철강", "단비유업");
    private static final List<String> SHEETS = List.of("11월 거래처별 매출", "3분기 매입 내역", "주간 납품 실적",
            "부서별 비품 구매액", "지점별 판매 실적", "월별 광고비 집행", "협력사 미수금", "출장비 정산 내역",
            "분기 반품 금액", "신규 거래처 첫 주문", "팀별 야근 식대", "창고별 재고 금액", "연말 선물 구매 내역",
            "온라인몰 일 매출", "택배비 정산");

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
    private static final List<String> DRAFTERS = List.of("김대리", "이주임", "박사원", "최과장", "정대리", "한사원",
            "윤대리", "오주임", "서사원", "강과장", "조대리", "임사원", "신주임", "장차장");
    private static final Map<String, List<String>> ITEMS = Map.of(
            "비품 구매", List.of("모니터 2대", "사무용 의자 3개", "복합기 토너", "노트북 거치대 5개", "회의실 화이트보드",
                    "무선 키보드 10개", "탕비실 커피머신", "서류 파쇄기", "회의실 빔프로젝터", "스탠딩 책상 2개", "공기청정기"),
            "국내 출장비", List.of("부산 거래처 미팅", "대전 공장 점검", "광주 지점 교육", "제주 박람회 참가",
                    "울산 협력사 감사", "강릉 워크숍 답사", "대구 신규 매장 오픈", "인천 물류센터 점검", "전주 고객 간담회"),
            "팀 식대", List.of("분기 마감 회식", "신입 환영 점심", "야근 저녁 식사", "프로젝트 종료 회식",
                    "송년회", "퇴사자 환송회", "주말 특근 점심", "팀장 승진 축하", "신규 수주 축하"),
            "교육비", List.of("엑셀 고급 과정", "리더십 워크숍", "데이터 분석 입문", "영어 회화 과정",
                    "파이썬 업무 자동화", "프레젠테이션 스킬", "회계 기초", "중국어 비즈니스", "AI 활용 실무"));

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
            new Mail("고객 문의", "가온커머스 이대리", "세금계산서 발행 요청", "지난달 거래분 세금계산서가 아직 안 왔습니다. 확인 부탁드립니다."),
            new Mail("고객 문의", "푸른상사 정팀장", "샘플 발송 가능할까요?", "신제품 샘플을 두 박스 정도 받아 볼 수 있을지 문의드립니다."),
            new Mail("고객 문의", "누리테크 기술지원", "설치 일정 조율 요청", "장비 설치 날짜를 다음 주 화요일로 옮길 수 있을까요?"),
            new Mail("고객 문의", "별빛무역 최부장", "대량 주문 할인 문의", "이번 분기에 평소의 세 배를 주문하려 합니다. 할인 조건이 있을까요?"),
            new Mail("고객 문의", "한결제약 구매담당", "납품 서류 누락 건", "어제 받은 물품에 시험 성적서가 빠져 있었습니다. 다시 보내 주세요."),
            new Mail("고객 문의", "은하인쇄 오실장", "결제 조건 변경 요청", "대금 결제를 60일에서 30일로 당길 수 있는지 여쭙니다."),
            new Mail("고객 문의", "초록농산 고객지원", "주문 수량 정정 부탁드립니다", "어제 넣은 주문서의 수량이 100개가 아니라 10개입니다. 정정 부탁드려요."),
            new Mail("고객 문의", "대양해운 영업팀", "통관 서류 요청", "수출 건 통관에 필요한 원산지 증명서를 보내 주실 수 있을까요?"),
            new Mail("사내 공지", "인사팀", "[공지] 연말 건강검진 일정 안내", "올해 건강검진 마감은 12월 20일입니다. 미수검자는 기한 내 예약 바랍니다."),
            new Mail("사내 공지", "총무팀", "[공지] 3층 회의실 공사 안내", "다음 주 월~수 3층 회의실이 공사로 사용 불가합니다."),
            new Mail("사내 공지", "보안팀", "[필독] 비밀번호 변경 주기 안내", "사내 시스템 비밀번호는 90일마다 바꿔야 합니다. 이번 주 안에 변경해 주세요."),
            new Mail("사내 공지", "대표이사실", "송년회 일정 공유", "올해 송년회는 12월 26일 저녁 본사 강당에서 진행합니다."),
            new Mail("사내 공지", "총무팀", "[공지] 주차장 도색 작업", "이번 토요일 지하 주차장 도색 작업으로 주차가 불가합니다."),
            new Mail("사내 공지", "인사팀", "[공지] 연차 사용 촉진 안내", "남은 연차는 12월 31일까지 사용해 주세요. 이월되지 않습니다."),
            new Mail("사내 공지", "IT지원팀", "[공지] 메일 서버 점검", "오늘 밤 11시부터 1시간 동안 메일 송수신이 중단됩니다."),
            new Mail("사내 공지", "경영지원팀", "[공지] 법인카드 사용 기준 변경", "다음 달부터 법인카드 1회 사용 한도가 50만 원으로 바뀝니다."),
            new Mail("사내 공지", "사내 동호회", "[안내] 풋살 동호회 신규 회원 모집", "매주 목요일 저녁 풋살장에서 함께 뛰실 분을 모집합니다."),
            new Mail("사내 공지", "총무팀", "[공지] 탕비실 정수기 교체", "내일 오전 탕비실 정수기를 새 제품으로 교체합니다."),
            new Mail("사내 공지", "안전관리팀", "[공지] 소방 대피 훈련 실시", "금요일 오후 2시 전 직원 소방 대피 훈련이 있습니다."),
            new Mail("사내 공지", "인사팀", "[공지] 하반기 승진 대상자 발표", "하반기 승진 대상자 명단을 인트라넷에 게시했습니다."),
            new Mail("결재 요청", "이주임", "[결재 요청] 신규 거래처 등록", "누리테크를 신규 거래처로 등록하려 합니다. 첨부 서류 확인 후 결재 부탁드립니다."),
            new Mail("결재 요청", "최과장", "[결재 요청] 12월 마케팅 예산안", "다음 달 온라인 광고 예산안을 올립니다. 검토 후 승인 부탁드립니다."),
            new Mail("결재 요청", "정대리", "[결재 요청] 연차 사용 신청", "12월 24일 연차 사용을 신청합니다. 업무 인수인계는 완료했습니다."),
            new Mail("결재 요청", "박사원", "[결재 요청] 법인카드 사용 내역 확인", "지난달 법인카드 사용 내역입니다. 확인 후 결재 부탁드립니다."),
            new Mail("결재 요청", "윤대리", "[결재 요청] 외부 강사 섭외비", "다음 달 사내 교육 강사료 품의를 올립니다. 검토 부탁드립니다."),
            new Mail("결재 요청", "강과장", "[결재 요청] 협력사 계약 연장", "물류 협력사와 계약을 1년 연장하려 합니다. 결재 부탁드립니다."),
            new Mail("결재 요청", "오주임", "[결재 요청] 재택근무 신청", "다음 주 수요일 재택근무를 신청합니다. 승인 부탁드립니다."),
            new Mail("결재 요청", "서사원", "[결재 요청] 소프트웨어 라이선스 구매", "디자인 프로그램 라이선스 3개 구매 품의입니다."),
            new Mail("결재 요청", "조대리", "[결재 요청] 출장 계획서", "다음 주 부산 출장 계획서를 올립니다. 확인 부탁드립니다."),
            new Mail("결재 요청", "장차장", "[결재 요청] 하반기 채용 계획", "하반기 신입 채용 인원과 일정안입니다. 검토 후 결재 바랍니다."),
            new Mail("결재 요청", "신주임", "[결재 요청] 고객 사은품 구매", "연말 고객 사은품 200개 구매 품의를 올립니다."),
            new Mail("결재 요청", "임사원", "[결재 요청] 야근 식대 정산", "이번 달 팀 야근 식대 정산서를 올립니다. 결재 부탁드립니다."),
            new Mail("스팸", "당첨 안내 <win@lucky-event.biz>", "축하합니다! 최신 스마트폰에 당첨되셨습니다", "아래 링크에서 개인정보를 입력하시면 바로 배송해 드립니다."),
            new Mail("스팸", "투자의신", "원금 보장 주식 리딩방 무료 초대", "지금 들어오시면 수익률 300% 종목을 공짜로 알려 드립니다."),
            new Mail("스팸", "택배 알림 <dl1very@notice.top>", "[긴급] 배송 주소가 확인되지 않습니다", "주소 확인을 위해 첨부 파일을 열고 결제 정보를 입력해 주세요."),
            new Mail("스팸", "해외 왕자", "유산 상속 협조 요청", "막대한 유산을 옮기는 데 귀하의 계좌가 필요합니다. 수수료만 보내 주세요."),
            new Mail("스팸", "보안센터 <security@rnicrosoft.co>", "계정이 잠겼습니다", "24시간 안에 아래 링크에서 비밀번호를 다시 입력하지 않으면 계정이 삭제됩니다."),
            new Mail("스팸", "대출 상담사", "무직자도 당일 5천만 원 대출", "신용 조회 없이 바로 입금해 드립니다. 지금 답장 주세요."),
            new Mail("스팸", "국세청 환급 <refund@nts-gov.cc>", "미수령 환급금 38만 원 안내", "환급을 받으려면 카드 번호와 비밀번호를 입력해 주세요."),
            new Mail("스팸", "다이어트 전문가", "한 달에 10kg 빠지는 비밀", "병원에서도 알려 주지 않는 방법, 지금 구매하면 반값입니다."),
            new Mail("스팸", "코인 리치", "비트코인 10배 보장 이벤트", "오늘만 입금액의 두 배를 돌려 드립니다. 서두르세요."),
            new Mail("스팸", "명품 아울렛", "정품 명품가방 90% 할인", "재고 처리로 단 하루만 진행합니다. 링크를 눌러 확인하세요."),
            new Mail("스팸", "회사 대표 <ceo.urgent@gmail.co>", "급히 상품권 구매 부탁", "회의 중이라 전화가 어렵습니다. 상품권 50만 원어치 사서 번호만 보내 주세요."),
            new Mail("스팸", "쇼핑몰 고객센터 <cs@shopp1ng.xyz>", "결제가 승인되었습니다 (₩1,290,000)", "본인이 결제하지 않았다면 첨부된 프로그램을 설치해 취소하세요."));

    private static Made mail(RandomGenerator random, Deque<Integer> mailDeck) {
        if (mailDeck.isEmpty()) {
            var order = new ArrayList<Integer>();
            for (int i = 0; i < MAILS.size(); i++) order.add(i);
            java.util.Collections.shuffle(order, random);
            mailDeck.addAll(order);
        }
        var mail = MAILS.get(mailDeck.poll());
        var data = new LinkedHashMap<String, Object>();
        data.put("from", mail.from());
        data.put("subject", mail.subject());
        data.put("body", mail.body());
        data.put("folders", FOLDERS);
        return new Made("mail", "메일 · 받은편지함 정리", data, mail.folder(),
                "다른 폴더가 더 맞습니다. 보낸 사람과 내용을 다시 보세요.");
    }
}
