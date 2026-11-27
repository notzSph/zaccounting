package com.zaccounting.api.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispatches queued authentication messages through the configured SMTP sender.
 *
 * <p>Delivery is polled asynchronously. SMTP secrets are never logged. Transient
 * failures are retried by the outbox; a permanently failed challenge is invalidated.
 */
@Component
final class AuthMailService {

	private static final Logger logger = LoggerFactory.getLogger(AuthMailService.class);

	private final ObjectProvider<JavaMailSender> senderProvider;
	private final AuthProperties authProperties;
	private final AuthEmailOutbox outbox;
	private final AuthService authService;
	private final String smtpHost;
	private final String smtpUsername;
	private final String smtpPassword;
	private final String fromAddress;

	/**
	 * Creates the scheduled dispatcher and captures mail settings supplied by Spring.
	 *
	 * @param senderProvider optional configured Spring mail sender
	 * @param authProperties public frontend URL settings
	 * @param outbox durable email job queue
	 * @param authService service used to invalidate challenges after permanent failure
	 * @param smtpHost configured SMTP host
	 * @param smtpUsername configured SMTP username
	 * @param smtpPassword configured SMTP password
	 * @param fromAddress configured sender address
	 */
	AuthMailService(
			ObjectProvider<JavaMailSender> senderProvider,
			AuthProperties authProperties,
			AuthEmailOutbox outbox,
			AuthService authService,
			@Value("${spring.mail.host:}") String smtpHost,
			@Value("${spring.mail.username:}") String smtpUsername,
			@Value("${spring.mail.password:}") String smtpPassword,
			@Value("${app.mail.from:}") String fromAddress) {
		this.senderProvider = senderProvider;
		this.authProperties = authProperties;
		this.outbox = outbox;
		this.authService = authService;
		this.smtpHost = smtpHost;
		this.smtpUsername = smtpUsername;
		this.smtpPassword = smtpPassword;
		this.fromAddress = fromAddress;
	}

	/**
	 * Reports whether SMTP credentials, sender address, and a mail sender are available.
	 *
	 * @return {@code true} when all required delivery settings are present
	 */
	boolean isConfigured() {
		return smtpHost != null
				&& !smtpHost.isBlank()
				&& smtpUsername != null
				&& !smtpUsername.isBlank()
				&& smtpPassword != null
				&& !smtpPassword.isBlank()
				&& fromAddress != null
				&& !fromAddress.isBlank()
				&& senderProvider.getIfAvailable() != null;
	}

	/**
	 * Claims and processes at most one queued email job on each scheduled poll.
	 */
	@Scheduled(fixedDelayString = "${app.auth-mail.poll-interval:2000}")
	void dispatchNext() {
		if (!isConfigured()) {
			return;
		}

		outbox.claimNext().ifPresent(job -> {
			AuthEmailOutbox.EmailMessage email = null;
			try {
				email = outbox.decrypt(job);
				if (!"NOOP".equals(email.messageType())) {
					send(email);
				}
				outbox.markSent(job);
			} catch (RuntimeException exception) {
				logger.warn(
						"Authentication email delivery attempt failed jobId={} attempt={} exceptionType={}",
						job.id(),
						job.attempts(),
						exception.getClass().getSimpleName(),
						exception);
				boolean permanentlyFailed = outbox.markFailedOrRetry(job);
				if (permanentlyFailed && email != null) {
					authService.invalidateChallenge(challenge(email));
				}
			}
		});
	}

	/**
	 * Reconstructs the service challenge needed to invalidate a terminally failed job.
	 *
	 * @param email decoded outbox message
	 * @return challenge descriptor using the matching token table purpose
	 */
	private AuthService.Challenge challenge(AuthEmailOutbox.EmailMessage email) {
		String tokenPurpose = "EMAIL_VERIFICATION".equals(email.messageType())
				? "email_verification_token"
				: "password_reset_token";
		return new AuthService.Challenge(email.recipient(), email.token(), tokenPurpose);
	}

	/**
	 * Builds and sends a one-time link email through the configured SMTP sender.
	 *
	 * @param email decoded outbox message to deliver
	 * @throws IllegalStateException when the stored message type is unsupported
	 */
	private void send(AuthEmailOutbox.EmailMessage email) {
		String subject;
		String path;
		String intro;
		if ("EMAIL_VERIFICATION".equals(email.messageType())) {
			subject = "Confirm your zAccounting email address";
			path = "/verify-email";
			intro = "Confirm your email address using this link:\n\n";
		} else if ("PASSWORD_RESET".equals(email.messageType())) {
			subject = "Reset your zAccounting password";
			path = "/reset-password";
			intro = "Reset your password using this link:\n\n";
		} else {
			throw new IllegalStateException("Unsupported authentication email type");
		}

		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(email.recipient());
		message.setSubject(subject);
		message.setText(intro + frontendLink(path, email.token()));
		senderProvider.getObject().send(message);
	}

	/**
	 * Builds a frontend challenge URL from configured base URL, path, and token.
	 *
	 * @param path frontend verification or reset path
	 * @param token raw one-time challenge token
	 * @return complete link for the email body
	 */
	private String frontendLink(String path, String token) {
		String baseUrl = authProperties.frontendUrl().replaceAll("/+$", "");
		return baseUrl + path + "?token=" + token;
	}
}
