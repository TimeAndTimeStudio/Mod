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
 * Supports both read-only interactive menus (Auction House, Shop, Claim Map)
 * and free drag-and-drop containers (Mailbox claim, Bulk Sell).
 */
public class VirtualChestMenu extends ChestMenu {
	@FunctionalInterface
	public interface SlotClickHandler {
		void onClick(int slotIndex, int button, ContainerInput input, ServerPlayer player);
	}

	private final boolean readOnly;
	private final Set<Integer> lockedSlots;
	private final SlotClickHandler clickHandler;
	private final BiConsumer<ServerPlayer, Container> closeHandler;

	public VirtualChestMenu(int syncId, Inventory playerInventory, Container container, int rows,
							boolean readOnly, Set<Integer> lockedSlots, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		super(rows <= 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6,
				syncId, playerInventory, container, rows);
		this.readOnly = readOnly;
		this.lockedSlots = lockedSlots != null ? lockedSlots : Collections.emptySet();
		this.clickHandler = clickHandler;
		this.closeHandler = closeHandler;
	}

	public VirtualChestMenu(int syncId, Inventory playerInventory, Container container, int rows,
							boolean readOnly, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		this(syncId, playerInventory, container, rows, readOnly, Collections.emptySet(), clickHandler, closeHandler);
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
		open(player, title, rows, readOnly, container, Collections.emptySet(), clickHandler, closeHandler);
	}

	public static void open(ServerPlayer player, Component title, int rows, boolean readOnly,
							Container container, Set<Integer> lockedSlots, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new VirtualChestMenu(syncId, inv, container, rows, readOnly, lockedSlots, clickHandler, closeHandler),
				title
		));
	}
}
