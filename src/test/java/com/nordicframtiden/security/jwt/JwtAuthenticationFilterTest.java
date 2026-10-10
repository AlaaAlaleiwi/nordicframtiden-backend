package com.nordicframtiden.security.jwt;

import jakarta.servlet.http.Cookie;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.Permission;
import com.nordicframtiden.security.model.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessTokenCookieIsNeverAcceptedAsAuthentication() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.setCookies(new Cookie("ACCESS_TOKEN", "cookie-token"));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
        verifyNoInteractions(userRepository);
    }

    @Test
    void bearerTokenForDisabledAccountIsRejected() throws Exception {
        JwtService jwtService = new JwtService(
            "test-only-key-that-is-at-least-32-bytes", "test-issuer", 60);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        AppUser disabled = new AppUser();
        disabled.setId(1L);
        disabled.setUsername("disabled-user");
        String token = jwtService.generateAccessToken(disabled, Map.of());
        disabled.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(disabled));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void bearerTokenUsesCurrentRolesAndPermissionsFromActiveAccount() throws Exception {
        JwtService jwtService = new JwtService(
            "test-only-key-that-is-at-least-32-bytes", "test-issuer", 60);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        AppUser active = new AppUser();
        active.setId(2L);
        active.setUsername("active-user");
        active.setEnabled(true);
        active.setRoles(Set.of(Role.STAFF));
        active.setPermissions(Set.of(Permission.SALARIES));
        when(userRepository.findById(2L)).thenReturn(Optional.of(active));
        String token = jwtService.generateAccessToken(active, Map.of(
            "roles", Set.of("ADMIN"),
            "perms", Set.of("PEOPLE")));
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/salaries/month");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
            .extracting("authority")
            .containsExactlyInAnyOrder("ROLE_STAFF", "PERM_SALARIES");
    }

    @Test
    void websocketAcceptsBearerTokenFromSubprotocolHeader() throws Exception {
        JwtService jwtService = new JwtService(
            "test-only-key-that-is-at-least-32-bytes", "test-issuer", 60);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        AppUser active = new AppUser();
        active.setId(3L);
        active.setUsername("active-user");
        active.setEnabled(true);
        active.setRoles(Set.of(Role.USER));
        when(userRepository.findById(3L)).thenReturn(Optional.of(active));
        String token = jwtService.generateAccessToken(active, Map.of());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/chat");
        request.addHeader("Sec-WebSocket-Protocol", "bearer, " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("active-user");
    }

    @Test
    void restEndpointNeverAcceptsTokenFromWebsocketSubprotocolHeader() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/rooms");
        request.addHeader("Sec-WebSocket-Protocol", "bearer, token-value");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
        verifyNoInteractions(userRepository);
    }

    // ----- Server-side revocation -----

    private static final String SECRET = "test-only-key-that-is-at-least-32-bytes";

    private static AppUser user(long id, String username) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash("hash-1");
        user.setRoles(Set.of(Role.USER));
        return user;
    }

    private static boolean authenticates(JwtService jwtService, AppUserRepository users, String token)
            throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + token);
        new JwtAuthenticationFilter(jwtService, users)
            .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    @Test
    void passwordChangeRevokesEarlierTokens() throws Exception {
        JwtService jwtService = new JwtService(SECRET, "test-issuer", 60);
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser anna = user(7L, "anna.svensson");
        when(users.findById(7L)).thenReturn(Optional.of(anna));
        String stolen = jwtService.generateAccessToken(anna, Map.of());
        assertThat(authenticates(jwtService, users, stolen)).isTrue();

        anna.setPasswordHash("hash-2");

        assertThat(authenticates(jwtService, users, stolen)).isFalse();
        assertThat(authenticates(jwtService, users, jwtService.generateAccessToken(anna, Map.of()))).isTrue();
    }

    @Test
    void tokenOfADeletedAccountNeverAuthenticatesANewAccountWithTheSameUsername() throws Exception {
        JwtService jwtService = new JwtService(SECRET, "test-issuer", 60);
        AppUserRepository users = mock(AppUserRepository.class);
        String oldToken = jwtService.generateAccessToken(user(7L, "anna.svensson"), Map.of());
        // Account 7 was deleted; a new hire got the same generated username.
        when(users.findById(7L)).thenReturn(Optional.empty());
        when(users.findByUsername("anna.svensson")).thenReturn(Optional.of(user(8L, "anna.svensson")));

        assertThat(authenticates(jwtService, users, oldToken)).isFalse();
    }

    @Test
    void renamedAccountKeepsItsSessionUnderTheCurrentUsername() throws Exception {
        JwtService jwtService = new JwtService(SECRET, "test-issuer", 60);
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser anna = user(7L, "anna.svensson");
        String token = jwtService.generateAccessToken(anna, Map.of());
        anna.setUsername("anna.berg");
        when(users.findById(7L)).thenReturn(Optional.of(anna));

        assertThat(authenticates(jwtService, users, token)).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("anna.berg");
    }

    @Test
    void legacyTokenWithoutIdAndVersionClaimsIsRejected() throws Exception {
        JwtService jwtService = new JwtService(SECRET, "test-issuer", 60);
        AppUserRepository users = mock(AppUserRepository.class);
        java.time.Instant now = java.time.Instant.now();
        String legacy = io.jsonwebtoken.Jwts.builder()
            .setIssuer("test-issuer")
            .setSubject("anna.svensson")
            .setIssuedAt(java.util.Date.from(now))
            .setExpiration(java.util.Date.from(now.plusSeconds(600)))
            .claim("type", "access")
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)), io.jsonwebtoken.SignatureAlgorithm.HS256)
            .compact();

        assertThat(authenticates(jwtService, users, legacy)).isFalse();
    }
}
