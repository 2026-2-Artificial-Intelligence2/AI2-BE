package game.officestock.profile;

import game.officestock.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 회원 가입·로그인·자동 로그인·로그아웃·잠금과 비회원 닉네임. 실제 PostgreSQL에서 돈다. */
@SpringBootTest
@Import(TestDatabase.class)
class ProfileServiceTest {
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    ProfileService profiles;

    private static String newName() {
        return "회원" + SEQ.incrementAndGet();
    }

    @Test
    void 가입하면_열쇠를_주고_그_열쇠로_자동_로그인한다() {
        var name = newName();
        var session = profiles.signup(name, "secret1");
        assertThat(session.token()).isNotBlank();
        assertThat(profiles.resume(session.token()).name()).isEqualTo(name);
    }

    @Test
    void 이름은_대소문자를_가리지_않고_하나뿐이다() {
        profiles.signup("Alice" + SEQ.incrementAndGet(), "secret1");
        var taken = "Bob" + SEQ.incrementAndGet();
        profiles.signup(taken, "secret1");
        assertThatThrownBy(() -> profiles.signup(taken.toLowerCase(), "secret2")).hasMessageContaining("이미 있는 이름");
    }

    @Test
    void 이름과_비밀번호_규칙() {
        assertThatThrownBy(() -> profiles.signup("김", "secret1")).hasMessageContaining("2~10자");
        assertThatThrownBy(() -> profiles.signup("김 대리", "secret1")).hasMessageContaining("공백");
        assertThatThrownBy(() -> profiles.signup("P7", "secret1")).hasMessageContaining("P1, P2");
        assertThatThrownBy(() -> profiles.signup(newName(), "12345")).hasMessageContaining("비밀번호");
    }

    @Test
    void 다른_기기에서_비밀번호로_로그인하면_같은_프로필과_꾸미기가_온다() {
        var name = newName();
        var first = profiles.signup(name, "pass word 1");
        profiles.customize(first.profile().id(), "{\"hat\":\"red\"}");
        var second = profiles.login(name, "pass word 1");
        assertThat(second.profile().id()).isEqualTo(first.profile().id());
        assertThat(second.profile().customization().get("hat").asText()).isEqualTo("red");
        assertThat(second.token()).isNotEqualTo(first.token());
    }

    @Test
    void 로그아웃은_그_기기의_열쇠만_지운다() {
        var name = newName();
        var phone = profiles.signup(name, "secret1");
        var laptop = profiles.login(name, "secret1");
        profiles.logout(laptop.token());
        assertThatThrownBy(() -> profiles.resume(laptop.token())).hasMessageContaining("로그인이 풀렸습니다");
        assertThat(profiles.resume(phone.token()).name()).isEqualTo(name);
    }

    @Test
    void 비밀번호를_5번_틀리면_맞아도_잠깐_막힌다() {
        var name = newName();
        profiles.signup(name, "secret1");
        for (int i = 0; i < 4; i++)
            assertThatThrownBy(() -> profiles.login(name, "wrong")).hasMessageContaining("맞지 않습니다");
        assertThatThrownBy(() -> profiles.login(name, "wrong")).hasMessageContaining("맞지 않습니다");
        assertThatThrownBy(() -> profiles.login(name, "secret1")).hasMessageContaining("1분 뒤");
    }

    @Test
    void 비밀번호를_바꾸면_새_비밀번호로만_로그인된다() {
        var name = newName();
        var session = profiles.signup(name, "secret1");
        profiles.changePassword(session.profile().id(), "secret2");
        assertThatThrownBy(() -> profiles.login(name, "secret1")).hasMessageContaining("맞지 않습니다");
        assertThat(profiles.login(name, "secret2").profile().name()).isEqualTo(name);
    }

    @Test
    void 이름을_바꾸면_새_이름으로_로그인하고_옛_이름은_비어진다() {
        var oldName = newName();
        var session = profiles.signup(oldName, "secret1");
        var newName = newName();
        profiles.rename(session.profile().id(), newName);
        assertThat(profiles.login(newName, "secret1").profile().id()).isEqualTo(session.profile().id());
        assertThat(profiles.signup(oldName, "secret9").profile().name()).isEqualTo(oldName);
    }

    @Test
    void 꾸미기는_JSON_객체만_받는다() {
        var session = profiles.signup(newName(), "secret1");
        assertThatThrownBy(() -> profiles.customize(session.profile().id(), "[1,2]")).hasMessageContaining("JSON 객체");
        assertThatThrownBy(() -> profiles.customize(session.profile().id(), "{")).hasMessageContaining("JSON 객체");
        assertThatThrownBy(() -> profiles.customize(session.profile().id(), "{\"a\":\"" + "x".repeat(2_000) + "\"}"))
                .hasMessageContaining("너무 깁니다");
    }

    @Test
    void 비회원_닉네임은_회원_이름과_겹칠_수_없다() {
        var name = newName();
        profiles.signup(name, "secret1");
        assertThatThrownBy(() -> profiles.checkGuestName(name)).hasMessageContaining("회원이 쓰는 이름");
        profiles.checkGuestName("손님" + SEQ.incrementAndGet());
    }

    @Test
    void 가입하면_복구_코드를_주고_그_코드로_비밀번호를_되찾는다() {
        var name = newName();
        var first = profiles.signup(name, "secret1");
        var code = first.recoveryCode();
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");

        // 옮겨 적다 소문자·공백이 섞여도 된다.
        var reset = profiles.resetPassword(name, code.toLowerCase().replace("-", " "), "new pass 1");
        assertThat(reset.profile().id()).isEqualTo(first.profile().id());
        assertThat(reset.recoveryCode()).isNotEqualTo(code);
        assertThat(profiles.login(name, "new pass 1").profile().id()).isEqualTo(first.profile().id());
        assertThatThrownBy(() -> profiles.login(name, "secret1")).hasMessageContaining("맞지 않습니다");
        // 다른 기기의 로그인은 풀리고, 쓴 코드는 다시 쓸 수 없다.
        assertThatThrownBy(() -> profiles.resume(first.token())).hasMessageContaining("로그인이 풀렸습니다");
        assertThat(profiles.resume(reset.token()).name()).isEqualTo(name);
        assertThatThrownBy(() -> profiles.resetPassword(name, code, "another1")).hasMessageContaining("복구 코드");
        assertThat(profiles.resetPassword(name, reset.recoveryCode(), "another1").profile().name()).isEqualTo(name);
    }

    @Test
    void 복구_코드를_새로_만들면_예전_코드는_못_쓴다() {
        var name = newName();
        var session = profiles.signup(name, "secret1");
        var renewed = profiles.renewRecoveryCode(session.profile().id());
        assertThatThrownBy(() -> profiles.resetPassword(name, session.recoveryCode(), "secret2")).hasMessageContaining("복구 코드");
        assertThat(profiles.resetPassword(name, renewed, "secret2").profile().name()).isEqualTo(name);
    }

    @Test
    void 탈퇴는_비밀번호를_확인하고_로그인을_모두_지운다() {
        var name = newName();
        var session = profiles.signup(name, "secret1");
        assertThatThrownBy(() -> profiles.delete(session.profile().id(), "wrong!!")).hasMessageContaining("비밀번호");
        profiles.delete(session.profile().id(), "secret1");
        assertThatThrownBy(() -> profiles.login(name, "secret1")).hasMessageContaining("맞지 않습니다");
        assertThatThrownBy(() -> profiles.resume(session.token())).hasMessageContaining("로그인이 풀렸습니다");
        // 이름이 비어 다른 사람이 쓸 수 있다.
        assertThat(profiles.signup(name, "secret9").profile().name()).isEqualTo(name);
    }
}
