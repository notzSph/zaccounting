package com.zaccounting.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;

import com.zaccounting.api.common.error.ApiErrorCode;
import com.zaccounting.api.common.error.ApiProblemWriter;
import com.zaccounting.api.identity.AuthProperties;
import com.zaccounting.api.identity.AuthService;
import com.zaccounting.api.identity.OriginValidationFilter;
import com.zaccounting.api.identity.SessionAuthenticationFilter;

/**
 * Defines the stateless API security chain for the current identity implementation.
 *
 * <p>CSRF protection remains enabled for cookie-authenticated writes. Only health,
 * CSRF bootstrap, and authentication routes are public; the current-user route
 * requires a valid session, and every other route is denied until explicitly
 * assigned an authorization policy.
 */
@Configuration
public class SecurityConfig {

	/**
	 * Builds the security chain with CSRF, origin validation, and opaque cookie sessions.
	 *
	 * @param http Spring Security chain builder
	 * @param authService service used to resolve session cookies
	 * @param authProperties cookie and origin settings
	 * @param problemWriter shared RFC 9457 response writer for filter-level failures
	 * @return configured stateless security filter chain
	 * @throws Exception when Spring Security cannot build the chain
	 */
	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		AuthService authService,
		AuthProperties authProperties,
		ApiProblemWriter problemWriter) throws Exception {
		CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfTokenRepository.setCookieCustomizer(cookie -> cookie
				.path("/")
				.sameSite("Lax")
				.secure(authProperties.sessionCookieSecure()));

		http
				.csrf(csrf -> csrf
						.csrfTokenRepository(csrfTokenRepository)
						.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
				.securityContext(context -> context
						.securityContextRepository(new NullSecurityContextRepository()))
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/auth/csrf").permitAll()
						.requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
								"/api/v1/auth/logout", "/api/v1/auth/verify-email",
								"/api/v1/auth/resend-verification", "/api/v1/auth/forgot-password",
								"/api/v1/auth/reset-password").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
						.anyRequest().denyAll())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint((request, response, exception) ->
								problemWriter.write(request, response, ApiErrorCode.AUTHENTICATION_REQUIRED))
						.accessDeniedHandler((request, response, exception) ->
								problemWriter.write(request, response, ApiErrorCode.ACCESS_DENIED)))
				.addFilterBefore(new OriginValidationFilter(authProperties, problemWriter),
						org.springframework.security.web.csrf.CsrfFilter.class)
				.addFilterBefore(new SessionAuthenticationFilter(authService, authProperties, problemWriter),
						UsernamePasswordAuthenticationFilter.class)
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable());

		return http.build();
	}
}
