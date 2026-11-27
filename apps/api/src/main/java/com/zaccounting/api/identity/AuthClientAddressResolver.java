package com.zaccounting.api.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Derives a privacy-preserving network key for authentication rate limiting.
 *
 * <p>Forwarded client addresses are considered only when the direct peer matches
 * a configured trusted proxy CIDR. The resulting key groups IPv4 addresses by
 * /24 and IPv6 addresses by /64.
 */
@Component
final class AuthClientAddressResolver {

	private final List<IpAddressMatcher> trustedProxies;

	/**
	 * Parses the comma-separated trusted proxy CIDR setting.
	 *
	 * @param properties bound proxy trust settings
	 */
	AuthClientAddressResolver(AuthProperties properties) {
		String configuredCidrs = properties.trustedProxyCidrs();
		this.trustedProxies = configuredCidrs == null || configuredCidrs.isBlank()
				? List.of()
				: Arrays.stream(configuredCidrs.split(","))
						.map(String::trim)
						.filter(value -> !value.isEmpty())
						.map(IpAddressMatcher::new)
						.toList();
	}

	/**
	 * Resolves the client network from the peer address and trusted proxy chain.
	 *
	 * @param request servlet request containing peer and optional forwarding data
	 * @return normalized IPv4 /24 or IPv6 /64 key, or a shared unknown key if invalid
	 */
	String clientNetwork(HttpServletRequest request) {
		String peerAddress = request.getRemoteAddr();
		String clientAddress = peerAddress;
		if (isTrustedProxy(peerAddress)) {
			clientAddress = forwardedClientAddress(request.getHeader("X-Forwarded-For"), peerAddress);
		}
		return networkPrefix(clientAddress);
	}

	/**
	 * Walks the forwarding chain from the trusted peer toward the originating client.
	 *
	 * @param forwardedFor raw {@code X-Forwarded-For} header
	 * @param peerAddress direct socket peer address
	 * @return first untrusted address in the chain, or the peer when the chain is invalid
	 */
	private String forwardedClientAddress(String forwardedFor, String peerAddress) {
		if (forwardedFor == null || forwardedFor.isBlank()) {
			return peerAddress;
		}

		String[] addresses = forwardedFor.split(",");
		String currentAddress = peerAddress;
		for (int index = addresses.length - 1; index >= 0 && isTrustedProxy(currentAddress); index--) {
			String candidate = addresses[index].trim();
			if (!isNumericAddress(candidate)) {
				return peerAddress;
			}
			currentAddress = candidate;
		}
		return currentAddress;
	}

	private boolean isTrustedProxy(String address) {
		if (!isNumericAddress(address)) {
			return false;
		}
		return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
	}

	private String networkPrefix(String address) {
		try {
			byte[] bytes = parseNumericAddress(address).getAddress();
			if (bytes.length == 4) {
				return "ipv4/24:" + HexFormat.of().formatHex(bytes, 0, 3);
			}
			return "ipv6/64:" + HexFormat.of().formatHex(bytes, 0, 8);
		} catch (UnknownHostException exception) {
			return "unknown-client-network";
		}
	}

	private boolean isNumericAddress(String address) {
		if (address == null || address.isBlank() || !address.matches("[0-9a-fA-F:.]+")) {
			return false;
		}
		return address.indexOf('.') >= 0 || address.indexOf(':') >= 0;
	}

	private InetAddress parseNumericAddress(String address) throws UnknownHostException {
		if (!isNumericAddress(address)) {
			throw new UnknownHostException("Client address is not numeric");
		}
		return InetAddress.getByName(address);
	}
}
