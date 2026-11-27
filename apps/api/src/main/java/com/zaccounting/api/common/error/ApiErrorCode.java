package com.zaccounting.api.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable public error codes and safe default descriptions for API failures.
 *
 * <p>Clients should branch on {@link #code()}, not on the human-readable title
 * or detail. Details must remain safe to return to an untrusted caller.
 */
public enum ApiErrorCode {
	VALIDATION_FAILED(
			HttpStatus.BAD_REQUEST,
			"Validation failed",
			"One or more request fields are invalid."),
	REQUEST_REJECTED(
			HttpStatus.BAD_REQUEST,
			"Request rejected",
			"The request could not be processed."),
	MALFORMED_REQUEST(
			HttpStatus.BAD_REQUEST,
			"Malformed request",
			"The request body could not be read."),
	RESOURCE_NOT_FOUND(
			HttpStatus.NOT_FOUND,
			"Resource not found",
			"The requested resource was not found."),
	METHOD_NOT_ALLOWED(
			HttpStatus.METHOD_NOT_ALLOWED,
			"Method not allowed",
			"This HTTP method is not supported for the requested resource."),
	PAYLOAD_TOO_LARGE(
			HttpStatus.PAYLOAD_TOO_LARGE,
			"Payload too large",
			"The request payload exceeds the allowed size."),
	UNSUPPORTED_MEDIA_TYPE(
			HttpStatus.UNSUPPORTED_MEDIA_TYPE,
			"Unsupported media type",
			"The request content type is not supported."),
	AUTHENTICATION_REQUIRED(
			HttpStatus.UNAUTHORIZED,
			"Authentication required",
			"Authentication is required to access this resource."),
	AUTHENTICATION_FAILED(
			HttpStatus.UNAUTHORIZED,
			"Authentication failed",
			"The supplied credentials are invalid or the account is not eligible to sign in."),
	ACCESS_DENIED(
			HttpStatus.FORBIDDEN,
			"Access denied",
			"You do not have permission to perform this operation."),
	ORIGIN_NOT_ALLOWED(
			HttpStatus.FORBIDDEN,
			"Origin not allowed",
			"The request origin is not allowed."),
	ACCOUNT_IDENTIFIER_CONFLICT(
			HttpStatus.CONFLICT,
			"Account identifier unavailable",
			"The email address or username cannot be used."),
	REQUEST_CONFLICT(
			HttpStatus.CONFLICT,
			"Request conflict",
			"The request conflicts with the current resource state."),
	INVALID_OR_EXPIRED_TOKEN(
			HttpStatus.BAD_REQUEST,
			"Invalid or expired link",
			"This link is invalid, expired, or has already been used."),
	RATE_LIMITED(
			HttpStatus.TOO_MANY_REQUESTS,
			"Too many requests",
			"Too many requests. Please try again later."),
	EMAIL_DELIVERY_UNAVAILABLE(
			HttpStatus.SERVICE_UNAVAILABLE,
			"Email delivery unavailable",
			"Account email delivery is temporarily unavailable."),
	AUTHENTICATION_UNAVAILABLE(
			HttpStatus.SERVICE_UNAVAILABLE,
			"Authentication temporarily unavailable",
			"Authentication is temporarily unavailable."),
	DEPENDENCY_UNAVAILABLE(
			HttpStatus.SERVICE_UNAVAILABLE,
			"Service temporarily unavailable",
			"A required service is temporarily unavailable."),
	INTERNAL_ERROR(
			HttpStatus.INTERNAL_SERVER_ERROR,
			"Internal server error",
			"The request could not be completed due to an internal error.");

	private final HttpStatus status;
	private final String title;
	private final String detail;

	ApiErrorCode(HttpStatus status, String title, String detail) {
		this.status = status;
		this.title = title;
		this.detail = detail;
	}

	/** @return stable machine-readable error code */
	public String code() {
		return name();
	}

	/** @return HTTP status associated with this error */
	public HttpStatus status() {
		return status;
	}

	/** @return short safe title for API clients */
	public String title() {
		return title;
	}

	/** @return safe default detail for API clients */
	public String detail() {
		return detail;
	}
}
