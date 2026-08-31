package com.daou.erdstudio.auth;

import com.daou.erdstudio.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class McpTokenTest {

    @Autowired
    AuthService authService;
    @Autowired
    AppUserRepository appUserRepository;

    private Principal user(String username) {
        AppUser u = appUserRepository.save(new AppUser(username, username, "sub-" + username, false));
        return new Principal(u.getId(), u.getUsername(), u.isSuperAdmin());
    }

    @Test
    void 발급한_토큰으로_계정_주체를_복원한다() {
        Principal me = user("mcpuser1");
        String token = authService.issueMcpToken(me);

        assertThat(token).isNotBlank();
        Principal resolved = authService.resolveMcpToken(token);
        assertThat(resolved).isNotNull();
        assertThat(resolved.userId()).isEqualTo(me.userId());
        // 원문이 아닌 해시만 저장된다.
        assertThat(appUserRepository.findById(me.userId()).orElseThrow().getMcpTokenHash())
                .isNotEqualTo(token).hasSize(64);
    }

    @Test
    void 재발급하면_기존_토큰은_무효가_된다() {
        Principal me = user("mcpuser2");
        String first = authService.issueMcpToken(me);
        String second = authService.issueMcpToken(me);

        assertThat(authService.resolveMcpToken(first)).isNull();
        assertThat(authService.resolveMcpToken(second)).isNotNull();
    }

    @Test
    void 게스트는_발급할_수_없고_미지_토큰은_null_이다() {
        assertThatThrownBy(() -> authService.issueMcpToken(Principal.guest()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(authService.resolveMcpToken("no-such-token")).isNull();
        assertThat(authService.resolveMcpToken(null)).isNull();
    }
}
