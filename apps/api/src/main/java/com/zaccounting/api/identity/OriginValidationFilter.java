package com.zaccounting.api.identity;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiProblemWriter;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects unsafe browser requests whose supplied Origin or Referer is not the configured public origin.
 *
 * <p>Safe methods pass through. Requests without either source header are also
 * allowed through this filter and remain subject to Spring Security's CSRF
 * protection for cookie-authenticated writes.
 */
public final class OriginValidationFilter extends OncePerRequestFilter {

	private final AuthProperties properties;
	private final ApiProblemWriter problemWriter;

	/**
	 * Creates the filter with the canonical public origin used for comparison.
	 *
	 * @param properties bound application security settings
	 * @param problemWriter shared RFC 9457 response writer
	 */
	public OriginValidationFilter(AuthProperties properties, ApiProblemWriter problemWriter) {
		this.properties = properties;
		this.problemWriter = problemWriter;
	}

	/**
	 * Validates request origin metadata before continuing the servlet filter chain.
	 *
	 * @param request current HTTP request
	 * @param response current HTTP response
	 * @param filterChain remaining filters and request handler
	 * @throws ServletException when downstream filter processing fails
	 * @throws IOException when request or response I/O fails
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (isSafeMethod(request.getMethod())) {
			filterChain.doFilter(request, response);
			return;
		}

		String source = request.getHeader("Origin");
		if (source == null || source.isBlank()) {
			source = request.getHeader("Referer");
		}
		if (source != null && !source.isBlank() && !sameOrigin(source, properties.publicOrigin())) {
			problemWriter.write(request, response, ApiErrorCode.ORIGIN_NOT_ALLOWED);
			return;
		}
		filterChain.doFilter(request, response);
	}

	/**
	 * Compares scheme, host, and effective port while ignoring path and query.
	 *
	 * @param source origin extracted from the request
	 * @param expected configured public origin
	 * @return {@code true} when both values identify the same origin
	 */
	private boolean sameOrigin(String source, String expected) {
		if (source == null || source.isBlank() || expected == null || expected.isBlank()) {
			return false;
		}
		try {
			URI actual = new URI(source);
			URI allowed = new URI(expected);
			return actual.getScheme() != null
					&& allowed.getScheme() != null
					&& actual.getHost() != null
					&& allowed.getHost() != null
					&& actual.getScheme().toLowerCase(Locale.ROOT)
							.equals(allowed.getScheme().toLowerCase(Locale.ROOT))
					&& actual.getHost().toLowerCase(Locale.ROOT)
							.equals(allowed.getHost().toLowerCase(Locale.ROOT))
					&& effectivePort(actual) == effectivePort(allowed);
		} catch (URISyntaxException exception) {
			return false;
		}
	}

	private int effectivePort(URI uri) {
		if (uri.getPort() >= 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private boolean isSafeMethod(String method) {
		return "GET".equals(method)
				|| "HEAD".equals(method)
				|| "OPTIONS".equals(method)
				|| "TRACE".equals(method);
	}
}
