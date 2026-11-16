package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mailbox GUI (/mail)
 * Displays player items waiting to be claimed (from player market or auctions).
 * Slots 0-44: item storage (free to take/deposit)
 * Slots 45-53: navigation and info controls (locked)
 */
public final class MailGui {
	private MailGui() {}

	public static void open(ServerPlayer player, MailboxManager mailbox,
							PlayerMarket playerMarket, AuctionManager auctions,
							EconomyManager economy, String currency) {
		if (player == null) return;
		if (mailbox.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบกล่องของถูกปิดชั่วคราว"));
			return;
		}

		List<MailboxManager.MailEntry> entries = mailbox.list(player.getUUID());
		SimpleContainer container = new SimpleContainer(54);

		int slot = 0;
		for (MailboxManager.MailEntry entry : entries) {
			if (slot >= 45) break;
			var decoded = ItemCodecs.decode(entry.itemJson(), player.level().registryAccess());
			if (decoded.isPresent()) {
				ItemStack stack = decoded.get();
				stack.setCount(Math.min(entry.count(), stack.getMaxStackSize()));
				container.setItem(slot++, stack);
			}
		}

		// Slot 45: Go to /buy (Player Market)
		ItemStack buyBtn = GuiButtons.button(
				GuiButtons.CART,
				Component.literal("§a[ไปตลาดผู้เล่น] /buy"),
				List.of(
						Component.literal("§7คลิกเพื่อเปิดตลาดซื้อขายสินค้าผู้เล่น (/buy)"),
						Component.literal("§eซื้อไอเท็มในราคาคงที่ทันที")
				)
		);
		container.setItem(45, buyBtn);

		// Slot 46: Go to /ah (Auction House)
		ItemStack ahBtn = GuiButtons.button(
				GuiButtons.GAVEL,
				Component.literal("§6[ไปตลาดประมูล] /ah"),
				List.of(
						Component.literal("§7คลิกเพื่อเปิดตลาดประมูลไอเท็มและที่ดิน (/ah)"),
						Component.literal("§eประมูลเสนอราคาแข่งกับผู้เล่นอื่น")
				)
		);
		container.setItem(46, ahBtn);

		// Slot 49: Info Status
		ItemStack infoBtn = GuiButtons.button(
				GuiButtons.PARCEL,
				Component.literal("§d[กล่องรับของของคุณ]"),
				List.of(
						Component.literal(entries.isEmpty() ? "§7(ขณะนี้ไม่มีไอเท็มค้างอยู่ในกล่อง)" : "§7มีไอเท็มค้างอยู่ §e" + entries.size() + " §7รายการ"),
						Component.literal("§7หยิบไอเท็มเข้าตัว หรือกด ESC เพื่อปิด")
				)
		);
		container.setItem(49, infoBtn);

		// Slot 53: Help Info
		ItemStack helpBtn = GuiButtons.button(
				GuiButtons.HELP,
				Component.literal("§b[!] ข้อมูลกล่องรับของ"),
				List.of(
						Component.literal("§7- ไอเท็มที่ประมูลชนะจะถูกส่งมาที่นี่"),
						Component.literal("§7- ไอเท็มที่ประมูลไม่สำเร็จ / ยกเลิกขายจะคืนมาที่นี่"),
						Component.literal("§7- สามารถหยิบไอเท็มเข้าตัวได้ตลอดเวลา")
				)
		);
		container.setItem(53, helpBtn);

		Set<Integer> lockedSlots = new HashSet<>();
		for (int i = 45; i < 54; i++) {
			lockedSlots.add(i);
		}

		VirtualChestMenu.open(
				player,
				Component.literal("§0กล่องรับของของคุณ (/mail)"),
				6,
				false,
				true,
				container,
				lockedSlots,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 45) {
						if (playerMarket != null) {
							BuyGui.open(sp, playerMarket, economy, currency);
						} else {
							sp.sendSystemMessage(Component.literal("§cระบบตลาดยังไม่พร้อมใช้งาน"));
						}
					} else if (slotIndex == 46) {
						if (auctions != null) {
							AuctionGui.open(sp, auctions, economy, currency, 0, AuctionGui.ViewMode.ALL);
						} else {
							sp.sendSystemMessage(Component.literal("§cระบบประมูลยังไม่พร้อมใช้งาน"));
						}
					}
				},
				(sp, cont) -> {
					List<MailboxManager.MailEntry> remaining = new ArrayList<>();
					for (int i = 0; i < 45; i++) {
						ItemStack stack = cont.getItem(i);
						if (stack != null && !stack.isEmpty()) {
							String json = ItemCodecs.encode(stack, sp.level().registryAccess());
							if (json != null) {
								ItemCodecs.ItemView v = ItemCodecs.view(stack);
								remaining.add(new MailboxManager.MailEntry(
										UUID.randomUUID().toString().substring(0, 8),
										json, v.itemId(), v.count(), v.label(), "mailbox",
										System.currentTimeMillis()
								));
							}
						}
					}
					if (entries.size() > 45) {
						for (int i = 45; i < entries.size(); i++) {
							remaining.add(entries.get(i));
						}
					}
					mailbox.setAll(sp.getUUID(), remaining);
					int taken = entries.size() - remaining.size();
					if (taken > 0) {
						sp.sendSystemMessage(Component.literal("§aคุณได้รับของเข้าตัวเรียบร้อยแล้ว (คงเหลือในกล่อง " + remaining.size() + " รายการ)"));
					} else if (remaining.isEmpty() && !entries.isEmpty()) {
						sp.sendSystemMessage(Component.literal("§aคุณได้รับของทั้งหมดในกล่องเรียบร้อยแล้ว"));
					}
				}
		);
	}
}
