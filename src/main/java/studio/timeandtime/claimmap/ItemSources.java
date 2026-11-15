package studio.timeandtime.claimmap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Locates item stacks the player can sell or trade: their own inventory, their
 * ender chest, and every container inside a cell they own or are a member of.
 *
 * <p>Refs are opaque to the browser and re-resolved on the server thread when a
 * listing is created, so an old ref can never pull from a container the player
 * no longer has access to.
 *
 * <p>A {@link Context} lets the same code run for an online player (live
 * inventory) and for an offline player (inventory loaded from the saved player
 * data). Offline claims may also read chunks they have never loaded, bounded so
 * a web request can never sweep an entire 500x500 cell.
 */
public final class ItemSources {
	private static final int MAX_ENTRIES = 1000;
	private static final int MAX_CHUNKS_PER_CELL = 4096;
	private static final int MAX_OFFLINE_CHUNK_LOADS = 256;

	/**
	 * One sellable stack. {@code ref} is what the server re-resolves; the
	 * container/slot/coordinate fields are for the player-item JSON cache and
	 * the web UI, so a later mutation knows exactly where the stack lives.
	 */
	public record Entry(String ref, String area, String itemId, String label, int count,
			String container, int slot, String dimension, Integer x, Integer y, Integer z) {
	}

	/** Everything needed to resolve item refs for one player. */
	public record Context(UUID uuid, ServerLevel level, Container inventory, Container ender,
			BlockPos near, boolean loadChunks) {
		public static Context online(ServerPlayer player) {
			return new Context(player.getUUID(), player.level(), player.getInventory(),
					player.getEnderChestInventory(), player.blockPosition(), false);
		}

		public static Context offline(UUID uuid, ServerLevel level, Container inventory, Container ender, BlockPos near) {
			return new Context(uuid, level, inventory, ender, near, true);
		}
	}

	public static final class Source {
		private final String ref;
		private final String area;
		private final Container container;
		private final int slot;
		private final Runnable changed;

		private Source(String ref, String area, Container container, int slot, Runnable changed) {
			this.ref = ref;
			this.area = area;
			this.container = container;
			this.slot = slot;
			this.changed = changed;
		}

		public String ref() {
			return ref;
		}

		public String area() {
			return area;
		}

		public ItemStack get() {
			return container.getItem(slot);
		}

		public void set(ItemStack stack) {
			container.setItem(slot, stack == null ? ItemStack.EMPTY : stack);
			container.setChanged();
			if (changed != null) changed.run();
		}
	}

	public record Taken(ItemStack stack, Runnable restore) {
	}

	private ItemSources() {
	}

	public static List<Entry> list(ServerPlayer player, RegionManager regions) {
		if (player == null) return List.of();
		return list(Context.online(player), regions);
	}

	/** Scans only the player's inventory and ender chest (cheap tick refresh). */
	public static List<Entry> listCarried(ServerPlayer player) {
		if (player == null) return List.of();
		return listCarried(Context.online(player));
	}

	public static List<Entry> listCarried(Context ctx) {
		List<Entry> out = new ArrayList<>();
		if (ctx == null || ctx.level() == null) return out;
		addCarried(out, ctx, Collections.newSetFromMap(new IdentityHashMap<>()));
		return out;
	}

	private static void addCarried(List<Entry> out, Context ctx, Set<Container> seen) {
		String dim = ctx.level().dimension().identifier().toString();
		Integer px = ctx.near() == null ? null : ctx.near().getX();
		Integer py = ctx.near() == null ? null : ctx.near().getY();
		Integer pz = ctx.near() == null ? null : ctx.near().getZ();

		Container inv = ctx.inventory();
		if (inv != null) {
			seen.add(inv);
			for (int i = 0; i < inv.getContainerSize() && out.size() < MAX_ENTRIES; i++) {
				addEntry(out, "inv|" + i, areaForInventorySlot(i), "inventory", i, dim, px, py, pz,
						inv.getItem(i));
			}
		}

		Container ender = ctx.ender();
		if (ender != null && seen.add(ender)) {
			for (int i = 0; i < ender.getContainerSize() && out.size() < MAX_ENTRIES; i++) {
				addEntry(out, "ender|" + i, "Ender Chest", "ender_chest", i, dim, px, py, pz,
						ender.getItem(i));
			}
		}
	}

	public static List<Entry> list(Context ctx, RegionManager regions) {
		List<Entry> out = new ArrayList<>();
		if (ctx == null || ctx.level() == null) return out;
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		addCarried(out, ctx, seen);

		ServerLevel level = ctx.level();
		String dim = level.dimension().identifier().toString();
		int cellSize = regions.cellSize();
		List<int[]> chunks = new ArrayList<>();
		for (RegionManager.CellData cell : regions.cellsAccessibleBy(ctx.uuid() == null ? null : ctx.uuid().toString())) {
			int cellMinX = cell.cx * cellSize;
			int cellMinZ = cell.cz * cellSize;
			int maxChunkX = Math.floorDiv(cellMinX + cellSize - 1, 16);
			int maxChunkZ = Math.floorDiv(cellMinZ + cellSize - 1, 16);
			int count = 0;
			for (int cx = Math.floorDiv(cellMinX, 16); cx <= maxChunkX; cx++) {
				for (int cz = Math.floorDiv(cellMinZ, 16); cz <= maxChunkZ; cz++) {
					if (++count > MAX_CHUNKS_PER_CELL) break;
					chunks.add(new int[]{cx, cz});
				}
			}
		}
		// Read chunks nearest to the player first so an offline request finds the
		// base long before its load budget runs out.
		if (ctx.near() != null) {
			long nx = ctx.near().getX();
			long nz = ctx.near().getZ();
			chunks.sort(Comparator.comparingLong(c -> {
				long dx = (long) c[0] * 16 + 8 - nx;
				long dz = (long) c[1] * 16 + 8 - nz;
				return dx * dx + dz * dz;
			}));
		}

		int loads = 0;
		for (int[] c : chunks) {
			if (out.size() >= MAX_ENTRIES) break;
			LevelChunk chunk = ctx.level().getChunkSource().getChunkNow(c[0], c[1]);
			if (chunk == null) {
				if (!ctx.loadChunks() || loads++ >= MAX_OFFLINE_CHUNK_LOADS) continue;
				chunk = chunkAt(ctx, c[0], c[1]);
			}
			if (chunk == null) continue;
			for (BlockEntity be : chunk.getBlockEntities().values()) {
				if (out.size() >= MAX_ENTRIES) break;
				if (!(be instanceof Container container)) continue;
				if (!seen.add(container)) continue;
				BlockPos pos = be.getBlockPos();
				RegionManager.CellData owner = regions.get(regions.cellIdAt(pos.getX(), pos.getZ()));
				if (owner == null || ctx.uuid() == null || !owner.hasAccess(ctx.uuid().toString())) continue;
				String name = be.getBlockState().getBlock().getName().getString();
				String area = name + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
				for (int i = 0; i < container.getContainerSize() && out.size() < MAX_ENTRIES; i++) {
					addEntry(out, "container|" + dim + "|" + pos.getX() + "|" + pos.getY() + "|"
							+ pos.getZ() + "|" + i, area, "container", i, dim,
							pos.getX(), pos.getY(), pos.getZ(), container.getItem(i));
				}
			}
		}
		return out;
	}

	private static LevelChunk chunkAt(Context ctx, int cx, int cz) {
		LevelChunk loaded = ctx.level().getChunkSource().getChunkNow(cx, cz);
		if (loaded != null || !ctx.loadChunks()) return loaded;
		try {
			var chunk = ctx.level().getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
			return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not read chunk {}/{}: {}", cx, cz, e.getMessage());
			return null;
		}
	}

	private static void addEntry(List<Entry> out, String ref, String area, String container, int slot,
			String dimension, Integer x, Integer y, Integer z, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		ItemCodecs.ItemView view = ItemCodecs.view(stack);
		out.add(new Entry(ref, area, view.itemId(), view.label(), view.count(),
				container, slot, dimension, x, y, z));
	}

	private static String areaForInventorySlot(int slot) {
		if (slot >= 36 && slot <= 39) return "ชุดเกราะ";
		if (slot == 40) return "มือรอง";
		return "กระเป๋า";
	}

	public static Source find(ServerPlayer player, RegionManager regions, String ref) {
		if (player == null) return null;
		return find(Context.online(player), regions, ref);
	}

	/** Re-resolves a ref on the server thread, or null when it is not valid. */
	public static Source find(Context ctx, RegionManager regions, String ref) {
		if (ctx == null || ctx.uuid() == null || ref == null) return null;
		String[] p = ref.split("\\|");
		try {
			switch (p[0]) {
				case "inv" -> {
					if (p.length != 2 || ctx.inventory() == null) return null;
					int slot = Integer.parseInt(p[1]);
					if (slot < 0 || slot >= ctx.inventory().getContainerSize()) return null;
					return new Source(ref, areaForInventorySlot(slot), ctx.inventory(), slot, null);
				}
				case "ender" -> {
					if (p.length != 2 || ctx.ender() == null) return null;
					int slot = Integer.parseInt(p[1]);
					if (slot < 0 || slot >= ctx.ender().getContainerSize()) return null;
					return new Source(ref, "Ender Chest", ctx.ender(), slot, null);
				}
				case "container" -> {
					if (p.length != 6) return null;
					String dim = p[1];
					ServerLevel level = ctx.level();
					if (level == null || !level.dimension().identifier().toString().equals(dim)) return null;
					int x = Integer.parseInt(p[2]);
					int y = Integer.parseInt(p[3]);
					int z = Integer.parseInt(p[4]);
					int slot = Integer.parseInt(p[5]);
					RegionManager.CellData cell = regions.get(regions.cellIdAt(x, z));
					if (cell == null || !cell.hasAccess(ctx.uuid().toString())) return null;
					BlockEntity be = blockEntityAt(ctx, new BlockPos(x, y, z));
					if (!(be instanceof Container container)) return null;
					if (slot < 0 || slot >= container.getContainerSize()) return null;
					String name = be.getBlockState().getBlock().getName().getString();
					return new Source(ref, name + " (" + x + ", " + y + ", " + z + ")", container, slot, null);
				}
				default -> {
					return null;
				}
			}
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static BlockEntity blockEntityAt(Context ctx, BlockPos pos) {
		BlockEntity be = ctx.level().getBlockEntity(pos);
		if (be != null || !ctx.loadChunks()) return be;
		LevelChunk chunk = chunkAt(ctx, pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
	}

	public static Taken take(ServerPlayer player, RegionManager regions, String ref, String itemId, int count) {
		return take(Context.online(player), regions, ref, itemId, count);
	}

	/**
	 * Removes exactly {@code count} matching items and returns them with a
	 * rollback action. Returns null when the slot no longer holds that item.
	 */
	public static Taken take(Context ctx, RegionManager regions, String ref, String itemId, int count) {
		Source source = find(ctx, regions, ref);
		if (source == null || count < 1) return null;
		ItemStack before = source.get().copy();
		if (before.isEmpty()) return null;
		String actual = ItemCodecs.idOf(before);
		if (itemId != null && !itemId.equals(actual)) return null;
		if (before.getCount() < count) return null;
		ItemStack taken = before.copy();
		taken.setCount(count);
		ItemStack rest = before.copy();
		rest.setCount(before.getCount() - count);
		source.set(rest.isEmpty() ? ItemStack.EMPTY : rest);
		return new Taken(taken, () -> source.set(before));
	}
}
