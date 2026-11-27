package com.zaccounting.api.identity;

import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiException;
import com.zaccounting.api.common.error.RequestIdFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the HTTP endpoints for account registration, session management, and
 * email-based account challenges.
 *
 * <p>The controller validates request DTOs, applies request limits before
 * expensive operations, delegates identity rules to {@link AuthService}, and
 * sets or clears the configured HTTP-only session cookie. Mail-dependent
 * endpoints return an unavailable response when delivery is not configured.
 *
 * @see AuthService
 * @see AuthDtos
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

	private final AuthService authService;
	private final AuthMailService mailService;
	private final AuthProperties properties;
	private final AuthClientAddressResolver clientAddressResolver;
	private final AuthRateLimitPolicy rateLimitPolicy;

	/**
	 * Creates the controller with authentication, mail, cookie, and throttling collaborators.
	 *
	 * @param authService account and session application service
	 * @param mailService asynchronous mail delivery status
	 * @param properties cookie and public-origin settings
	 * @param clientAddressResolver trusted-proxy-aware network resolver
	 * @param rateLimitPolicy endpoint-specific rate-limit policy
	 */
	AuthController(
			AuthService authService,
			AuthMailService mailService,
			AuthProperties properties,
			AuthClientAddressResolver clientAddressResolver,
			AuthRateLimitPolicy rateLimitPolicy) {
		this.authService = authService;
		this.mailService = mailService;
		this.properties = properties;
		this.clientAddressResolver = clientAddressResolver;
		this.rateLimitPolicy = rateLimitPolicy;
	}

	/**
	 * Returns the CSRF token issued for the current request.
	 *
	 * @param token token supplied by Spring Security's CSRF request handler
	 * @return the token value for a same-origin client to submit on protected writes
	 */
	@GetMapping("/csrf")
	public AuthDtos.CsrfResponse csrf(CsrfToken token) {
		return new AuthDtos.CsrfResponse(token.getToken());
	}

	/**
	 * Creates an unverified account and queues its email confirmation message.
	 *
	 * @param request validated email, username, display name, and password
	 * @param servletRequest request used to derive the client network rate-limit key
	 * @return an accepted response asking the user to check their email
	 * @throws ApiException when mail delivery is unavailable or the
	 *         normalized email or username is already registered
	 */
	@PostMapping("/register")
	public ResponseEntity<AuthDtos.RecoveryResponse> register(
			@Valid @RequestBody AuthDtos.RegisterRequest request,
			HttpServletRequest servletRequest) {
		requireMail();
		rateLimitPolicy.beforeRegister(request, clientAddressResolver.clientNetwork(servletRequest));
		authService.register(request);
		return ResponseEntity.accepted().body(new AuthDtos.RecoveryResponse(
				"Check your email for a link to confirm your account."));
	}

	/**
	 * Authenticates an email address or username and issues a server-side session.
	 *
	 * @param request normalized identifier and password
	 * @param servletRequest request used to derive the client network rate-limit key
	 * @param response response receiving the secure, HTTP-only session cookie
	 * @return the authenticated user's public profile
	 * @throws ApiException when credentials are invalid or rate limited
	 */
	@PostMapping("/login")
	public AuthDtos.AuthResponse login(
			@Valid @RequestBody AuthDtos.LoginRequest request,
			HttpServletRequest servletRequest,
			HttpServletResponse response) {
		String clientNetwork = clientAddressResolver.clientNetwork(servletRequest);
		rateLimitPolicy.beforeLogin(request.identifier(), clientNetwork);
		AuthService.LoginResult result = authService.login(request)
				.orElseThrow(() -> new ApiException(ApiErrorCode.AUTHENTICATION_FAILED));
		ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), result.sessionToken())
				.path("/")
				.httpOnly(true)
				.secure(properties.sessionCookieSecure())
				.sameSite("Lax")
				.maxAge(Duration.between(Instant.now(), result.expiresAt()))
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return new AuthDtos.AuthResponse(result.user());
	}

	/**
	 * Returns the profile resolved from the authenticated session cookie.
	 *
	 * @param user authenticated principal installed by the session filter
	 * @return the current user's public profile
	 */
	@GetMapping("/me")
	public AuthDtos.AuthResponse me(@AuthenticationPrincipal AuthDtos.UserResponse user) {
		return new AuthDtos.AuthResponse(user);
	}

	/**
	 * Revokes the presented session when valid and clears the browser cookie.
	 *
	 * @param request request that may contain the session cookie
	 * @param response response receiving an expired session cookie
	 * @return an empty no-content response
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		String token = sessionCookie(request);
		authService.logout(token);
		ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), "")
				.path("/")
				.httpOnly(true)
				.secure(properties.sessionCookieSecure())
				.sameSite("Lax")
				.maxAge(Duration.ZERO)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Consumes a single-use email verification token.
	 *
	 * @param request verification token
	 * @param servletRequest request used for network-based throttling
	 * @return confirmation that the address is verified
	 * @throws ApiException when the token is invalid, expired, or consumed
	 */
	@PostMapping("/verify-email")
	public AuthDtos.RecoveryResponse verifyEmail(
			@Valid @RequestBody AuthDtos.TokenRequest request,
			HttpServletRequest servletRequest) {
		rateLimitPolicy.beforeEmailVerification(clientAddressResolver.clientNetwork(servletRequest));
		authService.verifyEmail(request.token());
		return new AuthDtos.RecoveryResponse("Your email is confirmed. You can now sign in.");
	}

	/**
	 * Queues a replacement verification email when the account needs one.
	 *
	 * @param request email address to check
	 * @param servletRequest request used for network and address throttling
	 * @return a generic response that does not disclose account state
	 * @throws ApiException when mail delivery is unavailable
	 */
	@PostMapping("/resend-verification")
	public AuthDtos.RecoveryResponse resendVerification(
			@Valid @RequestBody AuthDtos.PasswordRecoveryRequest request,
			HttpServletRequest servletRequest) {
		requireMail();
		try {
				rateLimitPolicy.beforeVerificationResend(
						request.email(), clientAddressResolver.clientNetwork(servletRequest));
			authService.resendVerification(request.email());
		} catch (DataAccessException exception) {
			logRecoveryFailure("verification-resend", servletRequest, exception);
		}
		return new AuthDtos.RecoveryResponse(
				"If the account needs verification, a confirmation link will be sent shortly.");
	}

	/**
	 * Queues a password reset email when an eligible account exists.
	 *
	 * @param request account email address
	 * @param servletRequest request used for network and address throttling
	 * @return a generic accepted response that does not disclose account existence
	 * @throws ApiException when mail delivery is unavailable
	 */
	@PostMapping("/forgot-password")
	public ResponseEntity<AuthDtos.RecoveryResponse> forgotPassword(
			@Valid @RequestBody AuthDtos.PasswordRecoveryRequest request,
			HttpServletRequest servletRequest) {
		requireMail();
		try {
				rateLimitPolicy.beforePasswordRecovery(
						request.email(), clientAddressResolver.clientNetwork(servletRequest));
			authService.requestPasswordReset(request.email());
		} catch (DataAccessException exception) {
			logRecoveryFailure("password-recovery", servletRequest, exception);
		}
		return ResponseEntity.accepted().body(new AuthDtos.RecoveryResponse(
				"If an account exists for this email, a password reset link will be sent shortly."));
	}

	/**
	 * Consumes a password reset token, updates the credential, and revokes the
	 * current browser session.
	 *
	 * @param request reset token and replacement password
	 * @param servletRequest request that may contain the current session cookie
	 * @param response response receiving an expired session cookie
	 * @return confirmation that the password was updated
	 * @throws ApiException when the token is invalid, expired, or consumed
	 */
	@PostMapping("/reset-password")
	public AuthDtos.RecoveryResponse resetPassword(
			@Valid @RequestBody AuthDtos.PasswordResetRequest request,
			HttpServletRequest servletRequest,
			HttpServletResponse response) {
		rateLimitPolicy.beforePasswordReset(clientAddressResolver.clientNetwork(servletRequest));
		authService.resetPassword(request);
		String token = sessionCookie(servletRequest);
		authService.logout(token);
		ResponseCookie cookie = ResponseCookie.from(properties.sessionCookieName(), "")
				.path("/")
				.httpOnly(true)
				.secure(properties.sessionCookieSecure())
				.sameSite("Lax")
				.maxAge(Duration.ZERO)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return new AuthDtos.RecoveryResponse("Your password has been updated. Sign in with your new password.");
	}

	/** Rejects mail-dependent requests while outbound mail is not configured. */
	private void requireMail() {
		if (!mailService.isConfigured()) {
			throw mailUnavailable();
		}
	}

	/** @return a generic HTTP 503 response for unavailable account email delivery */
	private ApiException mailUnavailable() {
		return new ApiException(ApiErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
	}

	private void logRecoveryFailure(
			String operation,
			HttpServletRequest request,
			DataAccessException exception) {
		logger.error(
				"Authentication recovery operation failed operation={} requestId={} exceptionType={}",
				operation,
				RequestIdFilter.requestId(request),
				exception.getClass().getSimpleName(),
				exception);
	}

	/**
	 * Finds the configured session cookie without reading authorization from other headers.
	 *
	 * @param request incoming servlet request
	 * @return raw cookie value, or {@code null} when the cookie is absent
	 */
	private String sessionCookie(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
			if (properties.sessionCookieName().equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
