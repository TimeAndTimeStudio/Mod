package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapConfigTest {

	private ClaimMapConfig config() {
		return new ClaimMapConfig(Path.of("unused.properties"));
	}

	@Test
	void emptyPlayerListAllowsEveryone() {
		ClaimMapConfig c = config();
		c.players = "";
		assertTrue(c.isAllowed("Alice"));
		assertTrue(c.isAllowed("anyone"));
		// null / blank names are never allowed
		assertFalse(c.isAllowed(null));
		assertFalse(c.isAllowed(""));
		assertFalse(c.isAllowed("   "));
	}

	@Test
	void playerListIsTrimmedAndCaseInsensitive() {
		ClaimMapConfig c = config();
		c.players = " Alice , Bob ,, Carol ";
		assertTrue(c.isAllowed("alice"));
		assertTrue(c.isAllowed("ALICE"));
		assertTrue(c.isAllowed("  Bob "));
		assertTrue(c.isAllowed("carol"));
		assertFalse(c.isAllowed("Dave"));
		assertFalse(c.isAllowed("Alicia"));
	}

	@Test
	void adminsAlwaysPassEvenOutsidePlayers() {
		ClaimMapConfig c = config();
		c.players = "Bob";
		c.admins = " Alice ";
		assertTrue(c.isAllowed("alice"));
		assertTrue(c.isAdmin("ALICE"));
		assertFalse(c.isAdmin("Bob"));
		assertTrue(c.isAllowed("bob"));
	}

	@Test
	void adminListIgnoresEmptyEntries() {
		ClaimMapConfig c = config();
		c.admins = " , ,  ,";
		assertFalse(c.isAdmin("Alice"));
		assertFalse(c.isAdmin(""));
		assertFalse(c.isAdmin(null));
	}

	@Test
	void loadCreatesTheNewKeysForExistingConfigs(@TempDir Path tmp) throws Exception {
		Path file = tmp.resolve("claimmap.properties");
		Files.writeString(file, "port=8085\nbindAddress=127.0.0.1\ntoken=deadbeef\n", StandardCharsets.UTF_8);

		ClaimMapConfig c = new ClaimMapConfig(file);
		c.load();

		// Values already in the owner's file are never overwritten.
		assertEquals("127.0.0.1", c.bindAddress);
		String saved = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(saved.contains("players="), saved);
		assertTrue(saved.contains("admins="), saved);
		assertTrue(saved.contains("bindAddress=127.0.0.1"), saved);
	}

	@Test
	void firstStartDefaultsToAllInterfacesAndWritesFile(@TempDir Path tmp) throws Exception {
		Path file = tmp.resolve("sub/claimmap.properties");
		ClaimMapConfig c = new ClaimMapConfig(file);
		c.load();

		assertEquals("0.0.0.0", c.bindAddress);
		assertTrue(Files.exists(file));
		String saved = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(saved.contains("bindAddress=0.0.0.0"), saved);
		assertTrue(saved.contains("players="), saved);
		assertTrue(saved.contains("admins="), saved);
	}
}
