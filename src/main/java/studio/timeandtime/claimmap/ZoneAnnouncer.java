package studio.timeandtime.claimmap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Announces zone (claim cell) enter/exit on the player's action bar.
 * Claims only exist in the main overworld, so other dimensions are ignored.
 * Positions are re-checked every 20 ticks (1 second).
 */
public final class ZoneAnnouncer {
	private static final int TICK_INTERVAL = 20;

	private final ClaimMapConfig config;
	private final RegionManager regions;
	/** uuid string -> last cell id, or "" when standing in unclaimed land. */
	private final Map<String, String> lastCell = new HashMap<>();

	public ZoneAnnouncer(ClaimMapConfig config, RegionManager regions) {
		this.config = config;
		this.regions = regions;
	}

	public void tick(MinecraftServer srv) {
		if (!config.zoneAnnouncements) return;
		if (srv.getTickCount() % TICK_INTERVAL != 0) return;
		for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
			if (!p.isAlive()) continue;
			if (!RegionManager.isClaimableWorld(p.level())) continue;
			String id = regions.cellIdAt(p.level(), p.getBlockX(), p.getBlockZ());
			RegionManager.CellData cell = regions.get(id);
			String key = p.getUUID().toString();
			String now = cell != null ? id : "";
			String prev = lastCell.get(key);
			if (prev == null || prev.equals(now)) {
				lastCell.put(key, now);
				continue;
			}
			if (!now.isEmpty()) {
				ActionBar.send(p, "§a[เขต] เข้าเขตของ §f" + cell.ownerName + "§a");
			} else {
				String owner = regions.ownerName(prev);
				ActionBar.send(p, "§c[เขต] ออกจากเขตของ §f" + (owner != null ? owner : "?") + "§c");
			}
			lastCell.put(key, now);
		}
	}
}
