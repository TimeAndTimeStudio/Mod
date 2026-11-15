package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerItemStoreTest {
	@TempDir
	Path dir;

	private static ItemSources.Entry entry(String ref, String area, String itemId, int count,
			String container, int slot, Integer x, Integer y, Integer z) {
		return new ItemSources.Entry(ref, area, itemId, "Oak Log", count,
				container, slot, "minecraft:overworld", x, y, z);
	}

	@Test
	void writesOneJsonFilePerPlayerWithExactLocation() throws Exception {
		PlayerItemStore store = new PlayerItemStore(dir.resolve("player-items"));
		UUID uuid = UUID.randomUUID();
		store.record(uuid, "Steve", "online", List.of(
				entry("inv|5", "กระเป๋า", "minecraft:oak_log", 12, "inventory", 5, 10, 64, -20),
				entry("container|minecraft:overworld|1|2|3|0", "Chest (1, 2, 3)",
						"minecraft:diamond", 3, "container", 0, 1, 2, 3)));

		Path file = dir.resolve("player-items").resolve(uuid + ".json");
		assertTrue(Files.exists(file));
		String json = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"slot\":5"));
		assertTrue(json.contains("\"x\":1"));
		assertTrue(json.contains("\"container\":\"container\""));
		assertTrue(json.contains("\"totalCount\":15"));

		PlayerItemStore.Snapshot loaded = store.get(uuid).orElseThrow();
		assertEquals("Steve", loaded.name());
		assertEquals(2, loaded.items().size());
		assertEquals(15, loaded.totalCount());
		assertEquals(5, loaded.items().get(0).slot());
		assertEquals(1, loaded.items().get(1).x());
	}

	@Test
	void identicalScanDoesNotRewriteTheFile() {
		PlayerItemStore store = new PlayerItemStore(dir.resolve("player-items"));
		UUID uuid = UUID.randomUUID();
		List<ItemSources.Entry> rows = List.of(entry("inv|0", "กระเป๋า", "minecraft:stone", 4,
				"inventory", 0, 0, 64, 0));
		PlayerItemStore.Snapshot first = store.record(uuid, "Alex", "online", rows);
		PlayerItemStore.Snapshot second = store.record(uuid, "Alex", "online", rows);
		assertEquals(first.updatedAt(), second.updatedAt());
	}

	@Test
	void carriedRefreshKeepsContainerRowsButReplacesInventory() {
		PlayerItemStore store = new PlayerItemStore(dir.resolve("player-items"));
		UUID uuid = UUID.randomUUID();
		store.record(uuid, "Alex", "online", List.of(
				entry("inv|0", "กระเป๋า", "minecraft:stone", 4, "inventory", 0, 0, 64, 0),
				entry("container|minecraft:overworld|1|2|3|0", "Chest (1, 2, 3)",
						"minecraft:diamond", 3, "container", 0, 1, 2, 3)));

		PlayerItemStore.Snapshot merged = store.mergeCarried(uuid, "Alex", List.of());
		assertEquals(1, merged.items().size());
		assertEquals("container", merged.items().get(0).container());
		assertFalse(merged.items().stream().anyMatch(e -> e.ref().startsWith("inv|")));
	}
}
