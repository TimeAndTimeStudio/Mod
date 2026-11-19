package studio.timeandtime.claimmap.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.timeandtime.claimmap.ServerSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class WorldBackupManagerTest {
	@TempDir
	Path tempDir;

	@Test
	void testZipBackupCreationAndContent() throws IOException {
		Path worldDir = tempDir.resolve("world");
		Path targetBackupDir = tempDir.resolve("backups");
		Files.createDirectories(worldDir);

		// Create dummy world files
		Files.writeString(worldDir.resolve("level.dat"), "dummy level data");
		Files.createDirectories(worldDir.resolve("region"));
		Files.writeString(worldDir.resolve("region").resolve("r.0.0.mca"), "dummy chunk data");
		Files.writeString(worldDir.resolve("session.lock"), "lock");

		Path zipPath = WorldBackupManager.createZipBackup(worldDir, targetBackupDir, 5);
		assertNotNull(zipPath);
		assertTrue(Files.exists(zipPath));

		// Verify zip contents
		try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
			assertNotNull(zipFile.getEntry("level.dat"));
			assertNotNull(zipFile.getEntry("region/r.0.0.mca"));
			// session.lock should be skipped
			assertNull(zipFile.getEntry("session.lock"));
		}
	}

	@Test
	void testBackupRotation() throws IOException, InterruptedException {
		Path backupDir = tempDir.resolve("backups_rotation");
		Files.createDirectories(backupDir);

		// Create 5 dummy backup files with different timestamps
		for (int i = 1; i <= 5; i++) {
			Path file = backupDir.resolve("backup_world_2026-11-19_18-00-0" + i + ".zip");
			Files.writeString(file, "dummy " + i);
			Thread.sleep(20);
		}

		assertEquals(5, Files.list(backupDir).count());

		// Rotate to keep max 3
		WorldBackupManager.rotateBackups(backupDir, 3);

		assertEquals(3, Files.list(backupDir).count());
		// Oldest ones (01, 02) should be removed
		assertFalse(Files.exists(backupDir.resolve("backup_world_2026-11-19_18-00-01.zip")));
		assertFalse(Files.exists(backupDir.resolve("backup_world_2026-11-19_18-00-02.zip")));
		assertTrue(Files.exists(backupDir.resolve("backup_world_2026-11-19_18-00-05.zip")));
	}

	@Test
	void testServerSettingsBackupFields() {
		Path settingsFile = tempDir.resolve("setting.json");
		ServerSettings settings = new ServerSettings(settingsFile);

		// Verify defaults
		assertTrue(settings.backupEnabled);
		assertEquals("backups", settings.backupPath);
		assertEquals(10, settings.maxBackups);

		// Modify and save
		settings.backupEnabled = false;
		settings.backupPath = "/custom/path/backups";
		settings.maxBackups = 15;
		settings.save();

		// Reload
		ServerSettings reloaded = new ServerSettings(settingsFile);
		assertFalse(reloaded.backupEnabled);
		assertEquals("/custom/path/backups", reloaded.backupPath);
		assertEquals(15, reloaded.maxBackups);
	}
}
