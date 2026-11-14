package studio.timeandtime.claimmap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Locates item stacks the player can sell or trade: their own inventory, their
 * ender chest, and every loaded container inside a cell they own or are a
 * member of.
 *
 * <p>Refs are opaque to the browser and re-resolved on the server thread when a
 * listing is created, so an old ref can never pull from a container the player
 * no longer has access to.
 */
public final class ItemSources {
	private static final int MAX_ENTRIES = 1000;
	private static final int MAX_CHUNKS_PER_CELL = 4096;

	public record Entry(String ref, String area, String itemId, String label, int count) {
	}

	public static final class Source {
		private final String ref;
		private final String area;
		private final Container container;
		private final int slot;
		private final ServerPlayer player;
		private final boolean ender;

		private Source(String ref, String area, Container container, int slot, ServerPlayer player, boolean ender) {
			this.ref = ref;
			this.area = area;
			this.container = container;
			this.slot = slot;
			this.player = player;
			this.ender = ender;
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
			if (ender) player.getEnderChestInventory().setChanged();
		}
	}

	public record Taken(ItemStack stack, Runnable restore) {
	}

	private ItemSources() {
	}

	public static List<Entry> list(ServerPlayer player, RegionManager regions) {
		List<Entry> out = new ArrayList<>();
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		var inv = player.getInventory();
		seen.add(inv);
		for (int i = 0; i < inv.getContainerSize() && out.size() < MAX_ENTRIES; i++) {
			addEntry(out, "inv|" + i, areaForInventorySlot(i), inv.getItem(i));
		}

		var ender = player.getEnderChestInventory();
		if (seen.add(ender)) {
			for (int i = 0; i < ender.getContainerSize() && out.size() < MAX_ENTRIES; i++) {
				addEntry(out, "ender|" + i, "Ender Chest", ender.getItem(i));
			}
		}

		ServerLevel level = player.level();
		String dim = level.dimension().identifier().toString();
		int cellSize = regions.cellSize();
		// Cells can be negative; floorDiv keeps negative coordinates correct.
		for (RegionManager.CellData cell : regions.cellsAccessibleBy(player.getUUID().toString())) {
			int cellMinX = cell.cx * cellSize;
			int cellMinZ = cell.cz * cellSize;
			int cellMaxX = cellMinX + cellSize - 1;
			int cellMaxZ = cellMinZ + cellSize - 1;
			int minChunkX = Math.floorDiv(cellMinX, 16);
			int minChunkZ = Math.floorDiv(cellMinZ, 16);
			int maxChunkX = Math.floorDiv(cellMaxX, 16);
			int maxChunkZ = Math.floorDiv(cellMaxZ, 16);
			int chunks = 0;
			for (int cx = minChunkX; cx <= maxChunkX && out.size() < MAX_ENTRIES; cx++) {
				for (int cz = minChunkZ; cz <= maxChunkZ && out.size() < MAX_ENTRIES; cz++) {
					if (++chunks > MAX_CHUNKS_PER_CELL) break;
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) continue;
					for (BlockEntity be : chunk.getBlockEntities().values()) {
						if (out.size() >= MAX_ENTRIES) break;
						if (!(be instanceof Container container)) continue;
						if (!seen.add(container)) continue;
						BlockPos pos = be.getBlockPos();
						if (pos.getX() < cellMinX || pos.getX() > cellMaxX
								|| pos.getZ() < cellMinZ || pos.getZ() > cellMaxZ) {
							continue;
						}
						RegionManager.CellData owner = regions.get(regions.cellIdAt(pos.getX(), pos.getZ()));
						if (owner == null || !owner.hasAccess(player.getUUID().toString())) continue;
						String name = be.getBlockState().getBlock().getName().getString();
						String area = name + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
						for (int i = 0; i < container.getContainerSize() && out.size() < MAX_ENTRIES; i++) {
							addEntry(out, "container|" + dim + "|" + pos.getX() + "|" + pos.getY() + "|"
									+ pos.getZ() + "|" + i, area, container.getItem(i));
						}
					}
				}
			}
		}
		return out;
	}

	private static void addEntry(List<Entry> out, String ref, String area, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		ItemCodecs.ItemView view = ItemCodecs.view(stack);
		out.add(new Entry(ref, area, view.itemId(), view.label(), view.count()));
	}

	private static String areaForInventorySlot(int slot) {
		if (slot >= 36 && slot <= 39) return "ชุดเกราะ";
		if (slot == 40) return "มือรอง";
		return "กระเป๋า";
	}

	/** Re-resolves a ref on the server thread, or null when it is not valid. */
	public static Source find(ServerPlayer player, RegionManager regions, String ref) {
		if (player == null || ref == null) return null;
		String[] p = ref.split("\\|");
		try {
			switch (p[0]) {
				case "inv" -> {
					if (p.length != 2) return null;
					int slot = Integer.parseInt(p[1]);
					var inv = player.getInventory();
					if (slot < 0 || slot >= inv.getContainerSize()) return null;
					return new Source(ref, areaForInventorySlot(slot), inv, slot, player, false);
				}
				case "ender" -> {
					if (p.length != 2) return null;
					int slot = Integer.parseInt(p[1]);
					var ender = player.getEnderChestInventory();
					if (slot < 0 || slot >= ender.getContainerSize()) return null;
					return new Source(ref, "Ender Chest", ender, slot, player, true);
				}
				case "container" -> {
					if (p.length != 6) return null;
					String dim = p[1];
					ServerLevel level = player.level();
					if (!level.dimension().identifier().toString().equals(dim)) return null;
					int x = Integer.parseInt(p[2]);
					int y = Integer.parseInt(p[3]);
					int z = Integer.parseInt(p[4]);
					int slot = Integer.parseInt(p[5]);
					RegionManager.CellData cell = regions.get(regions.cellIdAt(x, z));
					if (cell == null || !cell.hasAccess(player.getUUID().toString())) return null;
					BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
					if (!(be instanceof Container container)) return null;
					if (slot < 0 || slot >= container.getContainerSize()) return null;
					String name = be.getBlockState().getBlock().getName().getString();
					return new Source(ref, name + " (" + x + ", " + y + ", " + z + ")", container, slot, player, false);
				}
				default -> {
					return null;
				}
			}
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Removes exactly {@code count} matching items and returns them with a
	 * rollback action. Returns null when the slot no longer holds that item.
	 */
	public static Taken take(ServerPlayer player, RegionManager regions, String ref, String itemId, int count) {
		Source source = find(player, regions, ref);
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
