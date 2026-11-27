package com.zaccounting.api.common.error;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Converts API exceptions to a consistent RFC 9457 error response. */
@RestControllerAdvice(basePackages = "com.zaccounting.api")
public class ApiExceptionHandler {
	private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/**
	 * Maps an application-defined failure to the catalog's status and public detail.
	 *
	 * @param exception typed application failure
	 * @param request current servlet request
	 * @return problem response with stable code and request identifier
	 */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ProblemDetail> handleApiException(
			ApiException exception,
			HttpServletRequest request) {
		ApiErrorCode errorCode = exception.errorCode();
		ProblemDetail problem = ApiProblemFactory.create(errorCode, request);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(errorCode.status())
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header(RequestIdFilter.HEADER_NAME, RequestIdFilter.requestId(request));
		if (exception.retryAfterSeconds() != null) {
			response.header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
		}
		return response.body(problem);
	}

	/**
	 * Reports bean-validation failures without returning rejected values.
	 *
	 * @param exception validation exception containing field errors
	 * @param request current servlet request
	 * @return problem response with safe field names, constraint codes, and messages
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> handleValidation(
			MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		ProblemDetail problem = ApiProblemFactory.create(ApiErrorCode.VALIDATION_FAILED, request);
		List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
				.map(this::safeFieldError)
				.toList();
		problem.setProperty("errors", errors);
		return response(problem, ApiErrorCode.VALIDATION_FAILED, request);
	}

	/**
	 * Maps method-level constraint validation failures to a generic validation response.
	 *
	 * @param exception constraint failure
	 * @param request current servlet request
	 * @return validation problem response
	 */
	@ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
	public ResponseEntity<ProblemDetail> handleConstraintValidation(
			Exception exception,
			HttpServletRequest request) {
		return response(ApiErrorCode.VALIDATION_FAILED, request);
	}

	/**
	 * Maps malformed JSON or unreadable request bodies to a stable client error.
	 *
	 * @param exception body parsing failure
	 * @param request current servlet request
	 * @return malformed-request problem response
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> handleMalformedRequest(
			HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		return response(ApiErrorCode.MALFORMED_REQUEST, request);
	}

	/**
	 * Maps MVC authentication failures to the shared unauthorized response.
	 *
	 * @param exception Spring Security authentication failure
	 * @param request current servlet request
	 * @return authentication-required problem response
	 */
	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ProblemDetail> handleAuthentication(
			AuthenticationException exception,
			HttpServletRequest request) {
		return response(ApiErrorCode.AUTHENTICATION_REQUIRED, request);
	}

	/**
	 * Maps MVC authorization failures to the shared forbidden response.
	 *
	 * @param exception Spring Security authorization failure
	 * @param request current servlet request
	 * @return access-denied problem response
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ProblemDetail> handleAccessDenied(
			AccessDeniedException exception,
			HttpServletRequest request) {
		return response(ApiErrorCode.ACCESS_DENIED, request);
	}

	/**
	 * Treats unexpected integrity failures as server errors instead of mislabeling
	 * them as account conflicts.
	 *
	 * @param exception database integrity failure
	 * @param request current servlet request
	 * @return generic internal-error problem response
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ProblemDetail> handleIntegrityFailure(
			DataIntegrityViolationException exception,
			HttpServletRequest request) {
		logger.error("API integrity operation failed requestId={}", RequestIdFilter.requestId(request), exception);
		return response(ApiErrorCode.INTERNAL_ERROR, request);
	}

	/**
	 * Maps database availability failures without exposing driver or SQL details.
	 *
	 * @param exception database access failure
	 * @param request current servlet request
	 * @return dependency-unavailable problem response
	 */
	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ProblemDetail> handleDataAccess(
			DataAccessException exception,
			HttpServletRequest request) {
		logger.error("API database operation failed requestId={}", RequestIdFilter.requestId(request), exception);
		return response(ApiErrorCode.DEPENDENCY_UNAVAILABLE, request);
	}

	/**
	 * Normalizes framework error statuses or logs unexpected failures with the request ID.
	 *
	 * @param exception unexpected application failure
	 * @param request current servlet request
	 * @return normalized framework or internal-error problem response
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleUnexpected(
			Exception exception,
			HttpServletRequest request) {
		if (exception instanceof org.springframework.web.ErrorResponse frameworkError) {
			return frameworkError(frameworkError, request);
		}
		logger.error("Unhandled API failure requestId={}", RequestIdFilter.requestId(request), exception);
		return response(ApiErrorCode.INTERNAL_ERROR, request);
	}

	private ResponseEntity<ProblemDetail> frameworkError(
			org.springframework.web.ErrorResponse exception,
			HttpServletRequest request) {
		HttpStatusCode status = exception.getStatusCode();
		ApiErrorCode errorCode = errorCodeFor(status.value());
		if (status.is5xxServerError()) {
			logger.error("Framework API failure requestId={} status={}",
					RequestIdFilter.requestId(request), status.value(), exception);
		}
		ProblemDetail problem = ApiProblemFactory.create(errorCode, status, request);
		return response(problem, status, request);
	}

	private Map<String, String> safeFieldError(FieldError error) {
		return Map.of(
				"field", error.getField(),
				"code", error.getCode() == null ? "Invalid" : error.getCode(),
				"message", error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage());
	}

	private ResponseEntity<ProblemDetail> response(ApiErrorCode errorCode, HttpServletRequest request) {
		return response(ApiProblemFactory.create(errorCode, request), errorCode, request);
	}

	private ResponseEntity<ProblemDetail> response(
			ProblemDetail problem,
			ApiErrorCode errorCode,
			HttpServletRequest request) {
		return response(problem, errorCode.status(), request);
	}

	private ResponseEntity<ProblemDetail> response(
			ProblemDetail problem,
			HttpStatusCode status,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header(RequestIdFilter.HEADER_NAME, RequestIdFilter.requestId(request))
				.body(problem);
	}

	private ApiErrorCode errorCodeFor(int status) {
		return switch (status) {
			case 400 -> ApiErrorCode.VALIDATION_FAILED;
			case 401 -> ApiErrorCode.AUTHENTICATION_REQUIRED;
			case 403 -> ApiErrorCode.ACCESS_DENIED;
			case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
			case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
			case 409 -> ApiErrorCode.REQUEST_CONFLICT;
			case 413 -> ApiErrorCode.PAYLOAD_TOO_LARGE;
			case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
			case 429 -> ApiErrorCode.RATE_LIMITED;
			case 503 -> ApiErrorCode.DEPENDENCY_UNAVAILABLE;
			default -> status >= 400 && status < 500
					? ApiErrorCode.REQUEST_REJECTED
					: ApiErrorCode.INTERNAL_ERROR;
		};
	}
}
