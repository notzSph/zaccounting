package com.zaccounting.api.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired authentication records and old terminal outbox jobs on schedule.
 *
 * <p>Retention is currently 30 days for terminal email jobs, 7 days for consumed
 * or expired one-time challenges, and 90 days for expired or revoked sessions.
 */
@Component
final class AuthMaintenance {

	private final JdbcClient jdbcClient;

	/**
	 * Creates the scheduled cleanup task.
	 *
	 * @param jdbcClient parameterized SQL client for authentication tables
	 */
	AuthMaintenance(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * Deletes expired rate-limit buckets and authentication records past retention.
	 */
	@Scheduled(cron = "0 17 * * * *")
	void removeExpiredAuthenticationRecords() {
		OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
		jdbcClient.sql("DELETE FROM auth_rate_limit_bucket WHERE expires_at <= :now")
				.param("now", now)
				.update();
		jdbcClient.sql("""
				DELETE FROM auth_email_outbox
				WHERE status IN ('SENT', 'FAILED') AND created_at <= :retentionCutoff
				""")
				.param("retentionCutoff", now.minusDays(30))
				.update();
		jdbcClient.sql("""
				DELETE FROM email_verification_token
				WHERE expires_at <= :retentionCutoff OR consumed_at <= :retentionCutoff
				""")
				.param("retentionCutoff", now.minusDays(7))
				.update();
		jdbcClient.sql("""
				DELETE FROM password_reset_token
				WHERE expires_at <= :retentionCutoff OR consumed_at <= :retentionCutoff
				""")
				.param("retentionCutoff", now.minusDays(7))
				.update();
		jdbcClient.sql("""
				DELETE FROM auth_session
				WHERE COALESCE(revoked_at, expires_at) <= :retentionCutoff
				""")
				.param("retentionCutoff", now.minusDays(90))
				.update();
	}
}
