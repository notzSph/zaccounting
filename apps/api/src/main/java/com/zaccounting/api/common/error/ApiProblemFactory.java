package com.zaccounting.api.common.error;

import java.net.URI;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the shared RFC 9457 response body used by MVC and servlet security errors. */
public final class ApiProblemFactory {
	private ApiProblemFactory() {
	}

	/**
	 * Creates a problem document for the supplied error code and request.
	 *
	 * @param errorCode catalog entry defining status and safe public text
	 * @param request current servlet request containing the generated request identifier
	 * @return an RFC 9457 problem document with stable code and request identifier properties
	 */
	public static ProblemDetail create(ApiErrorCode errorCode, HttpServletRequest request) {
		return create(errorCode, errorCode.status(), request);
	}

	/**
	 * Creates a problem document using a framework-provided HTTP status.
	 *
	 * @param errorCode stable public classification and safe description
	 * @param status HTTP status returned by a framework error
	 * @param request current servlet request
	 * @return problem document with the supplied HTTP status
	 */
	public static ProblemDetail create(
			ApiErrorCode errorCode,
			HttpStatusCode status,
			HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, errorCode.detail());
		problem.setType(URI.create(
				"urn:zaccounting:error:" + errorCode.code().toLowerCase(Locale.ROOT)));
		problem.setTitle(errorCode.title());
		problem.setProperty("code", errorCode.code());
		problem.setProperty("requestId", RequestIdFilter.requestId(request));
		return problem;
	}
}
