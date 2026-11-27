/**
 * Implements local account identity, password authentication, cookie sessions,
 * email verification, password recovery, request throttling, and queued email
 * delivery for the versioned authentication API.
 *
 * <p>HTTP inputs are validated at the controller boundary and normalized before
 * identity comparisons. Password work uses Spring Security's password encoder;
 * session and one-time token values are returned only to their intended caller
 * and persisted as digests. Email challenges are placed in a database-backed,
 * encrypted outbox so request handling does not wait for SMTP delivery.
 *
 * <p>This package currently does not implement MFA or business-scoped
 * authorization. Those capabilities and the related schema migration work are
 * deferred. The package documentation describes the present implementation and
 * is not a security certification.
 *
 * @see com.zaccounting.api.identity.AuthController
 * @see com.zaccounting.api.identity.AuthService
 */
package com.zaccounting.api.identity;
