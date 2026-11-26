package game.officestock.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 프로필 만들기·찾기·이름 바꾸기·꾸미기. 로그인 대신 처음 이름을 정할 때 비밀 열쇠를 주고,
 * 브라우저가 그 열쇠를 보관했다가 다음 접속 때 보낸다. 서버에는 열쇠의 해시만 남긴다.
 */
@Service
public class ProfileService {
    /** 한글·영문·숫자·밑줄·하이픈 2~10자. 공백은 안 된다(메신저 명령에서 이름을 공백으로 나눈다). */
    private static final Pattern NAME = Pattern.compile("[가-힣A-Za-z0-9_-]{2,10}");
    /** 이름이 없는 사람에게 붙는 P1, P2… 와 헷갈리지 않게 막는다. */
    private static final Pattern RESERVED = Pattern.compile("[Pp][0-9]+");
    private static final int MAX_CUSTOMIZATION_CHARS = 2_000;

    /** 화면에 보낼 프로필. customization은 JSON 객체. */
    public record View(UUID id, String name, JsonNode customization) {}

    public record Created(View profile, String token) {}

    private final ProfileRepository profiles;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    ProfileService(ProfileRepository profiles, ObjectMapper json) {
        this.profiles = profiles;
        this.json = json;
    }

    @Transactional
    public Created create(String name) {
        checkName(name);
        var bytes = new byte[24];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var profile = new Profile(name, hash(token), Instant.now());
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        }
        return new Created(view(profile), token);
    }

    @Transactional(readOnly = true)
    public View login(String token) {
        return profiles.findByTokenHash(hash(token)).map(this::view)
                .orElseThrow(() -> new IllegalArgumentException("프로필을 찾을 수 없습니다. 이름을 다시 정하세요."));
    }

    @Transactional
    public View rename(UUID id, String name) {
        checkName(name);
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("프로필이 없습니다."));
        if (profile.getName().equals(name)) return view(profile);
        profile.rename(name, Instant.now());
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
        }
        return view(profile);
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
        var profile = profiles.findById(id).orElseThrow(() -> new IllegalStateException("프로필이 없습니다."));
        profile.customize(node.toString(), Instant.now());
        return view(profile);
    }

    private void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("이름은 한글·영문·숫자·_·- 로 2~10자여야 합니다(공백 없이).");
        if (RESERVED.matcher(name).matches())
            throw new IllegalArgumentException("P1, P2 같은 이름은 쓸 수 없습니다.");
        if (profiles.existsByNameIgnoreCase(name)) throw new IllegalArgumentException("이미 있는 이름입니다: " + name);
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
