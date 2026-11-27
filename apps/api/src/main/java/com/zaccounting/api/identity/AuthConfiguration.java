package com.zaccounting.api.identity;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides authentication cryptographic components and scheduled-task support.
 *
 * <p>The password encoder is Spring Security's Argon2 implementation. The email
 * outbox key is required at startup and must decode from Base64 to exactly 32
 * bytes. Keep this key separate from the database because queued payloads require
 * it for delivery.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
public class AuthConfiguration {

	/**
	 * Creates the password encoder used for account credentials.
	 *
	 * @return Argon2 encoder configured with a 16-byte salt, 32-byte hash,
	 *         parallelism 4, 64 MiB memory cost, and 3 iterations
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new Argon2PasswordEncoder(16, 32, 4, 65_536, 3);
	}

	/**
	 * Creates the cryptographically secure random source used for opaque tokens.
	 *
	 * @return a JDK {@link SecureRandom} instance
	 */
	@Bean
	SecureRandom secureRandom() {
		return new SecureRandom();
	}

	/**
	 * Creates the authenticated encryptor for queued authentication email payloads.
	 *
	 * @param properties configured authentication secrets
	 * @return an AES-GCM byte encryptor backed by the configured key
	 * @throws IllegalStateException when the key is missing, invalid Base64, or not 32 bytes
	 */
	@Bean
	BytesEncryptor authOutboxEncryptor(AuthProperties properties) {
		String configuredKey = properties.outboxEncryptionKey();
		if (configuredKey == null || configuredKey.isBlank()) {
			throw new IllegalStateException("AUTH_OUTBOX_ENCRYPTION_KEY must be configured");
		}

		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(configuredKey);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("AUTH_OUTBOX_ENCRYPTION_KEY must be valid Base64", exception);
		}
		if (keyBytes.length != 32) {
			throw new IllegalStateException("AUTH_OUTBOX_ENCRYPTION_KEY must decode to 32 bytes");
		}

		SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
		return AesGcmBytesEncryptor.withSecretKey(secretKey).build();
	}

}
