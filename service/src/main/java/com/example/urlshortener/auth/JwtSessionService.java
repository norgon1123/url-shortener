package com.example.urlshortener.auth;

import com.example.urlshortener.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies session tokens.
 *
 * <p>Both ports live on one component because they are two halves of one
 * decision -- the key material. Splitting them would mean two beans holding the
 * same keypair, and a mismatch between them is not something a test would
 * notice until sign-in silently stopped verifying.
 *
 * <p>Ed25519, so verification needs only the public key: any other service can
 * check a token locally and no request causes a call back to a login system.
 *
 * <p><strong>The ephemeral-key trap.</strong> When neither PEM is configured the
 * service generates a keypair at startup and logs a warning. That is correct for
 * one instance and for tests, and wrong for replicas -- a token issued by one
 * instance will not verify on another, and every session dies at each restart. A
 * deployment with more than one instance must supply {@code app.session.*-key-pem}
 * from the environment.
 */
@Component
public class JwtSessionService implements JwtIssuer, JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtSessionService.class);

    private static final String KEY_ALGORITHM = "Ed25519";
    private static final String EMAIL_CLAIM = "email";

    private final String issuer;
    private final Duration ttl;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public JwtSessionService(AppProperties properties) {
        AppProperties.Session session = properties.session();
        this.issuer = session.issuer();
        this.ttl = session.ttl();

        if (session.privateKeyPem().isBlank() || session.publicKeyPem().isBlank()) {
            KeyPair generated = generateKeyPair();
            this.privateKey = generated.getPrivate();
            this.publicKey = generated.getPublic();
            log.warn("No session signing key configured; generated an ephemeral {} keypair. Tokens issued by this "
                    + "instance will not verify on any other instance and will not survive a restart. Set "
                    + "app.session.private-key-pem and app.session.public-key-pem for any deployment with "
                    + "more than one instance.", KEY_ALGORITHM);
        } else {
            this.privateKey = readPrivateKey(session.privateKeyPem());
            this.publicKey = readPublicKey(session.publicKeyPem());
        }
    }

    @Override
    public IssuedSession issue(UUID customerId, String email) {
        // A JWT expiry is a whole number of seconds. Truncating before we build the
        // token keeps the instant we advertise and the instant the token actually
        // expires the same value, rather than up to a second apart.
        Instant expiresAt = Instant.now().plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(customerId.toString())
                .claim(EMAIL_CLAIM, email)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiresAt))
                .signWith(privateKey, Jwts.SIG.EdDSA)
                .compact();
        return new IssuedSession(token, expiresAt);
    }

    @Override
    public Optional<CurrentCustomer> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new CurrentCustomer(
                    UUID.fromString(claims.getSubject()), claims.get(EMAIL_CLAIM, String.class)));
        } catch (RuntimeException rejected) {
            // Expired, forged, malformed and issued-by-someone-else all end here and
            // all produce the same 401. The reason is deliberately not propagated:
            // telling a caller which of those it was lets them find out which tokens
            // are close to valid.
            log.debug("Rejected a session token: {}", rejected.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(KEY_ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate an " + KEY_ALGORITHM + " session signing key", e);
        }
    }

    private static PrivateKey readPrivateKey(String pem) {
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(decode(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("app.session.private-key-pem is not a PKCS#8 " + KEY_ALGORITHM + " key", e);
        }
    }

    private static PublicKey readPublicKey(String pem) {
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(decode(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("app.session.public-key-pem is not an X.509 " + KEY_ALGORITHM + " key", e);
        }
    }

    private static byte[] decode(String pem) {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
