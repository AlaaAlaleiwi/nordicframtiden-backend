package com.nordicframtiden.security.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.repo.AppUserRepository;

@Component
public class JwtService {

  private final Key key;
  private final String issuer;
  private final long accessTokenMinutes;
  private final long refreshTokenDays;

  @org.springframework.beans.factory.annotation.Autowired
  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.issuer}") String issuer,
      @Value("${app.jwt.accessTokenMinutes}") long accessTokenMinutes,
      @Value("${app.jwt.refreshTokenDays:30}") long refreshTokenDays
  ) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.issuer = issuer;
    this.accessTokenMinutes = accessTokenMinutes;
    this.refreshTokenDays = refreshTokenDays;
  }

  public JwtService(String secret, String issuer, long accessTokenMinutes) {
    this(secret, issuer, accessTokenMinutes, 30);
  }

  /** Claim holding the account id: usernames can be reused, ids never are. */
  static final String USER_ID_CLAIM = "uid";
  /** Claim holding {@link AppUser#getTokenVersion()} at issue time. */
  static final String TOKEN_VERSION_CLAIM = "tv";

  public String generateAccessToken(AppUser user, Map<String, Object> claims) {
    Instant now = Instant.now();
    Instant exp = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);

    return Jwts.builder()
        .setIssuer(issuer)
        .setSubject(user.getUsername())
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(exp))
        .addClaims(claims) // roles + perms go here
        .claim(USER_ID_CLAIM, user.getId())
        .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
        .claim("type", "access")
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
  }

  public String generateRefreshToken(AppUser user) {
    Instant now = Instant.now();
    return Jwts.builder()
        .setIssuer(issuer)
        .setSubject(user.getUsername())
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(now.plus(refreshTokenDays, ChronoUnit.DAYS)))
        .claim(USER_ID_CLAIM, user.getId())
        .claim(TOKEN_VERSION_CLAIM, user.getTokenVersion())
        .claim("type", "refresh")
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
  }

  /**
   * The active account a validated token belongs to, or empty when the
   * account is gone or disabled, or the token predates its last revocation
   * (password change, disable, "log out everywhere"). Tokens without the id
   * and version claims (issued before revocation existed) are rejected.
   */
  public Optional<AppUser> currentUser(Claims claims, AppUserRepository users) {
    Long userId = claims.get(USER_ID_CLAIM, Long.class);
    Integer version = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
    if (userId == null || version == null) {
      return Optional.empty();
    }
    return users.findById(userId)
        .filter(AppUser::isEnabled)
        .filter(user -> user.getTokenVersion() == version);
  }

  public Jws<Claims> parse(String token) throws JwtException {
    return Jwts.parserBuilder()
        .requireIssuer(issuer)
        .setSigningKey(key)
        .build()
        .parseClaimsJws(token);
  }

  public String getSubject(String token) {
    return parse(token).getBody().getSubject();
  }

  public Claims validateAccessToken(String token) {
    Claims claims = parse(token).getBody();
    if (!"access".equals(claims.get("type"))) {
      throw new JwtException("Token is not an access token");
    }
    return claims;
  }

  public Claims validateRefreshToken(String token) {
    Claims claims = parse(token).getBody();
    if (!"refresh".equals(claims.get("type"))) {
      throw new JwtException("Token is not a refresh token");
    }
    return claims;
  }
}
