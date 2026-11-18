package studio.timeandtime.claimmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionManagerTest {

	@TempDir
	Path tmp;

	private ClaimMapConfig config() {
		return new ClaimMapConfig(tmp.resolve("config/timesmp.properties"));
	}

	@Test
	void ownerCanTogglePvpAndItPersists() throws Exception {
		ClaimMapConfig c = config();
		RegionManager regions = new RegionManager(c);
		// Seed one claimed cell owned by Alice.
		assertEquals(RegionManager.ClaimResult.OK, regions.claim(0, 0, "u-alice", "Alice"));
		RegionManager.CellData cell = regions.get("0,0");
		assertTrue(cell.pvp, "new claims default to PvP on");

		// A stranger cannot change the flag.
		assertFalse(regions.setPvp("0,0", "u-bob", false, false));
		assertTrue(regions.get("0,0").pvp);

		// The owner can turn it off.
		assertTrue(regions.setPvp("0,0", "u-alice", false, false));
		assertFalse(regions.get("0,0").pvp);

		// The change is written to disk and survives a reload.
		RegionManager reloaded = new RegionManager(c);
		reloaded.load();
		RegionManager.CellData reloadedCell = reloaded.get("0,0");
		assertFalse(reloadedCell.pvp, "pvp flag must persist across reload");
	}

	@Test
	void adminCanTogglePvpButNotOnMissingCell() {
		ClaimMapConfig c = config();
		RegionManager regions = new RegionManager(c);
		regions.claim(1, 1, "u-alice", "Alice");

		assertTrue(regions.setPvp("1,1", "u-admin", false, true));
		assertFalse(regions.get("1,1").pvp);

		// No such cell.
		assertFalse(regions.setPvp("9,9", "u-admin", false, true));
	}
}
