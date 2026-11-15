package studio.timeandtime.claimmap;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the saved player data (world/playerdata/&lt;uuid&gt;.dat) so the
 * market can list, sell and deliver items while the player is offline.
 *
 * <p>Everything here must run on the server thread and only for players that
 * are not currently online: an online player owns the live inventory object and
 * writing the file behind their back would lose items on the next autosave.
 */
public final class OfflinePlayers {
	/** Inventory slots we expose: hotbar 0-8, main 9-35, armor 36-39 and offhand 40. */
	public static final int SLOTS = 41;
	private static final int ADD_SLOTS = 36;      // hotbar + main inventory only
	private static final String INVENTORY_KEY = "Inventory";
	private static final String ENDER_KEY = "EnderItems";

	private OfflinePlayers() {
	}

	public static Optional<CompoundTag> loadTag(MinecraftServer server, UUID uuid, String name) {
		if (server == null || uuid == null) return Optional.empty();
		try {
			return server.getPlayerList().loadPlayerData(
					new NameAndId(uuid, name == null || name.isBlank() ? uuid.toString() : name));
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not read offline data for {}: {}", uuid, e.getMessage());
			return Optional.empty();
		}
	}

	public static List<ItemStackWithSlot> readSlots(CompoundTag tag, RegistryAccess access, String key) {
		if (tag == null || access == null) return List.of();
		try {
			var input = TagValueInput.create(ProblemReporter.DISCARDING, access, tag);
			List<ItemStackWithSlot> out = new ArrayList<>();
			input.listOrEmpty(key, ItemStackWithSlot.CODEC).forEach(out::add);
			return out;
		} catch (RuntimeException e) {
			// Callers must not write the file back when a decode failed.
			throw new IllegalStateException("cannot decode " + key + ": " + e.getMessage(), e);
		}
	}

	public static List<ItemStackWithSlot> readInventory(CompoundTag tag, RegistryAccess access) {
		return readSlots(tag, access, INVENTORY_KEY);
	}

	public static List<ItemStackWithSlot> readEnder(CompoundTag tag, RegistryAccess access) {
		return readSlots(tag, access, ENDER_KEY);
	}

	private static boolean writeSlots(CompoundTag tag, RegistryAccess access, String key, List<ItemStackWithSlot> slots) {
		if (tag == null || access == null) return false;
		try {
			DynamicOps<Tag> ops = access.createSerializationContext(NbtOps.INSTANCE);
			Tag encoded = ItemStackWithSlot.CODEC.listOf()
					.encodeStart(ops, slots)
					.getOrThrow(IllegalStateException::new);
			tag.put(key, encoded);
			return true;
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not encode {} for player data: {}", key, e.getMessage());
			return false;
		}
	}

	public static boolean writeInventory(CompoundTag tag, RegistryAccess access, List<ItemStackWithSlot> slots) {
		return writeSlots(tag, access, INVENTORY_KEY, slots);
	}

	public static boolean writeEnder(CompoundTag tag, RegistryAccess access, List<ItemStackWithSlot> slots) {
		return writeSlots(tag, access, ENDER_KEY, slots);
	}

	public static boolean saveTag(MinecraftServer server, UUID uuid, CompoundTag tag) {
		if (server == null || uuid == null || tag == null) return false;
		Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
		try {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, uuid + "-", ".dat");
			NbtIo.writeCompressed(tag, tmp);
			Path real = dir.resolve(uuid + ".dat");
			Path backup = dir.resolve(uuid + ".dat_old");
			if (!Util.safeReplaceOrMoveFile(real, tmp, backup, false)) {
				Files.deleteIfExists(tmp);
				ClaimMap.LOGGER.warn("[timesmp] Could not replace player data for {}", uuid);
				return false;
			}
			return true;
		} catch (IOException | RuntimeException e) {
			ClaimMap.LOGGER.error("[timesmp] Could not save offline player data for {}", uuid, e);
			return false;
		}
	}

	/** Loads a player's saved inventory into a mutable container view. */
	public static Optional<Session> open(MinecraftServer server, UUID uuid, String name, RegistryAccess access) {
		Optional<CompoundTag> tag = loadTag(server, uuid, name);
		if (tag.isEmpty()) return Optional.empty();
		try {
			return Optional.of(new Session(server, uuid, tag.get(), access));
		} catch (RuntimeException e) {
			ClaimMap.LOGGER.warn("[timesmp] Refusing to edit offline data for {}: {}", uuid, e.getMessage());
			return Optional.empty();
		}
	}

	/** A loaded offline player whose inventory can be modified and flushed. */
	public static final class Session {
		private final MinecraftServer server;
		private final UUID uuid;
		private final CompoundTag tag;
		private final InventoryView inventory;
		private final InventoryView ender;

		private Session(MinecraftServer server, UUID uuid, CompoundTag tag, RegistryAccess access) {
			this.server = server;
			this.uuid = uuid;
			this.tag = tag;
			this.inventory = new InventoryView(SLOTS);
			this.ender = new InventoryView(SLOTS);
			for (ItemStackWithSlot s : readInventory(tag, access)) {
				if (s.isValidInContainer(SLOTS)) inventory.setItem(s.slot(), s.stack());
			}
			for (ItemStackWithSlot s : readEnder(tag, access)) {
				if (s.isValidInContainer(SLOTS)) ender.setItem(s.slot(), s.stack());
			}
		}

		public UUID uuid() {
			return uuid;
		}

		public InventoryView inventory() {
			return inventory;
		}

		public InventoryView ender() {
			return ender;
		}

		/** Writes both inventories back to disk. */
		public boolean flush(RegistryAccess access) {
			if (!inventory.dirty && !ender.dirty) return true;
			List<ItemStackWithSlot> inv = slotsOf(inventory);
			List<ItemStackWithSlot> end = slotsOf(ender);
			boolean ok = writeInventory(tag, access, inv) & writeEnder(tag, access, end);
			if (ok) ok = saveTag(server, uuid, tag);
			if (ok) {
				inventory.dirty = false;
				ender.dirty = false;
			}
			return ok;
		}

		private static List<ItemStackWithSlot> slotsOf(InventoryView view) {
			List<ItemStackWithSlot> out = new ArrayList<>();
			for (int i = 0; i < view.getContainerSize(); i++) {
				ItemStack stack = view.getItem(i);
				if (stack != null && !stack.isEmpty()) out.add(new ItemStackWithSlot(i, stack));
			}
			return out;
		}
	}

	/**
	 * Mutable slot array with the same add/merge rules as a player inventory,
	 * so mailbox delivery behaves exactly like handing items to the player.
	 */
	public static final class InventoryView implements Container {
		private final ItemStack[] items;
		private boolean dirty;

		public InventoryView(int size) {
			this.items = new ItemStack[size];
			for (int i = 0; i < size; i++) this.items[i] = ItemStack.EMPTY;
		}

		@Override
		public int getContainerSize() {
			return items.length;
		}

		@Override
		public boolean isEmpty() {
			for (ItemStack stack : items) if (stack != null && !stack.isEmpty()) return false;
			return true;
		}

		@Override
		public ItemStack getItem(int slot) {
			if (slot < 0 || slot >= items.length) return ItemStack.EMPTY;
			ItemStack stack = items[slot];
			return stack == null ? ItemStack.EMPTY : stack;
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			ItemStack stack = getItem(slot);
			if (stack.isEmpty() || count <= 0) return ItemStack.EMPTY;
			ItemStack taken = stack.split(count);
			if (stack.isEmpty()) items[slot] = ItemStack.EMPTY;
			dirty = true;
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			ItemStack stack = getItem(slot);
			items[slot] = ItemStack.EMPTY;
			dirty = true;
			return stack;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			if (slot < 0 || slot >= items.length) return;
			items[slot] = stack == null ? ItemStack.EMPTY : stack;
			dirty = true;
		}

		@Override
		public void setChanged() {
			dirty = true;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void clearContent() {
			for (int i = 0; i < items.length; i++) items[i] = ItemStack.EMPTY;
			dirty = true;
		}

		/** Merges a stack into the hotbar/main slots. Returns true when all of it fit. */
		public boolean add(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return true;
			int left = stack.getCount();
			// Merge into matching stacks first.
			for (int i = 0; i < Math.min(ADD_SLOTS, items.length) && left > 0; i++) {
				ItemStack slot = items[i];
				if (slot == null || slot.isEmpty()) continue;
				if (!ItemStack.isSameItemSameComponents(slot, stack)) continue;
				int space = Math.min(slot.getMaxStackSize(), getMaxStackSize(slot)) - slot.getCount();
				if (space <= 0) continue;
				int move = Math.min(space, left);
				slot.grow(move);
				left -= move;
				dirty = true;
			}
			// Then fill empty slots.
			for (int i = 0; i < Math.min(ADD_SLOTS, items.length) && left > 0; i++) {
				ItemStack slot = items[i];
				if (slot != null && !slot.isEmpty()) continue;
				int move = Math.min(Math.min(stack.getMaxStackSize(), getMaxStackSize(stack)), left);
				ItemStack placed = stack.copy();
				placed.setCount(move);
				items[i] = placed;
				left -= move;
				dirty = true;
			}
			if (left > 0) stack.setCount(left);
			return left <= 0;
		}

		public int getFreeSlot() {
			for (int i = 0; i < Math.min(ADD_SLOTS, items.length); i++) {
				if (items[i] == null || items[i].isEmpty()) return i;
			}
			return -1;
		}
	}
}
