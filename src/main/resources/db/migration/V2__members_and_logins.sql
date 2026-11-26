-- 회원: 이름 + 비밀번호(BCrypt 해시). V1에서 열쇠만으로 만든 프로필은 비밀번호가 없을 수 있다.
ALTER TABLE profile ADD COLUMN password_hash VARCHAR(100);

-- 자동 로그인 열쇠. 기기(브라우저)마다 하나씩 생기므로 프로필 하나에 여러 개가 붙는다.
CREATE TABLE login_token (
    token_hash      VARCHAR(64)  PRIMARY KEY,
    profile_id      UUID         NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ  NOT NULL,
    last_used_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX login_token_profile ON login_token (profile_id);

INSERT INTO login_token (token_hash, profile_id, created_at, last_used_at)
SELECT token_hash, id, created_at, updated_at FROM profile;

ALTER TABLE profile DROP COLUMN token_hash;
