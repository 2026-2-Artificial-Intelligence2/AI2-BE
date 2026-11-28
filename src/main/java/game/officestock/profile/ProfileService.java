package game.officestock.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 회원 가입·로그인과 프로필(이름·꾸미기). 회원은 이름 + 비밀번호로 어느 기기에서든 로그인하고,
 * 로그인하면 그 기기용 자동 로그인 열쇠를 받는다. 비회원(게스트)은 저장하지 않고 이름 규칙만 확인한다.
 *
 * 이메일을 받지 않으므로 비밀번호를 잊으면 복구 코드로 되찾는다. 복구 코드는 가입 때(또는 내 정보에서 새로 만들 때)
 * 한 번만 보여 주고 해시만 남긴다. 한 번 쓰면 새 코드로 바뀐다.
 */
@Service
public class ProfileService {
    /** 한글·영문·숫자·밑줄·하이픈 2~10자. 공백은 안 된다(메신저 명령에서 이름을 공백으로 나눈다). */
    private static final Pattern NAME = Pattern.compile("[가-힣A-Za-z0-9_-]{2,10}");
    /** 이름이 없는 사람에게 붙는 P1, P2… 와 부장님(매매 기록에 쓰는 이름)과 헷갈리지 않게 막는다. */
    private static final Pattern RESERVED = Pattern.compile("[Pp][0-9]+|부장님?");
    private static final int MIN_PASSWORD = 6, MAX_PASSWORD = 64;
    private static final int MAX_CUSTOMIZATION_CHARS = 2_000;
    /** 같은 이름으로 비밀번호를 이만큼 틀리면 잠깐 막는다. */
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 60_000;

    /** 화면에 보낼 프로필. customization은 JSON 객체. */
    public record View(UUID id, String name, JsonNode customization) {}

    /**
     * 가입·로그인 결과. token은 이 기기의 자동 로그인 열쇠(원문은 이때 한 번만 준다).
     * recoveryCode는 새로 만든 복구 코드(가입·복구 때만, 아니면 null).
     */
    public record Session(View profile, String token, String recoveryCode) {
        Session(View profile, String token) {
            this(profile, token, null);
        }
    }

    /** 복구 코드 글자: 헷갈리는 0/O, 1/I/L을 뺀 대문자·숫자. 16자 = 약 80비트. */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_LENGTH = 16;

    private record Failures(int count, long lockedUntil) {}

    private final ProfileRepository profiles;
    private final LoginTokenRepository tokens;
    private final ObjectMapper json;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    ProfileService(ProfileRepository profiles, LoginTokenRepository tokens, ObjectMapper json) {
        this.profiles = profiles;
        this.tokens = tokens;
        this.json = json;
    }

    @Transactional
    public Session signup(String name, String password) {
        checkName(name);
        if (profiles.existsByNameIgnoreCase(name)) throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        checkPassword(password);
        var profile = new Profile(name, passwords.encode(password), Instant.now());
        var code = newRecoveryCode();
        profile.setRecovery(passwords.encode(normalizeRecovery(code)), Instant.now());
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        }
        return new Session(view(profile), issueToken(profile.getId()), code);
    }

    @Transactional
    public Session login(String name, String password) {
        var profile = checked(name, p -> p.getPasswordHash() != null && passwords.matches(password, p.getPasswordHash()),
                "이름이나 비밀번호가 맞지 않습니다.");
        return new Session(view(profile), issueToken(profile.getId()));
    }

    /**
     * 비밀번호를 잊었을 때: 이름 + 복구 코드로 새 비밀번호를 정한다. 모든 기기의 로그인을 풀고(다른 사람이 쓰고 있었을 수 있다)
     * 이 기기에만 새 열쇠를 준다. 쓴 복구 코드는 버리고 새 코드를 준다. 틀리면 로그인과 같이 5번에 1분 잠긴다.
     */
    @Transactional
    public Session resetPassword(String name, String recoveryCode, String newPassword) {
        checkPassword(newPassword);
        var profile = checked(name, p -> p.getRecoveryHash() != null
                && passwords.matches(normalizeRecovery(recoveryCode), p.getRecoveryHash()),
                "이름이나 복구 코드가 맞지 않습니다.");
        var now = Instant.now();
        profile.changePassword(passwords.encode(newPassword), now);
        var code = newRecoveryCode();
        profile.setRecovery(passwords.encode(normalizeRecovery(code)), now);
        tokens.deleteAllOfProfile(profile.getId());
        return new Session(view(profile), issueToken(profile.getId()), code);
    }

    /** 내 정보에서 복구 코드를 새로 만든다. 예전 코드는 더는 쓸 수 없다. */
    @Transactional
    public String renewRecoveryCode(UUID id) {
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("회원 정보가 없습니다."));
        var code = newRecoveryCode();
        profile.setRecovery(passwords.encode(normalizeRecovery(code)), Instant.now());
        return code;
    }

    /** 회원 탈퇴: 비밀번호를 한 번 더 확인하고 프로필·로그인 열쇠·판 결과를 모두 지운다(DB가 함께 지운다). */
    @Transactional
    public void delete(UUID id, String password) {
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("회원 정보가 없습니다."));
        if (profile.getPasswordHash() == null || !passwords.matches(password, profile.getPasswordHash()))
            throw new IllegalArgumentException("비밀번호가 맞지 않습니다.");
        profiles.delete(profile);
        profiles.flush();
    }

    /** 이름으로 찾아 확인한다. 같은 이름으로 여러 번 틀리면 잠깐 막는다(로그인·복구가 함께 센다). */
    private Profile checked(String name, java.util.function.Predicate<Profile> matches, String wrong) {
        var key = name.toLowerCase();
        var failed = failures.get(key);
        if (failed != null && failed.lockedUntil() > System.currentTimeMillis())
            throw new IllegalStateException("여러 번 틀렸습니다. 1분 뒤에 다시 하세요.");
        var profile = profiles.findByNameIgnoreCase(name).orElse(null);
        if (profile == null || !matches.test(profile)) {
            int count = failed == null ? 1 : failed.count() + 1;
            failures.put(key, count >= MAX_FAILURES
                    ? new Failures(0, System.currentTimeMillis() + LOCK_MILLIS) : new Failures(count, 0));
            throw new IllegalArgumentException(wrong);
        }
        failures.remove(key);
        return profile;
    }

    /** ABCD-EFGH-JKMN-PQRS 꼴. */
    private String newRecoveryCode() {
        var code = new StringBuilder();
        for (int i = 0; i < RECOVERY_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) code.append('-');
            code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return code.toString();
    }

    /** 사람이 옮겨 적은 코드: 대소문자·하이픈·공백을 가리지 않는다. */
    private static String normalizeRecovery(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase();
    }

    /** 이 기기에 보관한 열쇠로 자동 로그인한다. */
    @Transactional
    public View resume(String token) {
        var login = tokens.findById(hash(token))
                .orElseThrow(() -> new IllegalArgumentException("로그인이 풀렸습니다. 다시 로그인하세요."));
        login.touch(Instant.now());
        return profiles.findById(login.getProfileId()).map(this::view)
                .orElseThrow(() -> new IllegalArgumentException("로그인이 풀렸습니다. 다시 로그인하세요."));
    }

    /** 이 기기의 열쇠만 지운다. 다른 기기의 로그인은 그대로다. */
    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) tokens.deleteById(hash(token));
    }

    @Transactional
    public View rename(UUID id, String name) {
        checkName(name);
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("회원 정보가 없습니다."));
        if (profile.getName().equals(name)) return view(profile);
        if (profiles.existsByNameIgnoreCase(name) && !profile.getName().equalsIgnoreCase(name))
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        profile.rename(name, Instant.now());
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        }
        return view(profile);
    }

    @Transactional
    public void changePassword(UUID id, String password) {
        checkPassword(password);
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("회원 정보가 없습니다."));
        profile.changePassword(passwords.encode(password), Instant.now());
    }

    @Transactional
    public View customize(UUID id, String customization) {
        if (customization.length() > MAX_CUSTOMIZATION_CHARS)
            throw new IllegalArgumentException("꾸미기 설정이 너무 깁니다(" + MAX_CUSTOMIZATION_CHARS + "자 이내).");
        JsonNode node;
        try {
            node = json.readTree(customization);
        } catch (Exception e) {
            throw new IllegalArgumentException("꾸미기 설정은 JSON 객체여야 합니다.");
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("꾸미기 설정은 JSON 객체여야 합니다.");
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("회원 정보가 없습니다."));
        profile.customize(node.toString(), Instant.now());
        return view(profile);
    }

    /**
     * 닉네임 규칙만 본다(저장하지 않는다). 로그인 없이 하므로 다른 사람과 이름이 겹쳐도 되고,
     * 명예의 전당에서는 기기 꼬리표(#1234)로 구분한다.
     */
    public void checkGuestName(String name) {
        checkName(name);
    }

    private String issueToken(UUID profileId) {
        var bytes = new byte[24];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new LoginToken(hash(token), profileId, Instant.now()));
        return token;
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("이름은 한글·영문·숫자·_·- 로 2~10자여야 합니다(공백 없이).");
        if (RESERVED.matcher(name).matches())
            throw new IllegalArgumentException("P1, P2나 부장님 같은 이름은 쓸 수 없습니다.");
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD
                || password.isBlank())
            throw new IllegalArgumentException("비밀번호는 " + MIN_PASSWORD + "~" + MAX_PASSWORD + "자로 정하세요.");
    }

    private View view(Profile profile) {
        try {
            return new View(profile.getId(), profile.getName(), json.readTree(profile.getCustomization()));
        } catch (Exception e) {
            return new View(profile.getId(), profile.getName(), json.createObjectNode());
        }
    }

    private static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
