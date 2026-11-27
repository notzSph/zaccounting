/**
 * Provides the shared API error catalog, typed failures, request identifiers,
 * and RFC 9457 problem response handling.
 *
 * <p>Application modules raise {@link com.zaccounting.api.common.error.ApiException}
 * with an {@link com.zaccounting.api.common.error.ApiErrorCode}. Controllers do
 * not construct ad hoc error bodies. Public details are fixed safe descriptions;
 * internal exception causes are kept for server-side diagnostics only.
 */
package com.zaccounting.api.common.error;
