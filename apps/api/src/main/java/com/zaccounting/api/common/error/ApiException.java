package com.zaccounting.api.common.error;

import java.util.Objects;

/**
 * Application exception carrying a stable API error code and safe public response.
 *
 * <p>Use this for expected failures that cross an HTTP boundary. Do not put raw
 * credentials, tokens, email addresses, database messages, or arbitrary input in
 * the exception message. Unexpected failures should keep their original cause
 * and be translated to {@link ApiErrorCode#INTERNAL_ERROR} by the global handler.
 */
public final class ApiException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final ApiErrorCode errorCode;
	private final Long retryAfterSeconds;

	/**
	 * Creates an expected API failure using the catalog's safe default detail.
	 *
	 * @param errorCode stable public error classification
	 */
	public ApiException(ApiErrorCode errorCode) {
		this(errorCode, null, null);
	}

	/**
	 * Creates an expected API failure while retaining an internal cause for diagnostics.
	 *
	 * @param errorCode stable public error classification
	 * @param cause internal cause, which is never serialized into the API response
	 */
	public ApiException(ApiErrorCode errorCode, Throwable cause) {
		this(errorCode, null, cause);
	}

	/**
	 * Creates a rate-limit failure with a retry delay.
	 *
	 * @param errorCode stable public error classification, normally {@code RATE_LIMITED}
	 * @param retryAfterSeconds number of seconds before another attempt is advised
	 */
	public ApiException(ApiErrorCode errorCode, long retryAfterSeconds) {
		this(errorCode, retryAfterSeconds, null);
	}

	private ApiException(ApiErrorCode errorCode, Long retryAfterSeconds, Throwable cause) {
		super(Objects.requireNonNull(errorCode, "errorCode").detail(), cause);
		if (retryAfterSeconds != null
				&& (errorCode != ApiErrorCode.RATE_LIMITED || retryAfterSeconds < 1)) {
			throw new IllegalArgumentException("Retry delay is only valid for rate-limit errors");
		}
		this.errorCode = errorCode;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	/** @return stable error code used to build the HTTP problem response */
	public ApiErrorCode errorCode() {
		return errorCode;
	}

	/** @return retry delay in seconds, or {@code null} when not rate limited */
	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
