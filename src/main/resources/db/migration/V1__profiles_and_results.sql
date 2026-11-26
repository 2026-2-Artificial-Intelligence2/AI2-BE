-- 프로필: 이름과 꾸미기. 비밀 열쇠는 원문이 아니라 SHA-256 해시만 둔다.
CREATE TABLE profile (
    id              UUID PRIMARY KEY,
    name            VARCHAR(10)  NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    customization   TEXT         NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
-- 이름은 대소문자를 가리지 않고 겹치지 않게 한다.
CREATE UNIQUE INDEX profile_name_lower ON profile (LOWER(name));

-- 판 결과: 퇴근 정산 때 프로필이 있는 사람마다 한 줄.
CREATE TABLE game_result (
    id                  BIGSERIAL PRIMARY KEY,
    profile_id          UUID         NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    mode                VARCHAR(8)   NOT NULL,      -- solo, multi
    session_seconds     INTEGER      NOT NULL,
    starting_cash_won   BIGINT       NOT NULL,
    net_worth_won       BIGINT       NOT NULL,
    return_percent      DOUBLE PRECISION NOT NULL,
    bankrupt            BOOLEAN      NOT NULL,
    player_count        INTEGER      NOT NULL,
    room_code           VARCHAR(6)   NOT NULL,
    finished_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX game_result_board ON game_result (mode, session_seconds, profile_id);
CREATE INDEX game_result_profile ON game_result (profile_id, finished_at DESC);
