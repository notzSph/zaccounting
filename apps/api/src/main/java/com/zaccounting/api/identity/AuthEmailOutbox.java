package com.zaccounting.api.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists authentication email jobs with encrypted payloads and leased claims.
 *
 * <p>Claims use row locking with {@code SKIP LOCKED} so multiple application
 * instances can dispatch jobs without selecting the same active lease. Payloads
 * are cleared after success or terminal failure. Jobs retry with bounded
 * exponential backoff and become terminal after eight attempts.
 */
@Service
class AuthEmailOutbox {

	private static final int MAX_ATTEMPTS = 8;
	private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

	private final JdbcClient jdbcClient;
	private final BytesEncryptor encryptor;

	/**
	 * Creates the persistent queue with its payload encryptor.
	 *
	 * @param jdbcClient parameterized SQL client for outbox rows
	 * @param authOutboxEncryptor AES-GCM encryptor for recipient and token data
	 */
	AuthEmailOutbox(JdbcClient jdbcClient, BytesEncryptor authOutboxEncryptor) {
		this.jdbcClient = jdbcClient;
		this.encryptor = authOutboxEncryptor;
	}

	/**
	 * Adds an encrypted email verification job within the caller's transaction.
	 *
	 * @param challenge verification challenge with recipient and raw token
	 */
	void enqueueVerification(AuthService.Challenge challenge) {
		enqueue("EMAIL_VERIFICATION", challenge);
	}

	/**
	 * Adds an encrypted password reset job within the caller's transaction.
	 *
	 * @param challenge reset challenge with recipient and raw token
	 */
	void enqueuePasswordReset(AuthService.Challenge challenge) {
		enqueue("PASSWORD_RESET", challenge);
	}

	/**
	 * Adds an encrypted no-op job to make unknown-address recovery requests perform
	 * asynchronous outbox work as well.
	 */
	void enqueueNoop() {
		insert("NOOP", encryptor.encrypt("NOOP\n-\n-".getBytes(StandardCharsets.UTF_8)));
	}

	private void enqueue(String messageType, AuthService.Challenge challenge) {
		String plaintext = messageType + "\n" + challenge.email() + "\n" + challenge.token();
		insert(messageType, encryptor.encrypt(plaintext.getBytes(StandardCharsets.UTF_8)));
	}

	private void insert(String messageType, byte[] encryptedPayload) {
		jdbcClient.sql("""
				INSERT INTO auth_email_outbox (message_type, encrypted_payload)
				VALUES (:messageType, :payload)
				""")
				.param("messageType", messageType)
				.param("payload", encryptedPayload)
				.update();
	}

	/**
	 * Claims one ready job using a time-limited lease.
	 *
	 * @return the claimed job, or empty when no job is ready
	 */
	@Transactional
	public Optional<EmailJob> claimNext() {
		Instant now = Instant.now();
		jdbcClient.sql("""
				UPDATE auth_email_outbox
				SET status = 'FAILED', encrypted_payload = NULL,
					lock_token = NULL, locked_until = NULL
				WHERE attempts >= :maxAttempts
				  AND status = 'PROCESSING'
				  AND locked_until <= :now
				""")
				.param("maxAttempts", MAX_ATTEMPTS)
				.param("now", timestamp(now))
				.update();

		UUID lockToken = UUID.randomUUID();
		return jdbcClient.sql("""
				WITH candidate AS (
					SELECT id
					FROM auth_email_outbox
					WHERE attempts < :maxAttempts
					  AND (
						(status = 'PENDING' AND available_at <= :now)
						OR (status = 'PROCESSING' AND locked_until <= :now)
					  )
					ORDER BY available_at, created_at
					FOR UPDATE SKIP LOCKED
					LIMIT 1
				)
				UPDATE auth_email_outbox AS outbox
				SET status = 'PROCESSING', attempts = outbox.attempts + 1,
					lock_token = :lockToken, locked_until = :lockedUntil
				FROM candidate
				WHERE outbox.id = candidate.id
				RETURNING outbox.id, outbox.message_type, outbox.encrypted_payload,
					outbox.lock_token, outbox.attempts
				""")
				.param("maxAttempts", MAX_ATTEMPTS)
				.param("now", timestamp(now))
				.param("lockToken", lockToken)
				.param("lockedUntil", timestamp(now.plus(CLAIM_LEASE)))
				.query((resultSet, rowNum) -> new EmailJob(
						resultSet.getObject("id", UUID.class),
						resultSet.getString("message_type"),
						resultSet.getBytes("encrypted_payload"),
						resultSet.getObject("lock_token", UUID.class),
						resultSet.getInt("attempts")))
				.optional();
	}

	/**
	 * Marks a currently leased job sent and clears its encrypted payload.
	 *
	 * @param job previously claimed job carrying its lease token
	 */
	void markSent(EmailJob job) {
		jdbcClient.sql("""
				UPDATE auth_email_outbox
				SET status = 'SENT', encrypted_payload = NULL, sent_at = :now,
					lock_token = NULL, locked_until = NULL
				WHERE id = :id AND status = 'PROCESSING' AND lock_token = :lockToken
				""")
				.param("now", timestamp(Instant.now()))
				.param("id", job.id())
				.param("lockToken", job.lockToken())
				.update();
	}

	/**
	 * Schedules a retry or marks a job terminal after its final attempt.
	 *
	 * @param job previously claimed job carrying its attempt count and lease token
	 * @return {@code true} when the job reached terminal failure, otherwise {@code false}
	 */
	boolean markFailedOrRetry(EmailJob job) {
		Instant now = Instant.now();
		if (job.attempts() >= MAX_ATTEMPTS) {
			return jdbcClient.sql("""
					UPDATE auth_email_outbox
					SET status = 'FAILED', encrypted_payload = NULL,
						lock_token = NULL, locked_until = NULL
					WHERE id = :id AND status = 'PROCESSING' AND lock_token = :lockToken
					""")
					.param("id", job.id())
					.param("lockToken", job.lockToken())
					.update() == 1;
		}

		long delaySeconds = Math.min(3600, 5L << Math.min(job.attempts() - 1, 9));
		jdbcClient.sql("""
				UPDATE auth_email_outbox
				SET status = 'PENDING', available_at = :availableAt,
					lock_token = NULL, locked_until = NULL
				WHERE id = :id AND status = 'PROCESSING' AND lock_token = :lockToken
				""")
				.param("availableAt", timestamp(now.plusSeconds(delaySeconds)))
				.param("id", job.id())
				.param("lockToken", job.lockToken())
				.update();
		return false;
	}

	/**
	 * Decrypts and validates a claimed job's authentication email payload.
	 *
	 * @param job claimed job containing the encrypted payload
	 * @return decoded message type, recipient, and token
	 * @throws IllegalStateException when the payload structure or message type is invalid
	 */
	EmailMessage decrypt(EmailJob job) {
		String plaintext = new String(encryptor.decrypt(job.encryptedPayload()), StandardCharsets.UTF_8);
		String[] parts = plaintext.split("\n", 3);
		if (parts.length != 3 || !job.messageType().equals(parts[0])) {
			throw new IllegalStateException("Invalid encrypted authentication email payload");
		}
		return new EmailMessage(parts[0], parts[1], parts[2]);
	}

	private OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** A claimed outbox row and its lease metadata. */
	record EmailJob(UUID id, String messageType, byte[] encryptedPayload, UUID lockToken, int attempts) {
	}

	/** Decoded recipient and one-time token used to deliver an authentication message. */
	record EmailMessage(String messageType, String recipient, String token) {
	}
}
