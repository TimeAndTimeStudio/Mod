package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old accounts.json shapes must keep working without asking the owner to edit them. */
class AccountManagerMigrationTest {
	@TempDir
	Path tmp;

	private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private final UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private ClaimMapConfig config() {
		ClaimMapConfig cfg = new ClaimMapConfig(tmp.resolve("claimmap.properties"));
		cfg.pbkdf2Iterations = 10_000; // keeps tests fast
		return cfg;
	}

	@Test
	void legacySha256AccountWithUuidKeepsWorkingAndIsRehashed() throws Exception {
		String salt = "0123456789abcdef";
		String hash = PasswordHasher.sha256Hex(salt + "oldpass");
		Files.writeString(tmp.resolve("accounts.json"),
				"{\"accounts\":[{\"uuid\":\"" + alice + "\",\"name\":\"Alice\",\"salt\":\"" + salt
						+ "\",\"hash\":\"" + hash + "\",\"createdAt\":1,\"updatedAt\":1}]}",
				StandardCharsets.UTF_8);

		AccountManager accounts = new AccountManager(config());
		accounts.load();
		assertTrue(accounts.hasPassword(alice.toString()));
		AccountManager.Account account = accounts.verify("Alice", "oldpass");
		assertNotNull(account);
		assertEquals("Alice", account.name);
		assertTrue(PasswordHasher.isModern(account.hash), "successful login upgrades to PBKDF2");
		assertTrue(Files.readString(tmp.resolve("accounts.json"), StandardCharsets.UTF_8).contains("pbkdf2$"));
	}

	@Test
	void legacyAccountWithoutUuidIsBoundOnTheNextJoin() throws Exception {
		Files.writeString(tmp.resolve("accounts.json"),
				"{\"accounts\":[{\"name\":\"Bob\",\"salt\":\"feedfacecafebeef\",\"hash\":\""
						+ PasswordHasher.sha256Hex("feedfacecafebeef" + "bobpass")
						+ "\",\"createdAt\":1,\"updatedAt\":1}]}",
				StandardCharsets.UTF_8);

		AccountManager accounts = new AccountManager(config());
		accounts.load();
		assertFalse(accounts.hasPassword(bob.toString()), "not bound yet");

		assertTrue(accounts.bindUuid("Bob", bob));
		assertTrue(accounts.hasPassword(bob.toString()));
		assertEquals("Bob", accounts.nameOf(bob).orElseThrow());
		assertNotNull(accounts.verify("Bob", "bobpass"));
		String saved = Files.readString(tmp.resolve("accounts.json"), StandardCharsets.UTF_8);
		assertTrue(saved.contains(bob.toString()));
	}

	@Test
	void plainNameKeyedMapAndPendingRecordsSurviveASave() throws Exception {
		Files.writeString(tmp.resolve("accounts.json"),
				"{\"Bob\":{\"salt\":\"0011223344556677\",\"hash\":\""
						+ PasswordHasher.sha256Hex("0011223344556677" + "bobpass") + "\"}}",
				StandardCharsets.UTF_8);

		AccountManager accounts = new AccountManager(config());
		accounts.load();
		// Saving for an unrelated account must not drop the legacy record.
		assertTrue(accounts.setPasswordHash(alice.toString(), "Alice", PasswordHasher.hash("alicepass", 10_000)));

		AccountManager reloaded = new AccountManager(config());
		reloaded.load();
		assertTrue(reloaded.bindUuid("Bob", bob), "legacy record was kept in the file");
		assertNotNull(reloaded.verify("Bob", "bobpass"));
	}
}
