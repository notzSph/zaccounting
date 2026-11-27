package com.zaccounting.api.identity;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Defines validated request bodies and deliberately limited authentication responses.
 *
 * <p>These records form the HTTP contract. They do not expose password hashes,
 * session digests, challenge digests, lockout state, or internal database entities.
 */
public final class AuthDtos {

	private AuthDtos() {
	}

	/**
	 * Registration data used to create an unverified local account.
	 *
	 * @param email account email address
	 * @param username desired account username
	 * @param displayName human-readable account name
	 * @param password new password, subject to service-level Unicode and byte limits
	 */
	public record RegisterRequest(
			@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(min = 3, max = 64) String username,
			@NotBlank @Size(min = 1, max = 200) String displayName,
			@NotBlank @Size(min = 12, max = 512) String password) {
	}

	/**
	 * Credentials for a login using either the normalized email or username.
	 *
	 * @param identifier email address or username
	 * @param password password to verify
	 */
	public record LoginRequest(
			@NotBlank @Size(max = 320) String identifier,
			@NotBlank @Size(max = 512) String password) {
	}

	/**
	 * Email address used to request verification delivery or password recovery.
	 *
	 * @param email submitted account email address
	 */
	public record PasswordRecoveryRequest(@NotBlank @Email @Size(max = 320) String email) {
	}

	/**
	 * Single-use password reset token and the replacement password.
	 *
	 * @param token raw token received by email
	 * @param password replacement password
	 */
	public record PasswordResetRequest(
			@NotBlank @Size(max = 512) String token,
			@NotBlank @Size(min = 12, max = 512) String password) {
	}

	/**
	 * One-time token submitted to confirm an email address.
	 *
	 * @param token raw email verification token
	 */
	public record TokenRequest(@NotBlank @Size(max = 512) String token) {
	}

	/**
	 * Public profile returned for the currently authenticated account.
	 *
	 * @param id stable user identifier
	 * @param email normalized account email
	 * @param username canonical account username
	 * @param displayName human-readable account name
	 * @param status account status
	 * @param createdAt account creation timestamp
	 */
	public record UserResponse(
			UUID id,
			String email,
			String username,
			String displayName,
			String status,
			Instant createdAt) {
	}

	/**
	 * Authentication response containing the signed-in user's public profile.
	 *
	 * @param user authenticated user's public profile
	 */
	public record AuthResponse(UserResponse user) {
	}

	/**
	 * Generic human-readable status returned by verification and recovery flows.
	 *
	 * @param detail response message
	 */
	public record RecoveryResponse(String detail) {
	}

	/**
	 * CSRF token response for same-origin clients.
	 *
	 * @param token value to send in the CSRF request header for protected writes
	 */
	public record CsrfResponse(String token) {
	}
}
