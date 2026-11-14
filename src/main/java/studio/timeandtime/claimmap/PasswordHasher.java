package studio.timeandtime.claimmap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * PBKDF2 password hashing (PHASES.md 3.5).
 *
 * <p>Stored form: {@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}.
 * The legacy salted SHA-256 form ({@code salt} + {@code hash} fields) is still
 * accepted and upgraded on the next successful login.
 */
public final class PasswordHasher {
	public static final int DEFAULT_ITERATIONS = 210_000;
	private static final int SALT_BYTES = 16;
	private static final int KEY_BITS = 256;
	private static final int MIN_ITERATIONS = 10_000;
	private static final int MAX_ITERATIONS = 2_000_000;
	private static final SecureRandom RANDOM = new SecureRandom();

	private PasswordHasher() {
	}

	public static String hash(String plain, int iterations) {
		int iter = Math.max(MIN_ITERATIONS, Math.min(MAX_ITERATIONS, iterations));
		byte[] salt = new byte[SALT_BYTES];
		RANDOM.nextBytes(salt);
		byte[] key = derive(plain, salt, iter);
		return "pbkdf2$" + iter + "$" + Base64.getEncoder().encodeToString(salt)
				+ "$" + Base64.getEncoder().encodeToString(key);
	}

	public static boolean isModern(String stored) {
		return stored != null && stored.startsWith("pbkdf2$");
	}

	/**
	 * Verifies either a PBKDF2 hash or a legacy SHA-256 hash. Constant-time
	 * comparison; malformed stored values simply fail.
	 */
	public static boolean verify(String stored, String legacySalt, String plain) {
		if (stored == null || plain == null) return false;
		if (!isModern(stored)) {
			String salt = legacySalt == null ? "" : legacySalt;
			return constantTimeEquals(sha256Hex(salt + plain), stored);
		}
		String[] parts = stored.split("\\$", 4);
		if (parts.length != 4) return false;
		int iterations;
		try {
			iterations = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			return false;
		}
		if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) return false;
		try {
			byte[] salt = Base64.getDecoder().decode(parts[2]);
			byte[] expected = Base64.getDecoder().decode(parts[3]);
			byte[] actual = derive(plain, salt, iterations);
			return MessageDigest.isEqual(expected, actual);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static byte[] derive(String plain, byte[] salt, int iterations) {
		PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, KEY_BITS);
		try {
			SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
			return factory.generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException("PBKDF2 unavailable", e);
		} finally {
			spec.clearPassword();
		}
	}

	static String sha256Hex(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) return false;
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
