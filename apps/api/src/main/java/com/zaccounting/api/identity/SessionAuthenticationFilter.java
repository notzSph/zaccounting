package com.zaccounting.api.identity;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiProblemWriter;
import com.zaccounting.api.common.error.RequestIdFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the configured session cookie and installs its active user as the security principal.
 *
 * <p>The filter does not accept bearer tokens. Session validity, expiry, revocation,
 * and user eligibility are checked by {@link AuthService#resolveSession(String)}.
 */
public final class SessionAuthenticationFilter extends OncePerRequestFilter {
	private static final Logger logger = LoggerFactory.getLogger(SessionAuthenticationFilter.class);

	private final AuthService authService;
	private final AuthProperties properties;
	private final ApiProblemWriter problemWriter;

	/**
	 * Creates the filter with the session resolver and cookie settings.
	 *
	 * @param authService service that validates persisted sessions
	 * @param properties settings including the session cookie name
	 * @param problemWriter shared writer for failures raised before MVC dispatch
	 */
	public SessionAuthenticationFilter(
			AuthService authService,
			AuthProperties properties,
			ApiProblemWriter problemWriter) {
		this.authService = authService;
		this.properties = properties;
		this.problemWriter = problemWriter;
	}

	/**
	 * Resolves a cookie session, populates the security context when valid, then continues.
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
		String token = sessionToken(request);
		if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				authService.resolveSession(token).ifPresent(user -> {
					UsernamePasswordAuthenticationToken authentication =
							UsernamePasswordAuthenticationToken.authenticated(user, null, java.util.List.of());
					authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
					SecurityContextHolder.getContext().setAuthentication(authentication);
				});
			} catch (DataAccessException exception) {
				logger.error(
						"Session lookup failed requestId={} exceptionType={}",
						RequestIdFilter.requestId(request),
						exception.getClass().getSimpleName(),
						exception);
				problemWriter.write(request, response, ApiErrorCode.DEPENDENCY_UNAVAILABLE);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private String sessionToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (properties.sessionCookieName().equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
