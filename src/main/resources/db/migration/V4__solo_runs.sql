-- 로그인 없이: 혼자 하기 판은 누가 했든 이름 없이 한 줄씩 남기고(상위 몇 %인지 계산용),
-- 퇴근 화면에서 "명예의 전당에 올리기"를 고르면 이름과 기기 꼬리표를 붙인다.
CREATE TABLE solo_run (
    id                BIGSERIAL PRIMARY KEY,
    session_seconds   INTEGER          NOT NULL,
    starting_cash_won BIGINT           NOT NULL,
    net_worth_won     BIGINT           NOT NULL,
    return_percent    DOUBLE PRECISION NOT NULL,
    bankrupt          BOOLEAN          NOT NULL,
    finished_at       TIMESTAMPTZ      NOT NULL,
    -- 올렸을 때만 채운다. device_hash는 기기 열쇠의 SHA-256(원문은 저장하지 않는다), tag는 그 앞 네 자리 숫자.
    name              VARCHAR(10),
    tag               VARCHAR(4),
    device_hash       VARCHAR(64),
    shared_at         TIMESTAMPTZ
);
CREATE INDEX solo_run_month ON solo_run (session_seconds, finished_at);
