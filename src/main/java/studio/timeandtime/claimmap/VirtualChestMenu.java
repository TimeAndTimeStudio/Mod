package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Universal Server-Side Virtual Chest Menu for Time SMP.
 * Supports:
 * 1. Read-only interactive menus (Auction House, Shop, Claim Map)
 * 2. Take-only containers (Mailbox /mail: items can only be taken out, cannot be deposited)
 * 3. Free drag-and-drop containers (Bulk Sell /sell: players put items in to sell)
 */
public class VirtualChestMenu extends ChestMenu {
	@FunctionalInterface
	public interface SlotClickHandler {
		void onClick(int slotIndex, int button, ContainerInput input, ServerPlayer player);
	}

	private final boolean readOnly;
	private final boolean takeOnly;
	private final Set<Integer> lockedSlots;
	private final SlotClickHandler clickHandler;
	private final BiConsumer<ServerPlayer, Container> closeHandler;

	public VirtualChestMenu(int syncId, Inventory playerInventory, Container container, int rows,
							boolean readOnly, boolean takeOnly, Set<Integer> lockedSlots, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		super(rows <= 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6,
				syncId, playerInventory, container, rows);
		this.readOnly = readOnly;
		this.takeOnly = takeOnly;
		this.lockedSlots = lockedSlots != null ? lockedSlots : Collections.emptySet();
		this.clickHandler = clickHandler;
		this.closeHandler = closeHandler;
	}

	public VirtualChestMenu(int syncId, Inventory playerInventory, Container container, int rows,
							boolean readOnly, Set<Integer> lockedSlots, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		this(syncId, playerInventory, container, rows, readOnly, false, lockedSlots, clickHandler, closeHandler);
	}

	public VirtualChestMenu(int syncId, Inventory playerInventory, Container container, int rows,
							boolean readOnly, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		this(syncId, playerInventory, container, rows, readOnly, false, Collections.emptySet(), clickHandler, closeHandler);
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput input, Player player) {
		if (readOnly) {
			int topSize = this.getContainer().getContainerSize();
			if (slotIndex >= 0 && slotIndex < topSize) {
				if (clickHandler != null && player instanceof ServerPlayer sp) {
					clickHandler.onClick(slotIndex, button, input, sp);
				}
			}
			this.sendAllDataToRemote();
			return;
		}

		if (lockedSlots.contains(slotIndex)) {
			if (clickHandler != null && player instanceof ServerPlayer sp) {
				clickHandler.onClick(slotIndex, button, input, sp);
			}
			this.sendAllDataToRemote();
			return;
		}

		int topSize = this.getContainer().getContainerSize();
		if (takeOnly) {
			if (slotIndex >= 0 && slotIndex < topSize) {
				// Interacting with top container slots in take-only mode
				if (input == ContainerInput.SWAP || input == ContainerInput.QUICK_CRAFT) {
					// Cannot swap hotbar or drag paint into top container
					this.sendAllDataToRemote();
					return;
				}
				if (!this.getCarried().isEmpty()) {
					// Cannot place/deposit or swap cursor item into top container
					this.sendAllDataToRemote();
					return;
				}
				// Cursor is empty: player is picking up item from top container, allow it
				super.clicked(slotIndex, button, input, player);
				return;
			}
			// Clicking in player inventory (slotIndex >= topSize)
			super.clicked(slotIndex, button, input, player);
			return;
		}

		super.clicked(slotIndex, button, input, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		if (readOnly) {
			return ItemStack.EMPTY;
		}
		if (lockedSlots.contains(slotIndex)) {
			return ItemStack.EMPTY;
		}

		int topSize = this.getContainer().getContainerSize();

		if (takeOnly) {
			// If shift-clicking from player inventory, do NOT put into top container
			if (slotIndex >= topSize) {
				return ItemStack.EMPTY;
			}
			// Shift-clicking from top container: move item into player inventory (slots topSize .. slots.size())
			Slot sourceSlot = this.getSlot(slotIndex);
			if (sourceSlot == null || !sourceSlot.hasItem()) return ItemStack.EMPTY;
			ItemStack source = sourceSlot.getItem();
			ItemStack copy = source.copy();
			if (!this.moveItemStackTo(source, topSize, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
			if (source.isEmpty()) {
				sourceSlot.set(ItemStack.EMPTY);
			} else {
				sourceSlot.setChanged();
			}
			return copy;
		}

		// Free / Sell mode: shift-click from player inventory moves into top container (0..targetLimit)
		if (!lockedSlots.isEmpty() && slotIndex >= topSize) {
			int targetLimit = topSize;
			for (int s : lockedSlots) {
				if (s < targetLimit) targetLimit = s;
			}
			Slot sourceSlot = this.getSlot(slotIndex);
			if (sourceSlot == null || !sourceSlot.hasItem()) return ItemStack.EMPTY;
			ItemStack source = sourceSlot.getItem();
			ItemStack copy = source.copy();
			if (!this.moveItemStackTo(source, 0, targetLimit, false)) {
				return ItemStack.EMPTY;
			}
			if (source.isEmpty()) {
				sourceSlot.set(ItemStack.EMPTY);
			} else {
				sourceSlot.setChanged();
			}
			return copy;
		}

		return super.quickMoveStack(player, slotIndex);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (closeHandler != null && player instanceof ServerPlayer sp) {
			closeHandler.accept(sp, this.getContainer());
		}
	}

	public static void open(ServerPlayer player, Component title, int rows, boolean readOnly,
							Container container, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		open(player, title, rows, readOnly, false, container, Collections.emptySet(), clickHandler, closeHandler);
	}

	public static void open(ServerPlayer player, Component title, int rows, boolean readOnly,
							Container container, Set<Integer> lockedSlots, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		open(player, title, rows, readOnly, false, container, lockedSlots, clickHandler, closeHandler);
	}

	public static void open(ServerPlayer player, Component title, int rows, boolean readOnly, boolean takeOnly,
							Container container, Set<Integer> lockedSlots, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new VirtualChestMenu(syncId, inv, container, rows, readOnly, takeOnly, lockedSlots, clickHandler, closeHandler),
				title
		));
	}
}
