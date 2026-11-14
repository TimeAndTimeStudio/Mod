package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMigrationTest {
	@TempDir
	Path tmp;

	@Test
	void copiesExistingFilesOnceAndKeepsThemUnchanged() throws Exception {
		String accounts = "{\"accounts\":[]}";
		Files.writeString(tmp.resolve("accounts.json"), accounts, StandardCharsets.UTF_8);
		Files.writeString(tmp.resolve("sessions.json"), "{\"sessions\":[]}", StandardCharsets.UTF_8);
		Files.writeString(tmp.resolve("regions.json"), "{}", StandardCharsets.UTF_8);

		Path backup = DataMigration.backupBeforeUpgrade(tmp);
		assertNotNull(backup);
		assertTrue(Files.isDirectory(backup));
		assertEquals(accounts, Files.readString(backup.resolve("accounts.json"), StandardCharsets.UTF_8));
		assertTrue(Files.exists(backup.resolve("sessions.json")));
		assertTrue(Files.exists(backup.resolve("regions.json")));
		assertTrue(Files.exists(tmp.resolve("backups/.pre-upgrade-done")));

		// The originals are only copied, never moved or rewritten.
		assertEquals(accounts, Files.readString(tmp.resolve("accounts.json"), StandardCharsets.UTF_8));
		// A second start must not create another pre-upgrade directory.
		assertNull(DataMigration.backupBeforeUpgrade(tmp));
		List<Path> dirs;
		try (var stream = Files.list(tmp.resolve("backups"))) {
			dirs = stream.filter(Files::isDirectory).toList();
		}
		assertEquals(1, dirs.size());
	}

	@Test
	void freshInstallHasNothingToBackUp() {
		assertNull(DataMigration.backupBeforeUpgrade(tmp));
	}

	@Test
	void oldPropertiesAreFilledWithoutOverwritingOwnerValues() throws Exception {
		Path file = tmp.resolve("claimmap.properties");
		Files.writeString(file, "port=9090\nbindAddress=127.0.0.1\nmaxCellsPerPlayer=9\n", StandardCharsets.UTF_8);

		ClaimMapConfig config = new ClaimMapConfig(file);
		config.load();

		assertEquals(9090, config.port);
		assertEquals("127.0.0.1", config.bindAddress, "owner value must win");
		assertEquals(9, config.maxCellsPerPlayer);
		assertEquals("", config.players, "missing keys get their default");
		assertTrue(config.token.length() > 20);
		String saved = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(saved.contains("players="));
		assertTrue(saved.contains("bindAddress=127.0.0.1"));
		assertTrue(saved.contains("maxCellsPerPlayer=9"));

		// The pre-upgrade copy was made before the first write.
		try (var stream = Files.list(tmp.resolve("backups"))) {
			Path backup = stream.filter(Files::isDirectory).findFirst().orElseThrow();
			String original = Files.readString(backup.resolve("claimmap.properties"), StandardCharsets.UTF_8);
			assertEquals("port=9090\nbindAddress=127.0.0.1\nmaxCellsPerPlayer=9\n", original);
		}
	}
}
