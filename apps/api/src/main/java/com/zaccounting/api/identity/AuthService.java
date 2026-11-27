package com.zaccounting.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements account registration, credential authentication, email challenges,
 * password recovery, and durable session lifecycle.
 *
 * <p>Password hashing uses Spring Security's configured encoder. Password work
 * is bounded by a process-wide semaphore, and session and challenge tokens are
 * generated with {@link SecureRandom}. Session and challenge token digests are
 * persisted instead of their raw values.
 *
 * @see AuthController
 * @see IdentityNormalizer
 */
@Service
public class AuthService {

	private static final Duration SESSION_LIFETIME = Duration.ofDays(30);
	private static final Duration EMAIL_TOKEN_LIFETIME = Duration.ofHours(24);
	private static final Duration RESET_TOKEN_LIFETIME = Duration.ofMinutes(30);
	private static final Semaphore PASSWORD_WORK_LIMIT = new Semaphore(2);

	private final JdbcClient jdbcClient;
	private final PasswordEncoder passwordEncoder;
	private final AuthEmailOutbox emailOutbox;
	private final SecureRandom secureRandom;
	private final String dummyPasswordHash;

	/**
	 * Creates the application service and a dummy hash used for unknown-identifier
	 * password checks.
	 *
	 * @param jdbcClient parameterized SQL client for the identity schema
	 * @param passwordEncoder Spring Security password encoder
	 * @param emailOutbox transactional authentication email queue
	 * @param secureRandom secure random source for opaque tokens
	 */
	AuthService(
			JdbcClient jdbcClient,
			PasswordEncoder passwordEncoder,
			AuthEmailOutbox emailOutbox,
			SecureRandom secureRandom) {
		this.jdbcClient = jdbcClient;
		this.passwordEncoder = passwordEncoder;
		this.emailOutbox = emailOutbox;
		this.secureRandom = secureRandom;
		this.dummyPasswordHash = passwordEncoder.encode(randomToken());
	}

	/**
	 * Creates an unverified account and queues its verification email atomically.
	 *
	 * @param request validated registration details
	 * @return the verification challenge created for the account
	 * @throws ApiException when the normalized email or username is already used
	 * @throws ApiException when an identity or password is invalid
	 */
	@Transactional
	public Challenge register(AuthDtos.RegisterRequest request) {
		String email = IdentityNormalizer.email(request.email());
		String username = IdentityNormalizer.username(request.username());
		String displayName = IdentityNormalizer.displayName(request.displayName());
		IdentityNormalizer.validatePassword(request.password());
		String passwordHash = encodePassword(request.password());

		boolean exists = jdbcClient.sql("""
				SELECT EXISTS (
					SELECT 1 FROM app_user
					WHERE email_normalized = :email OR username_normalized = :username
				)
				""")
				.param("email", email)
				.param("username", username)
				.query(Boolean.class)
				.single();
		if (exists) {
			throw new ApiException(ApiErrorCode.ACCOUNT_IDENTIFIER_CONFLICT);
		}

		UUID userId;
		try {
			userId = jdbcClient.sql("""
				INSERT INTO app_user (
					id, email_normalized, username, username_normalized,
					display_name, status, email_verified
				)
				VALUES (
					:userId, :email, :username, :username,
					:displayName, 'ACTIVE', FALSE
				)
				RETURNING id
				""")
				.param("userId", UUID.randomUUID())
				.param("email", email)
				.param("username", username)
				.param("displayName", displayName)
				.query((resultSet, rowNum) -> resultSet.getObject("id", UUID.class))
				.single();
		} catch (DataIntegrityViolationException exception) {
			throw new ApiException(ApiErrorCode.ACCOUNT_IDENTIFIER_CONFLICT, exception);
		}

		jdbcClient.sql("""
				INSERT INTO password_credential (user_id, password_hash)
				VALUES (:userId, :passwordHash)
				""")
				.param("userId", userId)
				.param("passwordHash", passwordHash)
				.update();

		Challenge challenge = issueToken("email_verification_token", userId, EMAIL_TOKEN_LIFETIME);
		emailOutbox.enqueueVerification(challenge.withEmail(email));
		writeSystemAudit("USER_REGISTERED", "APP_USER", userId);
		return challenge.withEmail(email);
	}

	/**
	 * Checks an email-or-username identifier and creates a durable session on success.
	 *
	 * @param request identifier and password supplied by the caller
	 * @return a login result for an eligible account, or empty for rejected credentials
	 * @throws ApiException when concurrent password work is saturated
	 * @throws ApiException when password bounds are invalid
	 */
	@Transactional
	public Optional<LoginResult> login(AuthDtos.LoginRequest request) {
		IdentityNormalizer.validateLoginPassword(request.password());
		String identifier = normalizeIdentifier(request.identifier());
		if (!PASSWORD_WORK_LIMIT.tryAcquire()) {
			throw new ApiException(ApiErrorCode.RATE_LIMITED, 1);
		}
		try {
			return authenticate(request, identifier);
		} finally {
			PASSWORD_WORK_LIMIT.release();
		}
	}

	/**
	 * Performs the row-locked credential check and records failures or creates a session.
	 *
	 * @param request original login request containing the password
	 * @param identifier canonical identifier used for lookup
	 * @return successful login result, or empty when authentication is rejected
	 */
	private Optional<LoginResult> authenticate(AuthDtos.LoginRequest request, String identifier) {

		Optional<Account> account = jdbcClient.sql("""
				SELECT
					u.id, u.email_normalized, u.username, u.display_name, u.status,
					u.email_verified, u.created_at, c.password_hash,
					c.failed_login_count, c.locked_until
				FROM app_user u
				JOIN password_credential c ON c.user_id = u.id
				WHERE u.email_normalized = :identifier
				   OR u.username_normalized = :identifier
				FOR UPDATE OF u, c
				""")
				.param("identifier", identifier)
				.query((resultSet, rowNum) -> Account.from(resultSet))
				.optional();

		if (account.isEmpty()) {
			passwordEncoder.matches(request.password(), dummyPasswordHash);
			return Optional.empty();
		}

		Account user = account.get();
		Instant now = Instant.now();
		boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now);
		if (locked) {
			return Optional.empty();
		}
		boolean passwordMatches = passwordEncoder.matches(request.password(), user.passwordHash());
		if (!passwordMatches) {
			int previousFailures = user.lockedUntil() != null ? 0 : user.failedLoginCount();
			int failedCount = Math.min(5, previousFailures + 1);
			Instant lockedUntil = failedCount == 5 ? now.plus(Duration.ofMinutes(15)) : null;
			jdbcClient.sql("""
					UPDATE password_credential
					SET failed_login_count = :failedCount,
						locked_until = :lockedUntil,
						updated_at = :now
					WHERE user_id = :userId
					""")
					.param("failedCount", failedCount)
					.param("lockedUntil", timestamp(lockedUntil))
					.param("now", timestamp(now))
					.param("userId", user.id())
					.update();
			return Optional.empty();
		}

		if (locked || !"ACTIVE".equals(user.status()) || !user.emailVerified()) {
			return Optional.empty();
		}

		jdbcClient.sql("""
				UPDATE password_credential
				SET failed_login_count = 0, locked_until = NULL, updated_at = :now
				WHERE user_id = :userId
				""")
				.param("now", timestamp(now))
				.param("userId", user.id())
				.update();

		String sessionToken = randomToken();
		Instant expiresAt = now.plus(SESSION_LIFETIME);
		jdbcClient.sql("""
				INSERT INTO auth_session (user_id, token_digest, expires_at)
				VALUES (:userId, :digest, :expiresAt)
				""")
				.param("userId", user.id())
				.param("digest", sha256(sessionToken))
				.param("expiresAt", timestamp(expiresAt))
				.update();
		writeUserAudit(user.id(), "USER_LOGGED_IN", "AUTH_SESSION", null);
		return Optional.of(new LoginResult(user.toResponse(), sessionToken, expiresAt));
	}

	/**
	 * Resolves a raw session token to an active, verified user when the session is valid.
	 *
	 * @param token raw session cookie value
	 * @return the authenticated user's profile, or empty for malformed, expired,
	 *         revoked, or otherwise ineligible sessions
	 */
	Optional<AuthDtos.UserResponse> resolveSession(String token) {
		String digest;
		try {
			digest = sessionDigest(token);
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		return jdbcClient.sql("""
				SELECT u.id, u.email_normalized, u.username, u.display_name,
					u.status, u.created_at
				FROM auth_session s
			JOIN app_user u ON u.id = s.user_id
			WHERE s.token_digest = :digest
			  AND s.revoked_at IS NULL
			  AND s.expires_at > :now
			  AND u.status = 'ACTIVE'
			  AND u.email_verified = TRUE
			""")
				.param("digest", digest)
				.param("now", timestamp(Instant.now()))
				.query((resultSet, rowNum) -> userResponse(resultSet))
				.optional();
	}

	/**
	 * Revokes the session identified by the supplied cookie value.
	 * Missing or malformed values are treated as already signed out.
	 *
	 * @param token raw session cookie value, or {@code null} when absent
	 */
	void logout(String token) {
		if (token == null) {
			return;
		}
		try {
			jdbcClient.sql("""
					UPDATE auth_session
					SET revoked_at = :now
					WHERE token_digest = :digest AND revoked_at IS NULL
					""")
					.param("now", timestamp(Instant.now()))
					.param("digest", sessionDigest(token))
					.update();
		} catch (IllegalArgumentException ignored) {
			// Invalid cookies are treated like an already signed-out session.
		}
	}

	/**
	 * Issues a replacement email verification challenge for an eligible account.
	 *
	 * @param rawEmail submitted email address
	 * @return the challenge when the account exists and needs verification, otherwise empty
	 * @throws ApiException when the address is invalid
	 */
	@Transactional
	public Optional<Challenge> resendVerification(String rawEmail) {
		String email = IdentityNormalizer.email(rawEmail);
		Optional<UUID> userId = jdbcClient.sql("""
				SELECT u.id FROM app_user u
				WHERE u.email_normalized = :email AND u.status = 'ACTIVE' AND u.email_verified = FALSE
				FOR UPDATE
				""")
				.param("email", email)
				.query((resultSet, rowNum) -> resultSet.getObject("id", UUID.class))
				.optional();
		if (userId.isEmpty()) {
			emailOutbox.enqueueNoop();
		}
		return userId.map(id -> {
			Challenge challenge = issueToken("email_verification_token", id, EMAIL_TOKEN_LIFETIME)
					.withEmail(email);
			emailOutbox.enqueueVerification(challenge);
			return challenge;
		});
	}

	/**
	 * Consumes a valid, unexpired, single-use verification token and marks the account verified.
	 *
	 * @param token raw token from the verification link
	 * @throws ApiException when the token is invalid, expired, or already consumed
	 */
	@Transactional
	public void verifyEmail(String token) {
		String digest = tokenDigest("email-verification", token);
		UUID userId = jdbcClient.sql("""
				SELECT user_id FROM email_verification_token
				WHERE token_digest = :digest AND consumed_at IS NULL AND expires_at > :now
				""")
				.param("digest", digest)
				.param("now", timestamp(Instant.now()))
				.query((resultSet, rowNum) -> resultSet.getObject("user_id", UUID.class))
				.optional()
				.orElseThrow(AuthService::invalidToken);
		jdbcClient.sql("SELECT id FROM app_user WHERE id = :userId FOR UPDATE")
				.param("userId", userId)
				.query((resultSet, rowNum) -> resultSet.getObject("id", UUID.class))
				.optional()
				.orElseThrow(AuthService::invalidToken);
		int consumed = jdbcClient.sql("""
				UPDATE email_verification_token SET consumed_at = :now
				WHERE token_digest = :digest AND consumed_at IS NULL AND expires_at > :now
				""")
				.param("digest", digest)
				.param("now", timestamp(Instant.now()))
				.update();
		if (consumed != 1) {
			throw invalidToken();
		}
		int updated = jdbcClient.sql("""
				UPDATE app_user SET email_verified = TRUE, updated_at = :now
				WHERE id = :userId AND status = 'ACTIVE'
				""")
				.param("now", timestamp(Instant.now()))
				.param("userId", userId)
				.update();
		if (updated != 1) {
			throw invalidToken();
		}
		writeSystemAudit("EMAIL_VERIFIED", "APP_USER", userId);
	}

	/**
	 * Queues a password-reset challenge when an eligible account matches the email.
	 * Unknown addresses receive a no-op outbox job to reduce observable differences.
	 *
	 * @param rawEmail submitted account email address
	 * @return the challenge when an eligible account exists, otherwise empty
	 * @throws ApiException when the address is invalid
	 */
	@Transactional
	public Optional<Challenge> requestPasswordReset(String rawEmail) {
		String email = IdentityNormalizer.email(rawEmail);
		Optional<UUID> userId = jdbcClient.sql("""
				SELECT u.id FROM app_user u
				JOIN password_credential c ON c.user_id = u.id
				WHERE u.email_normalized = :email AND u.status = 'ACTIVE'
				FOR UPDATE OF u, c
				""")
				.param("email", email)
				.query((resultSet, rowNum) -> resultSet.getObject("id", UUID.class))
				.optional();
		if (userId.isEmpty()) {
			emailOutbox.enqueueNoop();
		}
		return userId.map(id -> {
			Challenge challenge = issueToken("password_reset_token", id, RESET_TOKEN_LIFETIME)
					.withEmail(email);
			emailOutbox.enqueuePasswordReset(challenge);
			return challenge;
		});
	}

	/**
	 * Consumes a reset token, replaces the password hash, and revokes all user sessions.
	 *
	 * @param request reset token and replacement password
	 * @throws ApiException when the token is invalid, expired, or consumed
	 * @throws ApiException when the replacement password is invalid
	 */
	@Transactional
	public void resetPassword(AuthDtos.PasswordResetRequest request) {
		IdentityNormalizer.validatePassword(request.password());
		String passwordHash = encodePassword(request.password());
		String digest = tokenDigest("password-reset", request.token());
		UUID userId = jdbcClient.sql("""
				SELECT user_id FROM password_reset_token
				WHERE token_digest = :digest AND consumed_at IS NULL AND expires_at > :now
				""")
				.param("digest", digest)
				.param("now", timestamp(Instant.now()))
				.query((resultSet, rowNum) -> resultSet.getObject("user_id", UUID.class))
				.optional()
				.orElseThrow(AuthService::invalidToken);
		jdbcClient.sql("""
				SELECT u.id FROM app_user u
				JOIN password_credential c ON c.user_id = u.id
				WHERE u.id = :userId AND u.status = 'ACTIVE'
				FOR UPDATE OF u, c
				""")
				.param("userId", userId)
				.query((resultSet, rowNum) -> resultSet.getObject("id", UUID.class))
				.optional()
				.orElseThrow(AuthService::invalidToken);
		jdbcClient.sql("""
				SELECT id FROM password_reset_token
				WHERE user_id = :userId
				  AND token_digest = :digest
				  AND consumed_at IS NULL
				  AND expires_at > :now
				FOR UPDATE
				""")
				.param("userId", userId)
				.param("digest", digest)
				.param("now", timestamp(Instant.now()))
				.query((resultSet, rowNum) -> resultSet.getObject("id", UUID.class))
				.optional()
				.orElseThrow(AuthService::invalidToken);

		Instant now = Instant.now();
		jdbcClient.sql("""
				UPDATE password_credential
				SET password_hash = :passwordHash,
					failed_login_count = 0,
					locked_until = NULL,
					password_changed_at = :now,
					updated_at = :now
				WHERE user_id = :userId
				""")
				.param("passwordHash", passwordHash)
				.param("now", timestamp(now))
				.param("userId", userId)
				.update();
		jdbcClient.sql("""
				UPDATE password_reset_token SET consumed_at = :now
				WHERE user_id = :userId AND consumed_at IS NULL
				""")
				.param("now", timestamp(now))
				.param("userId", userId)
				.update();
		jdbcClient.sql("""
				UPDATE auth_session SET revoked_at = :now
				WHERE user_id = :userId AND revoked_at IS NULL
				""")
				.param("now", timestamp(now))
				.param("userId", userId)
				.update();
		writeSystemAudit("PASSWORD_RESET", "APP_USER", userId);
	}

	/**
	 * Invalidates a challenge after its queued delivery reaches a permanent failure.
	 *
	 * @param challenge challenge associated with the failed email job
	 */
	public void invalidateChallenge(Challenge challenge) {
		String table = "email_verification_token".equals(challenge.purpose())
				? "email_verification_token"
				: "password_reset_token";
		String purpose = "email_verification_token".equals(table)
				? "email-verification"
				: "password-reset";
		jdbcClient.sql("UPDATE " + table + " SET consumed_at = :now WHERE token_digest = :digest")
				.param("now", timestamp(Instant.now()))
				.param("digest", tokenDigest(purpose, challenge.token()))
				.update();
	}

	/**
	 * Replaces prior active tokens for the user and inserts a digest of a new token.
	 * The table name is selected only from the two supported internal constants.
	 *
	 * @param table verification or password-reset token table name
	 * @param userId account receiving the token
	 * @param lifetime duration until expiry
	 * @return challenge with the raw token for one-time email delivery
	 * @throws IllegalStateException when the table name is not supported
	 */
	private Challenge issueToken(String table, UUID userId, Duration lifetime) {
		if (!"email_verification_token".equals(table) && !"password_reset_token".equals(table)) {
			throw new IllegalStateException("Unsupported authentication token table");
		}
		String token = randomToken();
		Instant now = Instant.now();
		jdbcClient.sql(
				"UPDATE " + table + " SET consumed_at = :now WHERE user_id = :userId AND consumed_at IS NULL")
				.param("now", timestamp(now))
				.param("userId", userId)
				.update();
		jdbcClient.sql("""
				INSERT INTO %s (user_id, token_digest, expires_at)
				VALUES (:userId, :digest, :expiresAt)
				""".formatted(table))
				.param("userId", userId)
				.param("digest", tokenDigest(table.equals("password_reset_token")
						? "password-reset" : "email-verification", token))
				.param("expiresAt", timestamp(now.plus(lifetime)))
				.update();
		return new Challenge(null, token, table);
	}

	private void writeSystemAudit(String action, String targetType, UUID targetId) {
		jdbcClient.sql("""
				INSERT INTO audit_event (actor_type, action, target_type, target_id)
				VALUES ('SYSTEM', :action, :targetType, :targetId)
				""")
				.param("action", action)
				.param("targetType", targetType)
				.param("targetId", targetId)
				.update();
	}

	private void writeUserAudit(UUID userId, String action, String targetType, UUID targetId) {
		jdbcClient.sql("""
				INSERT INTO audit_event (
					actor_type, actor_user_id, action, target_type, target_id
				)
				VALUES ('USER', :userId, :action, :targetType, :targetId)
				""")
				.param("userId", userId)
				.param("action", action)
				.param("targetType", targetType)
				.param("targetId", targetId)
				.update();
	}

	/**
	 * Selects email or username normalization based on the submitted identifier.
	 *
	 * @param value raw login identifier
	 * @return canonical email or username value
	 * @throws ApiException when the value is invalid, returning a generic
	 *         authentication failure to avoid revealing which identifier was rejected
	 */
	static String normalizeIdentifier(String value) {
		if (value == null || value.isBlank() || value.length() > 320) {
			throw new ApiException(ApiErrorCode.AUTHENTICATION_FAILED);
		}
		try {
			return value.contains("@")
					? IdentityNormalizer.email(value)
					: IdentityNormalizer.username(value);
		} catch (ApiException exception) {
			throw new ApiException(ApiErrorCode.AUTHENTICATION_FAILED, exception);
		}
	}

	/** @return a 256-bit URL-safe random token without padding */
	private String randomToken() {
		byte[] token = new byte[32];
		secureRandom.nextBytes(token);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	/**
	 * Hashes a password while limiting concurrent encoder work in this process.
	 *
	 * @param password plaintext password supplied by the request
	 * @return encoded password hash
	 * @throws ApiException when password work slots are occupied
	 */
	private String encodePassword(String password) {
		if (!PASSWORD_WORK_LIMIT.tryAcquire()) {
			throw new ApiException(ApiErrorCode.RATE_LIMITED, 1);
		}
		try {
			return passwordEncoder.encode(password);
		} finally {
			PASSWORD_WORK_LIMIT.release();
		}
	}

	/**
	 * Validates session token encoding and returns its SHA-256 digest.
	 *
	 * @param token raw opaque session token
	 * @return lowercase hexadecimal digest
	 * @throws IllegalArgumentException when token syntax is invalid
	 */
	private String sessionDigest(String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
			throw new IllegalArgumentException("Invalid session token");
		}
		return sha256(token);
	}

	/**
	 * Validates a challenge token and digests it with a purpose-specific prefix.
	 *
	 * @param purpose domain-separation label
	 * @param token raw opaque challenge token
	 * @return lowercase hexadecimal digest
	 * @throws ApiException when token syntax is invalid
	 */
	private String tokenDigest(String purpose, String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
			throw invalidToken();
		}
		return sha256(purpose + ":" + token);
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private static AuthDtos.UserResponse userResponse(java.sql.ResultSet resultSet) throws java.sql.SQLException {
		return new AuthDtos.UserResponse(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("email_normalized"),
				resultSet.getString("username"),
				resultSet.getString("display_name"),
				resultSet.getString("status"),
				resultSet.getTimestamp("created_at").toInstant());
	}

	private static ApiException invalidToken() {
		return new ApiException(ApiErrorCode.INVALID_OR_EXPIRED_TOKEN);
	}

	/** Snapshot of account and credential fields read while checking a login. */
	private record Account(
			UUID id,
			String email,
			String username,
			String displayName,
			String status,
			boolean emailVerified,
			Instant createdAt,
			String passwordHash,
			int failedLoginCount,
			Instant lockedUntil) {

		static Account from(java.sql.ResultSet resultSet) throws java.sql.SQLException {
			java.sql.Timestamp lock = resultSet.getTimestamp("locked_until");
			return new Account(
					resultSet.getObject("id", UUID.class),
					resultSet.getString("email_normalized"),
					resultSet.getString("username"),
					resultSet.getString("display_name"),
					resultSet.getString("status"),
					resultSet.getBoolean("email_verified"),
					resultSet.getTimestamp("created_at").toInstant(),
					resultSet.getString("password_hash"),
					resultSet.getInt("failed_login_count"),
					lock == null ? null : lock.toInstant());
		}

		AuthDtos.UserResponse toResponse() {
			return new AuthDtos.UserResponse(id, email, username, displayName, status, createdAt);
		}
	}

	/**
	 * One-time email challenge returned internally for outbox delivery.
	 * The raw token must not be logged or persisted outside its encrypted outbox payload.
	 *
	 * @param email destination address, or {@code null} before it is attached
	 * @param token raw random challenge token
	 * @param purpose token table identifying the challenge type
	 */
	public record Challenge(String email, String token, String purpose) {
		Challenge withEmail(String value) {
			return new Challenge(value, token, purpose);
		}
	}

	/**
	 * Successful login result used to set the browser cookie.
	 *
	 * @param user public profile for the authenticated account
	 * @param sessionToken raw opaque token returned once to the controller
	 * @param expiresAt absolute session expiry time
	 */
	public record LoginResult(AuthDtos.UserResponse user, String sessionToken, Instant expiresAt) {
	}
}
