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
import net.minecraft.world.item.ItemStack;

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
	private final SlotClickHandler clickHandler;
	private final BiConsumer<ServerPlayer, Container> closeHandler;

	public VirtualChestMenu(int syncId, Inventory playerInventory, Container container, int rows,
							boolean readOnly, SlotClickHandler clickHandler,
							BiConsumer<ServerPlayer, Container> closeHandler) {
		super(rows <= 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6,
				syncId, playerInventory, container, rows);
		this.readOnly = readOnly;
		this.clickHandler = clickHandler;
		this.closeHandler = closeHandler;
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
		super.clicked(slotIndex, button, input, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		if (readOnly) {
			return ItemStack.EMPTY;
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
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new VirtualChestMenu(syncId, inv, container, rows, readOnly, clickHandler, closeHandler),
				title
		));
	}
}
