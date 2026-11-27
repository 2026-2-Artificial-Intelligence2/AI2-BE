-- 비밀번호를 잊었을 때 쓰는 복구 코드(BCrypt 해시). 가입 때 한 번 보여 주고, 쓰면 새 코드로 바뀐다.
ALTER TABLE profile ADD COLUMN recovery_hash VARCHAR(100);

-- 판에서 몇 위였는지(같은 금액이면 같은 순위). 이 열을 넣기 전 결과는 비어 있다.
ALTER TABLE game_result ADD COLUMN rank INTEGER;
