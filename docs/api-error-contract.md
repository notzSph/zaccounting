# API error contract

All API modules use the shared implementation in `apps/api/src/main/java/com/zaccounting/api/common/error/`.

## Response shape

Errors are returned as RFC 9457 `application/problem+json` documents. Clients branch on `code`, not on title or detail text.

```json
{
  "type": "urn:zaccounting:error:access_denied",
  "title": "Access denied",
  "status": 403,
  "detail": "You do not have permission to perform this operation.",
  "code": "ACCESS_DENIED",
  "requestId": "2a208f55-fc7f-4837-88bd-0f558f800166"
}
```

Validation responses may include an `errors` array containing field names, validation codes, and safe constraint messages. They never include submitted values. Responses carry the same server-generated ID in the `X-Request-ID` header and `requestId` property.

## Raising errors

- Expected application failures throw `ApiException` with an `ApiErrorCode`.
- The catalog owns HTTP status, stable code, title, and caller-safe detail.
- Rate-limit failures may include a positive retry delay, returned through `Retry-After`.
- Do not interpolate account names, emails, tokens, database errors, or arbitrary client values into public detail text.
- Unexpected failures are logged with the request ID and returned as a generic `INTERNAL_ERROR`.
- Spring MVC and Security filter failures use the same problem shape. Authentication, authorization, origin, and CSRF failures remain generic to avoid revealing protected resource details.
- Account-existence-sensitive flows may keep a generic success response. If an internal database or delivery operation fails, log the failure with the request ID while preserving the generic public response.

## Current codes

| Code | Status | Use |
| --- | ---: | --- |
| `VALIDATION_FAILED` | 400 | Request fields or normalized values are invalid. |
| `MALFORMED_REQUEST` | 400 | The body cannot be parsed. |
| `REQUEST_REJECTED` | 4xx | A framework-level client request cannot be processed. |
| `METHOD_NOT_ALLOWED` | 405 | The HTTP method is not supported for the route. |
| `PAYLOAD_TOO_LARGE` | 413 | The request exceeds the configured size limit. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request content type is unsupported. |
| `AUTHENTICATION_REQUIRED` | 401 | A protected route has no valid authenticated principal. |
| `AUTHENTICATION_FAILED` | 401 | Credentials or account eligibility do not allow sign-in. |
| `ACCESS_DENIED` | 403 | The authenticated caller lacks permission. |
| `ORIGIN_NOT_ALLOWED` | 403 | The browser request origin is not permitted. |
| `RESOURCE_NOT_FOUND` | 404 | The requested resource or route does not exist. |
| `ACCOUNT_IDENTIFIER_CONFLICT` | 409 | An email or username cannot be registered. |
| `REQUEST_CONFLICT` | 409 | The request conflicts with current resource state. |
| `INVALID_OR_EXPIRED_TOKEN` | 400 | A one-time link is invalid, expired, or consumed. |
| `RATE_LIMITED` | 429 | A shared or local request limit was exceeded. |
| `EMAIL_DELIVERY_UNAVAILABLE` | 503 | Required account email delivery is not configured. |
| `AUTHENTICATION_UNAVAILABLE` | 503 | Required authentication configuration is missing. |
| `DEPENDENCY_UNAVAILABLE` | 503 | A required database or service operation failed. |
| `INTERNAL_ERROR` | 500 | An unclassified internal failure occurred. |

Add a code only when it gives clients a useful, stable distinction. Keep transport status and internal exception cause separate: the caller receives the safe catalog entry, while logs tied to the request ID retain diagnostic context.
