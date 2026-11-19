package studio.timeandtime.claimmap.world;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class EndArenaManagerTest {
	@TempDir
	Path tempDir;

	private EndArenaManager arena;

	@BeforeEach
	void setUp() {
		arena = new EndArenaManager(tempDir);
		arena.load();
	}

	@Test
	void testArenaBoundaries() {
		// 500x500 area is X: -250..250, Z: -250..250
		assertTrue(EndArenaManager.isInsideArena(0, 0));
		assertTrue(EndArenaManager.isInsideArena(-250, -250));
		assertTrue(EndArenaManager.isInsideArena(250, 250));
		assertTrue(EndArenaManager.isInsideArena(100, -200));

		assertFalse(EndArenaManager.isInsideArena(-251, 0));
		assertFalse(EndArenaManager.isInsideArena(251, 0));
		assertFalse(EndArenaManager.isInsideArena(0, -251));
		assertFalse(EndArenaManager.isInsideArena(0, 251));
	}

	@Test
	void testArenaChunks() {
		// -250 / 16 = -16, 250 / 16 = 15
		assertTrue(EndArenaManager.isArenaChunk(0, 0));
		assertTrue(EndArenaManager.isArenaChunk(-16, -16));
		assertTrue(EndArenaManager.isArenaChunk(15, 15));

		assertFalse(EndArenaManager.isArenaChunk(-17, 0));
		assertFalse(EndArenaManager.isArenaChunk(16, 0));
		assertFalse(EndArenaManager.isArenaChunk(0, -17));
		assertFalse(EndArenaManager.isArenaChunk(0, 16));
	}

	@Test
	void testBlockTrackingAndPersistence() {
		BlockPos p1 = new BlockPos(10, 60, -20);
		BlockPos p2 = new BlockPos(-15, 75, 45);

		assertFalse(arena.isPlayerPlaced(p1));
		arena.recordPlaced(p1);
		arena.recordPlaced(p2);

		assertTrue(arena.isPlayerPlaced(p1));
		assertTrue(arena.isPlayerPlaced(p2));

		arena.save();

		// Load with a new manager instance from same file
		EndArenaManager loaded = new EndArenaManager(tempDir);
		loaded.load();

		assertTrue(loaded.isPlayerPlaced(p1));
		assertTrue(loaded.isPlayerPlaced(p2));

		// Remove block
		loaded.recordBroken(p1);
		assertFalse(loaded.isPlayerPlaced(p1));
		assertTrue(loaded.isPlayerPlaced(p2));
	}
}
