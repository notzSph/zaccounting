package com.zaccounting.api.identity;

import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Applies the configured network, identifier, and global limits to auth operations.
 *
 * <p>The policy normalizes email and username identifiers before bucket lookup.
 * Global buckets bound aggregate expensive work, while source and identifier
 * buckets constrain repeated requests from one network or identity.
 */
@Component
final class AuthRateLimitPolicy {

	private final AuthRateLimiter rateLimiter;

	AuthRateLimitPolicy(AuthRateLimiter rateLimiter) {
		this.rateLimiter = rateLimiter;
	}

	/**
	 * Applies registration source, email, username, and aggregate password-hash limits.
	 *
	 * @param request registration request whose email and username are normalized
	 * @param clientNetwork canonical network key resolved from the request
	 */
	void beforeRegister(AuthDtos.RegisterRequest request, String clientNetwork) {
		String email = IdentityNormalizer.email(request.email());
		String username = IdentityNormalizer.username(request.username());
		rateLimiter.consume("register-source", clientNetwork, 30, Duration.ofHours(1));
		rateLimiter.consume("register-email", email, 3, Duration.ofHours(1));
		rateLimiter.consume("register-username", username, 3, Duration.ofHours(1));
		rateLimiter.consume("password-hash-global", "all", 1_000, Duration.ofMinutes(15));
	}

	/**
	 * Applies source-network, global, and normalized identifier login limits.
	 *
	 * @param rawIdentifier submitted email address or username
	 * @param clientNetwork canonical network key resolved from the request
	 */
	void beforeLogin(String rawIdentifier, String clientNetwork) {
		rateLimiter.consume("login-source-network", clientNetwork, 100, Duration.ofMinutes(15));
		String identifier = AuthService.normalizeIdentifier(rawIdentifier);
		rateLimiter.consume("login-global", "all", 1_000, Duration.ofMinutes(15));
		rateLimiter.consume("login-identifier", identifier, 10, Duration.ofMinutes(15));
	}

	/**
	 * Applies source-network and email-specific verification resend limits.
	 *
	 * @param rawEmail submitted email address
	 * @param clientNetwork canonical network key resolved from the request
	 */
	void beforeVerificationResend(String rawEmail, String clientNetwork) {
		String email = IdentityNormalizer.email(rawEmail);
		rateLimiter.consume("verify-email-source", clientNetwork, 20, Duration.ofHours(1));
		rateLimiter.consume("verify-email", email, 3, Duration.ofHours(1));
	}

	/**
	 * Applies a source-network limit to token verification attempts.
	 *
	 * @param clientNetwork canonical network key resolved from the request
	 */
	void beforeEmailVerification(String clientNetwork) {
		rateLimiter.consume("verify-token-source", clientNetwork, 30, Duration.ofHours(1));
	}

	/**
	 * Applies source-network and email-specific password recovery limits.
	 *
	 * @param rawEmail submitted email address
	 * @param clientNetwork canonical network key resolved from the request
	 */
	void beforePasswordRecovery(String rawEmail, String clientNetwork) {
		String email = IdentityNormalizer.email(rawEmail);
		rateLimiter.consume("password-recovery-source", clientNetwork, 20, Duration.ofMinutes(15));
		rateLimiter.consume("password-recovery", email, 3, Duration.ofMinutes(15));
	}

	/**
	 * Applies source-network and aggregate password-hash limits to reset attempts.
	 *
	 * @param clientNetwork canonical network key resolved from the request
	 */
	void beforePasswordReset(String clientNetwork) {
		rateLimiter.consume("password-reset-source", clientNetwork, 20, Duration.ofMinutes(15));
		rateLimiter.consume("password-hash-global", "all", 1_000, Duration.ofMinutes(15));
	}
}
