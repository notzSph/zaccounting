package com.zaccounting.api.common.error;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds a server-generated request identifier to API responses and logging context. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {
	/** HTTP response header containing the server-generated request identifier. */
	public static final String HEADER_NAME = "X-Request-ID";
	static final String ATTRIBUTE_NAME = RequestIdFilter.class.getName() + ".requestId";
	private static final String MDC_KEY = "requestId";

	/**
	 * Establishes a fresh server-controlled identifier for each HTTP request.
	 *
	 * @param request current servlet request
	 * @param response current servlet response
	 * @param filterChain remaining servlet filters and handler
	 * @throws ServletException when downstream processing fails
	 * @throws IOException when request or response I/O fails
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE_NAME, requestId);
		response.setHeader(HEADER_NAME, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	/**
	 * Reads the identifier generated for this request, with a safe fallback for
	 * requests that bypassed this filter.
	 *
	 * @param request current servlet request
	 * @return request identifier or {@code unavailable} when no identifier is present
	 */
	public static String requestId(HttpServletRequest request) {
		Object value = request.getAttribute(ATTRIBUTE_NAME);
		return value instanceof String requestId ? requestId : "unavailable";
	}
}
