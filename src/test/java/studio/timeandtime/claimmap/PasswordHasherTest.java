package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
	private static final int FAST = 10_000; // minimum accepted, keeps tests quick

	@Test
	void roundTripsAModernHash() {
		String stored = PasswordHasher.hash("correct horse", FAST);
		assertTrue(stored.startsWith("pbkdf2$" + FAST + "$"), stored);
		assertTrue(PasswordHasher.verify(stored, null, "correct horse"));
		assertFalse(PasswordHasher.verify(stored, null, "wrong horse"));
		assertFalse(PasswordHasher.verify(stored, "ignored-salt", ""));
	}

	@Test
	void saltsAreRandom() {
		String a = PasswordHasher.hash("same password", FAST);
		String b = PasswordHasher.hash("same password", FAST);
		assertNotEquals(a, b);
		assertTrue(PasswordHasher.verify(a, null, "same password"));
		assertTrue(PasswordHasher.verify(b, null, "same password"));
	}

	@Test
	void verifiesLegacySaltedSha256() {
		String salt = "0123456789abcdef";
		String legacy = PasswordHasher.sha256Hex(salt + "oldpass");
		assertTrue(PasswordHasher.verify(legacy, salt, "oldpass"));
		assertFalse(PasswordHasher.verify(legacy, salt, "newpass"));
		assertFalse(PasswordHasher.isModern(legacy));
	}

	@Test
	void rejectsMalformedStoredValues() {
		assertFalse(PasswordHasher.verify(null, null, "x"));
		assertFalse(PasswordHasher.verify("pbkdf2$", "", "x"));
		assertFalse(PasswordHasher.verify("pbkdf2$abc$AAAA$BBBB", "", "x"));
		assertFalse(PasswordHasher.verify("pbkdf2$99999999$AAAA$BBBB", "", "x"));
		assertFalse(PasswordHasher.verify("pbkdf2$10000$not-base64$!!!!", "", "x"));
		assertFalse(PasswordHasher.verify("garbage", "", "x"));
	}
}
