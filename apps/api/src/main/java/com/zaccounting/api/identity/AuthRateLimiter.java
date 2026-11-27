package com.zaccounting.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enforces shared fixed-window request limits using PostgreSQL-backed buckets.
 *
 * <p>Bucket identities are HMAC-SHA-256 digests keyed by a configured pepper so
 * raw emails, usernames, and client network keys are not stored in the limiter
 * table. Each bucket update runs in an independent transaction.
 */
@Component
final class AuthRateLimiter {

	private final JdbcClient jdbcClient;
	private final AuthProperties properties;
	private final TransactionTemplate independentTransaction;

	AuthRateLimiter(
			JdbcClient jdbcClient,
			AuthProperties properties,
			PlatformTransactionManager transactionManager) {
		this.jdbcClient = jdbcClient;
		this.properties = properties;
		this.independentTransaction = new TransactionTemplate(transactionManager);
		this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Counts one request in the scoped window and rejects requests above the limit.
	 *
	 * @param scope distinct operation name to isolate limit buckets
	 * @param identity normalized user, network, or global bucket identity
	 * @param limit maximum accepted requests during the window
	 * @param window fixed-window duration
	 * @throws ApiException when this request exceeds the configured limit or the
	 *         rate-limit pepper is not configured
	 */
	void consume(String scope, String identity, int limit, Duration window) {
		String pepper = properties.rateLimitPepper();
		if (pepper == null || pepper.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new ApiException(ApiErrorCode.AUTHENTICATION_UNAVAILABLE);
		}

		String bucketKey = digest(scope + ":" + identity, pepper);
		Instant now = Instant.now();
		Instant cutoff = now.minus(window);
		Instant expiresAt = now.plus(window.multipliedBy(2));

		RateCount rateCount = independentTransaction.execute(status -> jdbcClient.sql("""
				INSERT INTO auth_rate_limit_bucket (
					bucket_key, window_started_at, request_count, expires_at
				)
				VALUES (:bucketKey, :now, 1, :expiresAt)
				ON CONFLICT (bucket_key) DO UPDATE SET
					window_started_at = CASE
						WHEN auth_rate_limit_bucket.window_started_at <= :cutoff THEN :now
						ELSE auth_rate_limit_bucket.window_started_at
					END,
					request_count = CASE
						WHEN auth_rate_limit_bucket.window_started_at <= :cutoff THEN 1
						ELSE LEAST(auth_rate_limit_bucket.request_count + 1, :limitPlusOne)
					END,
					expires_at = :expiresAt
				RETURNING request_count, window_started_at
				""")
				.param("bucketKey", bucketKey)
				.param("now", now.atOffset(java.time.ZoneOffset.UTC))
				.param("cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC))
				.param("expiresAt", expiresAt.atOffset(java.time.ZoneOffset.UTC))
				.param("limitPlusOne", limit + 1)
				.query((resultSet, rowNum) -> new RateCount(
						resultSet.getInt("request_count"),
						resultSet.getObject("window_started_at", OffsetDateTime.class).toInstant()))
				.single());

		if (rateCount.count() > limit) {
			long retryAfter = Math.max(
					1,
					Duration.between(now, rateCount.windowStartedAt().plus(window)).toSeconds());
			throw new ApiException(ApiErrorCode.RATE_LIMITED, retryAfter);
		}
	}

	private String digest(String value, String pepper) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
		}
	}

	private record RateCount(int count, Instant windowStartedAt) {
	}

}
