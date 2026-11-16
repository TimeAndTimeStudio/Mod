package studio.timeandtime.claimmap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Interactive Virtual Chest GUI for selling items to the server (/sell),
 * with a built-in player market shortcut button ("ลงขายให้ผู้เล่น").
 */
public final class SellGui {
	private SellGui() {
	}

	public static void open(ServerPlayer player, ShopManager shop, AuctionManager auctions,
							PriceService prices, EconomyManager economy, String currency) {
		if (shop.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบร้านค้าถูกปิดชั่วคราว"));
			return;
		}
		if (economy.isFrozen(player.getUUID())) {
			player.sendSystemMessage(Component.literal("§cบัญชีของคุณถูกระงับ"));
			return;
		}

		SimpleContainer container = new SimpleContainer(54);
		Set<Integer> lockedSlots = new HashSet<>();
		for (int i = 45; i < 54; i++) {
			lockedSlots.add(i);
		}

		// Fill bottom control row (slots 45..53)
		Item paneItem = BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:gray_stained_glass_pane"));
		Item fallbackItem = paneItem != null ? paneItem : Items.IRON_BARS;
		for (int slot : List.of(45, 46, 51, 52)) {
			ItemStack pane = new ItemStack(fallbackItem);
			pane.set(DataComponents.CUSTOM_NAME, Component.literal("§7---"));
			container.setItem(slot, pane);
		}

		// Slot 47: Sign - Information
		ItemStack sign = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal("§e[ช่องขายไอเท็มเข้าร้าน] (ช่อง 1-45)"),
				List.of(
						Component.literal("§7วางไอเท็มที่ต้องการขายในช่อง 1-45 ด้านบน"),
						Component.literal("§7เมื่อปิดหน้าต่าง ระบบจะขายให้เซิร์ฟเวอร์ทันที"),
						Component.literal("§aเงินจะโอนเข้ากระเป๋าของคุณอัตโนมัติ")
				)
		);
		container.setItem(47, sign);

		// Slot 48: Price check shortcut
		ItemStack priceBook = GuiButtons.button(
				GuiButtons.TAG,
				Component.literal("§b[ราคากลาง] ดูราคารับซื้อร้านค้า (/price)"),
				List.of(
						Component.literal("§7คลิกเพื่อเปิดหน้าต่างเช็คราคากลาง"),
						Component.literal("§eดูราคาขาย, ราคาซื้อ และความต้องการตลาด")
				)
		);
		container.setItem(48, priceBook);

		// Slot 49: Player Balance
		long balance = economy.balance(player.getUUID());
		ItemStack gold = GuiButtons.button(
				GuiButtons.COIN,
				Component.literal("§6[ยอดเงินของคุณ]: " + balance + " " + currency),
				List.of(Component.literal("§7ยอดเงินคงเหลือปัจจุบันของคุณ"))
		);
		container.setItem(49, gold);

		// Slot 50: [ปุ่มลงขายให้ผู้เล่น / ประมูล]
		ItemStack sellPlayerBtn = GuiButtons.button(
				GuiButtons.GAVEL,
				Component.literal("§a[ลงขาย] ให้ผู้เล่นอื่น (ตลาดประมูล)"),
				List.of(
						Component.literal("§7ต้องการขายของให้ผู้เล่นคนอื่นในราคาที่คุณต้องการ?"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eวิธีลงขายของในมือให้ผู้เล่น:"),
						Component.literal("§fถือไอเท็มในมือแล้วพิมพ์:"),
						Component.literal("§a/ah sell <ราคาเริ่มต้น> [ชั่วโมง]"),
						Component.literal("§7ตัวอย่าง: §b/ah sell 100 24"),
						Component.literal("§7--------------------------------"),
						Component.literal("§e[คลิกที่นี่] §fเพื่อเปิดดูตลาดที่ผู้เล่นลงขาย (/buy)"),
						Component.literal("§7(ระบบจะขายของในช่อง 1-45 ให้ก่อนเปลี่ยนหน้า)")
				)
		);
		container.setItem(50, sellPlayerBtn);

		// Slot 53: Close & Sell button
		ItemStack closeBtn = GuiButtons.button(
				GuiButtons.CART,
				Component.literal("§c[X] ปิดหน้าต่าง & ขายทันที"),
				List.of(
						Component.literal("§7คลิกเพื่อปิดหน้าต่างและทำการขายของที่วางอยู่"),
						Component.literal("§7(หรือกดปุ่ม Esc)")
				)
		);
		container.setItem(53, closeBtn);

		boolean[] processed = new boolean[]{false};
		Runnable doSell = () -> {
			if (processed[0]) return;
			processed[0] = true;
			for (int s : lockedSlots) {
				container.setItem(s, ItemStack.EMPTY);
			}
			ShopManager.BulkSellOutcome outcome = shop.sellContainer(player, container);
			sendOutcome(player, outcome, currency);
		};

		VirtualChestMenu.open(
				player,
				Component.literal("§0ลากไอเท็มที่ต้องการขายมาวาง (1-45)"),
				6,
				false,
				container,
				lockedSlots,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 50) { // ลงขายให้ผู้เล่น
						doSell.run();
						AuctionGui.open(sp, auctions, economy, currency, 0, AuctionGui.ViewMode.ITEMS);
						sp.sendSystemMessage(Component.literal("§e[แนะนำลงขาย]: §fหากต้องการนำไอเท็มลงขายให้ผู้เล่น ให้ถือไอเท็มในมือแล้วพิมพ์ §a/ah sell <ราคาเริ่ม> [ชั่วโมง]"));
					} else if (slotIndex == 48) { // ดูราคาร้านค้า
						doSell.run();
						PriceGui.open(sp, prices, shop, currency, 0, 0);
					} else if (slotIndex == 53) { // ปิดหน้าต่าง
						sp.closeContainer();
					}
				},
				(sp, cont) -> doSell.run()
		);
	}

	private static void sendOutcome(ServerPlayer sp, ShopManager.BulkSellOutcome outcome, String currency) {
		if (outcome.soldCount() > 0) {
			sp.sendSystemMessage(Component.literal(
					"§aขายไอเท็มสำเร็จ " + outcome.soldCount() + " ชิ้น! รวมเป็นเงิน +"
							+ outcome.totalEarned() + " " + currency
							+ " §7(ยอดเงินคงเหลือ: " + outcome.balance() + ")"));
		}
		if (outcome.returnedCount() > 0) {
			sp.sendSystemMessage(Component.literal(
					"§eคืนไอเท็มที่ร้านไม่รับซื้อ " + outcome.returnedCount() + " ชิ้น เข้าตัว"));
		}
	}
}
