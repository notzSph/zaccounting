package com.zaccounting.api.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds authentication cookie, public-origin, proxy, rate-limit, and outbox settings.
 *
 * @param sessionCookieName name used for the opaque server-side session cookie
 * @param sessionCookieSecure whether the cookie must only be sent over HTTPS
 * @param rateLimitPepper secret used to HMAC rate-limit identifiers before persistence
 * @param publicOrigin canonical origin accepted for unsafe browser requests
 * @param frontendUrl base URL used in emailed verification and reset links
 * @param trustedProxyCidrs comma-separated proxy CIDRs allowed to supply forwarded client addresses
 * @param outboxEncryptionKey Base64-encoded 32-byte AES key for queued email payloads
 */
@ConfigurationProperties(prefix = "app.security")
public record AuthProperties(
		String sessionCookieName,
		boolean sessionCookieSecure,
		String rateLimitPepper,
		String publicOrigin,
		String frontendUrl,
		String trustedProxyCidrs,
		String outboxEncryptionKey) {
}
