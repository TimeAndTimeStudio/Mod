package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claimed 500x500 cell regions. Persisted as JSON in
 * {@code config/timesmp/regions.json} (see plan.md, RegionManager).
 */
public final class RegionManager {
	public static final class CellData {
		public String id;          // "cx,cz"
		public int cx;
		public int cz;
		public String ownerUuid;
		public String ownerName;
		public List<String> members = new ArrayList<>(); // UUIDs
		/** Whether players may damage each other inside this claim. */
		public boolean pvp = true;

		public CellData() {
		}

		public boolean isOwner(String uuid) {
			return ownerUuid != null && ownerUuid.equals(uuid);
		}

		public boolean isMember(String uuid) {
			return members != null && members.contains(uuid);
		}

		public boolean hasAccess(String uuid) {
			return isOwner(uuid) || isMember(uuid);
		}
	}

	public enum ClaimResult {
		OK, ALREADY_CLAIMED, COMMON_AREA, LIMIT_REACHED
	}

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private final ClaimMapConfig config;
	private final Map<String, CellData> cells = new LinkedHashMap<>();
	private final Object lock = new Object();

	public RegionManager(ClaimMapConfig config) {
		this.config = config;
		this.file = config.regionsFile();
	}

	public void load() {
		synchronized (lock) {
			cells.clear();
			if (file == null || !Files.exists(file)) return;
			try {
				String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				Wrapper w = gson.fromJson(json, Wrapper.class);
				if (w != null && w.cells != null) {
					for (CellData c : w.cells) {
						if (c.id != null) {
							cells.put(c.id, c);
						}
					}
				}
			} catch (Exception e) {
				ClaimMap.LOGGER.error("[timesmp] Could not load regions.json", e);
			}
		}
	}

	public void save() {
		synchronized (lock) {
			try {
				Path parent = file.getParent();
				if (parent != null) Files.createDirectories(parent);
				Wrapper w = new Wrapper();
				w.cells = new ArrayList<>(cells.values());
				String json = gson.toJson(w);
				Path tmp = file.resolveSibling("regions.json.tmp");
				Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				ClaimMap.LOGGER.error("[timesmp] Could not save regions.json", e);
			}
		}
	}

	public int cellSize() {
		return config.cellSize;
	}

	public String cellIdAt(int blockX, int blockZ) {
		int cx = Math.floorDiv(blockX, cellSize());
		int cz = Math.floorDiv(blockZ, cellSize());
		return cx + "," + cz;
	}

	public boolean isCommonArea(int cx, int cz) {
		// 0 = ปิดการกันพื้นที่สปอน (จองได้ทุกช่อง), >0 = กันวงรัศมีรอบจุดกำเนิด
		int r = config.spawnProtectedCells;
		if (r <= 0) return false;
		return Math.abs(cx) <= r && Math.abs(cz) <= r;
	}

	public CellData get(String id) {
		synchronized (lock) {
			return cells.get(id);
		}
	}

	public String ownerName(String id) {
		CellData c = get(id);
		return c == null ? null : c.ownerName;
	}

	/** id -> owner name, for the web UI (/cells). */
	public Map<String, String> ownerMap() {
		Map<String, String> out = new LinkedHashMap<>();
		synchronized (lock) {
			for (CellData c : cells.values()) {
				out.put(c.id, c.ownerName);
			}
		}
		return out;
	}

	/** Cells the player owns or is a member of. */
	public List<CellData> cellsAccessibleBy(String uuid) {
		List<CellData> out = new ArrayList<>();
		if (uuid == null) return out;
		synchronized (lock) {
			for (CellData c : cells.values()) {
				if (c.hasAccess(uuid)) out.add(c);
			}
		}
		return out;
	}

	public int countClaimedBy(String uuid) {
		int n = 0;
		synchronized (lock) {
			for (CellData c : cells.values()) {
				if (c.isOwner(uuid)) n++;
			}
		}
		return n;
	}

	/** Double-checks that the cell is actually free server side (plan.md: กันยิง API ตรง ๆ). */
	public ClaimResult claim(int cellX, int cellZ, String uuid, String name) {
		synchronized (lock) {
			String id = cellX + "," + cellZ;
			if (isCommonArea(cellX, cellZ)) return ClaimResult.COMMON_AREA;
			if (cells.containsKey(id)) return ClaimResult.ALREADY_CLAIMED;
			if (config.maxCellsPerPlayer > 0 && countClaimedBy(uuid) >= config.maxCellsPerPlayer) return ClaimResult.LIMIT_REACHED;
			CellData c = new CellData();
			c.id = id;
			c.cx = cellX;
			c.cz = cellZ;
			c.ownerUuid = uuid;
			c.ownerName = name;
			c.members = new ArrayList<>();
			cells.put(id, c);
			save();
			return ClaimResult.OK;
		}
	}

	public boolean unclaim(String id, String uuid, boolean admin) {
		synchronized (lock) {
			CellData c = cells.get(id);
			if (c == null) return false;
			if (!admin && !c.isOwner(uuid)) return false;
			cells.remove(id);
			save();
			return true;
		}
	}

	public boolean transfer(String id, String actorUuid, String newOwnerUuid, String newOwnerName, boolean admin) {
		synchronized (lock) {
			CellData c = cells.get(id);
			if (c == null) return false;
			if (!admin && !c.isOwner(actorUuid)) return false;
			c.ownerUuid = newOwnerUuid;
			c.ownerName = newOwnerName;
			if (c.members != null) c.members.remove(newOwnerUuid);
			save();
			return true;
		}
	}

	/**
	 * Assigns a cell to a winner even when the cell was free or the player is at
	 * the claim limit. Used only by trusted auction settlement.
	 */
	public boolean assign(String id, String newOwnerUuid, String newOwnerName) {
		if (id == null || newOwnerUuid == null) return false;
		String[] parts = id.split(",", -1);
		if (parts.length != 2) return false;
		int cx;
		int cz;
		try {
			cx = Integer.parseInt(parts[0]);
			cz = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			return false;
		}
		synchronized (lock) {
			CellData c = cells.get(id);
			if (c == null) {
				c = new CellData();
				c.id = id;
				c.cx = cx;
				c.cz = cz;
				c.members = new ArrayList<>();
				cells.put(id, c);
			}
			c.ownerUuid = newOwnerUuid;
			c.ownerName = newOwnerName;
			c.members = new ArrayList<>();
			save();
			return true;
		}
	}

	public boolean addMember(String id, String uuid, String memberUuid, String memberName) {
		synchronized (lock) {
			CellData c = cells.get(id);
			if (c == null || !c.isOwner(uuid)) return false;
			if (!c.members.contains(memberUuid)) c.members.add(memberUuid);
			save();
			return true;
		}
	}

	public boolean removeMember(String id, String uuid, String memberUuid) {
		synchronized (lock) {
			CellData c = cells.get(id);
			if (c == null || !c.isOwner(uuid)) return false;
			c.members.remove(memberUuid);
			save();
			return true;
		}
	}

	/**
	 * Lets the claim owner (or an admin) turn PvP on or off for this claim.
	 * The rule is enforced by {@link Protection} in every claimed cell.
	 */
	public boolean setPvp(String id, String uuid, boolean enabled, boolean admin) {
		synchronized (lock) {
			CellData c = cells.get(id);
			if (c == null) return false;
			if (!admin && !c.isOwner(uuid)) return false;
			c.pvp = enabled;
			save();
			return true;
		}
	}

	private static final class Wrapper {
		List<CellData> cells;
	}
}
