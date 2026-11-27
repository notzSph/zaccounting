package com.zaccounting.api.identity;

import java.text.Normalizer;
import java.util.Locale;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiException;

/**
 * Canonicalizes user-supplied identity fields and enforces application-level bounds.
 *
 * <p>Email comparisons are case-insensitive and ASCII-only. Usernames are folded
 * to lowercase ASCII letters, digits, underscores, and hyphens. Display names
 * preserve Unicode after NFC normalization. Password length is checked by Unicode
 * code points and UTF-8 byte size.
 */
final class IdentityNormalizer {

	private IdentityNormalizer() {
	}

	/**
	 * Trims, validates, and lowercases an email address for canonical storage.
	 *
	 * @param value untrusted email address
	 * @return normalized ASCII email address
	 * @throws ApiException when the address is invalid or outside bounds
	 */
	static String email(String value) {
		if (value == null || value.length() > 320 || value.chars().anyMatch(Character::isISOControl)) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}

		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
		if (!normalized.chars().allMatch(character -> character < 128)) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}

		try {
			InternetAddress address = new InternetAddress(normalized);
			address.validate();
			if (address.getPersonal() != null || !address.getAddress().equals(normalized)) {
				throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
			}
		} catch (AddressException exception) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, exception);
		}

		return normalized.toLowerCase(Locale.ROOT);
	}

	/**
	 * Converts a username to its canonical lowercase ASCII representation.
	 *
	 * @param value untrusted username
	 * @return normalized username matching the supported character and length rules
	 * @throws ApiException when normalization produces an invalid username
	 */
	static String username(String value) {
		if (value == null || value.length() > 64) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}

		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9_-]+", "");

		if (!normalized.matches("[a-z0-9][a-z0-9_-]{2,63}")) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	/**
	 * Trims and NFC-normalizes a human-readable display name.
	 *
	 * @param value untrusted display name
	 * @return normalized display name
	 * @throws ApiException when the value is blank, too long, or contains control characters
	 */
	static String displayName(String value) {
		if (value == null || value.length() > 200) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}

		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
		if (normalized.isBlank()
				|| normalized.length() > 200
				|| normalized.codePoints().anyMatch(Character::isISOControl)
				|| normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 800) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	/**
	 * Validates a newly registered or replacement password.
	 *
	 * @param password password to validate
	 * @throws ApiException when null, outside 12 to 256 code points,
	 *         or larger than 512 UTF-8 bytes
	 */
	static void validatePassword(String password) {
		if (password == null
				|| password.codePointCount(0, password.length()) < 12
				|| password.codePointCount(0, password.length()) > 256
				|| password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}
	}

	/**
	 * Validates a login password without requiring the current creation minimum.
	 *
	 * @param password password presented for login
	 * @throws ApiException when null, empty, above 256 code points,
	 *         or larger than 512 UTF-8 bytes
	 */
	static void validateLoginPassword(String password) {
		if (password == null
				|| password.isEmpty()
				|| password.codePointCount(0, password.length()) > 256
				|| password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
		}
	}
}
