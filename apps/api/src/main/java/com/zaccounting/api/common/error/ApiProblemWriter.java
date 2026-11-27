package com.zaccounting.api.common.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** Writes the shared problem response directly from servlet security filters. */
@Component
public final class ApiProblemWriter {
	private final ObjectMapper objectMapper;

	/**
	 * Creates the writer with the application's configured JSON mapper.
	 *
	 * @param objectMapper configured mapper with Spring ProblemDetail support
	 */
	public ApiProblemWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Writes a no-store RFC 9457 response for a filter-chain failure.
	 *
	 * @param request current servlet request
	 * @param response response to populate
	 * @param errorCode stable public error classification
	 * @throws IOException if serialization or response output fails
	 */
	public void write(
			HttpServletRequest request,
			HttpServletResponse response,
			ApiErrorCode errorCode) throws IOException {
		if (response.isCommitted()) {
			return;
		}

		ProblemDetail problem = ApiProblemFactory.create(errorCode, request);
		response.setStatus(errorCode.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader("Cache-Control", "no-store");
		response.setHeader(RequestIdFilter.HEADER_NAME, RequestIdFilter.requestId(request));
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
