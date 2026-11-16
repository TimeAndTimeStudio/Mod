package studio.timeandtime.claimmap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public final class BuyGui {
	private static final int PAGE_SIZE = 45;

	private BuyGui() {}

	public static void open(ServerPlayer player, PlayerMarket market, EconomyManager economy, String currency) {
		open(player, market, economy, currency, 0, false);
	}

	public static void open(ServerPlayer player, PlayerMarket market, EconomyManager economy, String currency, int page, boolean myOnly) {
		if (market == null || market.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบตลาดผู้เล่นปิดปรับปรุงชั่วคราว"));
			return;
		}

		List<PlayerMarket.Listing> active = market.all().stream()
				.filter(PlayerMarket.Listing::isActive)
				.filter(l -> !myOnly || l.seller.equals(player.getUUID().toString()))
				.sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
				.toList();

		int maxPages = Math.max(1, (active.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int curPage = Math.max(0, Math.min(page, maxPages - 1));
		int startIdx = curPage * PAGE_SIZE;
		int endIdx = Math.min(startIdx + PAGE_SIZE, active.size());

		SimpleContainer container = new SimpleContainer(54);

		for (int i = startIdx; i < endIdx; i++) {
			PlayerMarket.Listing l = active.get(i);
			int slot = i - startIdx;

			var decoded = ItemCodecs.decode(l.itemJson, player.level().registryAccess());
			ItemStack displayStack = decoded.orElseGet(() -> new ItemStack(Items.PAPER));
			if (l.count > 1) {
				displayStack.setCount(Math.min(l.count, displayStack.getMaxStackSize()));
			}

			displayStack.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + l.label + (l.count > 1 ? " §7x" + l.count : "")));

			List<Component> lore = new ArrayList<>();
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§eผู้ลงขาย: §f" + l.sellerName));
			lore.add(Component.literal("§eราคา: §6" + l.price + " " + currency));
			lore.add(Component.literal("§7------------------------"));

			if (l.seller.equals(player.getUUID().toString())) {
				lore.add(Component.literal("§c[คลิก] เพื่อขอยกเลิกและรับของคืน (/mail)"));
			} else {
				lore.add(Component.literal("§a[คลิก] ดูรายละเอียด & ซื้อทันที"));
				long bal = economy.balance(player.getUUID());
				if (bal < l.price) {
					lore.add(Component.literal("§c(เงินของคุณไม่พอ: " + bal + " " + currency + ")"));
				}
			}

			displayStack.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(slot, displayStack);
		}

		// Slot 45: Previous page
		if (curPage > 0) {
			ItemStack prev = GuiButtons.button(
					GuiButtons.BACK,
					Component.literal("§e< หน้าก่อนหน้า (" + curPage + "/" + maxPages + ")"),
					List.of()
			);
			container.setItem(45, prev);
		}

		// Slot 46: Toggle filter (My listings vs All)
		ItemStack filterBtn = GuiButtons.button(
				myOnly ? GuiButtons.BRAND : GuiButtons.CART,
				Component.literal(myOnly ? "§a[แสดงสินค้าทั้งหมด]" : "§b[สินค้าของฉัน]"),
				List.of(Component.literal(myOnly ? "§7คลิกเพื่อดูสินค้าของผู้เล่นทุกคน" : "§7คลิกเพื่อกรองดูเฉพาะสินค้าที่คุณลงขายเอง"))
		);
		container.setItem(46, filterBtn);

		// Slot 47: Switch to /ah (Auction House)
		ItemStack ahBtn = GuiButtons.button(
				GuiButtons.GAVEL,
				Component.literal("§6[ตลาดประมูล] ไปที่ /ah"),
				List.of(
						Component.literal("§7คลิกเพื่อสลับไปหน้าต่างตลาดประมูล / โฉนดที่ดิน"),
						Component.literal("§eประมูลเสนอราคาแข่งกับผู้เล่นอื่น")
				)
		);
		container.setItem(47, ahBtn);

		// Slot 48: Mailbox shortcut
		ItemStack myMail = GuiButtons.button(
				GuiButtons.PARCEL,
				Component.literal("§d[กล่องรับของ] /mail"),
				List.of(Component.literal("§7ไอเท็มที่ซื้อสำเร็จหรือยกเลิกจะส่งเข้ากล่องรับของ"))
		);
		container.setItem(48, myMail);

		// Slot 49: Player Balance & Refresh
		long balance = economy.balance(player.getUUID());
		ItemStack info = GuiButtons.button(
				GuiButtons.COIN,
				Component.literal("§6[ยอดเงินของคุณ]: " + balance + " " + currency),
				List.of(
						Component.literal("§7หน้า " + (curPage + 1) + "/" + maxPages + " (ทั้งหมด " + active.size() + " รายการ)"),
						Component.literal("§eคลิกเพื่อรีเฟรช")
				)
		);
		container.setItem(49, info);

		// Slot 51: Next page
		if (curPage < maxPages - 1) {
			ItemStack next = GuiButtons.button(
					GuiButtons.NEXT,
					Component.literal("§eหน้าถัดไป > (" + (curPage + 2) + "/" + maxPages + ")"),
					List.of()
			);
			container.setItem(51, next);
		}

		// Slot 53: How to sell
		ItemStack howTo = GuiButtons.button(
				GuiButtons.TAG,
				Component.literal("§a[+] วิธีลงขายไอเท็ม"),
				List.of(
						Component.literal("§eวิธีนำไอเท็มลงขายในตลาด /buy:"),
						Component.literal("§71. ถือไอเท็มที่ต้องการขายไว้ในมือหลัก"),
						Component.literal("§72. พิมพ์คำสั่ง: §a/buy sell <ราคา>"),
						Component.literal("§7ตัวอย่าง: §b/buy sell 500"),
						Component.literal("§73. เมื่อมีผู้เล่นซื้อ เงินจะเข้าตัวคุณทันที"),
						Component.literal("§74. หากยกเลิก รับไอเท็มคืนได้ที่ §e/mail")
				)
		);
		container.setItem(53, howTo);

		String titleStr = myOnly
				? "§0สินค้าของฉัน (/buy) (" + (curPage + 1) + "/" + maxPages + ")"
				: "§0ตลาดซื้อสินค้าผู้เล่น (/buy) (" + (curPage + 1) + "/" + maxPages + ")";

		VirtualChestMenu.open(
				player,
				Component.literal(titleStr),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex >= 0 && slotIndex < endIdx - startIdx) {
						PlayerMarket.Listing selected = active.get(startIdx + slotIndex);
						openDetail(sp, selected, market, economy, currency, curPage, myOnly);
					} else if (slotIndex == 45 && curPage > 0) {
						open(sp, market, economy, currency, curPage - 1, myOnly);
					} else if (slotIndex == 51 && curPage < maxPages - 1) {
						open(sp, market, economy, currency, curPage + 1, myOnly);
					} else if (slotIndex == 46) {
						open(sp, market, economy, currency, 0, !myOnly);
					} else if (slotIndex == 47) {
						if (ClaimMap.instance() != null) {
							AuctionGui.open(sp, ClaimMap.instance().auctions(), economy, currency, 0, AuctionGui.ViewMode.ALL);
						}
					} else if (slotIndex == 48) {
						sp.closeContainer();
						var server = ClaimMap.getServer();
						if (server != null) {
							server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "mail");
						}
					} else if (slotIndex == 49) {
						open(sp, market, economy, currency, curPage, myOnly);
					}
				},
				null
		);
	}

	private static void openDetail(ServerPlayer sp, PlayerMarket.Listing listing, PlayerMarket market,
								   EconomyManager economy, String currency, int returnPage, boolean returnMyOnly) {
		SimpleContainer container = new SimpleContainer(27);

		boolean isOwner = listing.seller.equals(sp.getUUID().toString());
		long balance = economy.balance(sp.getUUID());

		// Slot 4: Info Banner
		ItemStack banner = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal("§6§lรายละเอียดสินค้า: " + listing.label),
				List.of(
						Component.literal("§eผู้ลงขาย: §f" + listing.sellerName),
						Component.literal("§eจำนวน: §f" + listing.count + " ชิ้น"),
						Component.literal("§eราคาขาย: §6" + listing.price + " " + currency),
						Component.literal("§eยอดเงินของคุณ: §a" + balance + " " + currency)
				)
		);
		container.setItem(4, banner);

		// Slot 13: Item display stack
		var decoded = ItemCodecs.decode(listing.itemJson, sp.level().registryAccess());
		ItemStack displayStack = decoded.orElseGet(() -> new ItemStack(Items.PAPER));
		if (listing.count > 1) {
			displayStack.setCount(Math.min(listing.count, displayStack.getMaxStackSize()));
		}
		container.setItem(13, displayStack);

		// Slot 11: Action Button (Buy or Cancel)
		if (isOwner) {
			ItemStack cancelBtn = GuiButtons.button(
					GuiButtons.TAG,
					Component.literal("§c[ยืนยันยกเลิกการลงขาย]"),
					List.of(
							Component.literal("§7คลิกเพื่อถอนรายการนี้ออกจากตลาด"),
							Component.literal("§eไอเท็มจะถูกส่งคืนเข้ากล่องพัสดุ (/mail) ของคุณ")
					)
			);
			container.setItem(11, cancelBtn);
		} else {
			ItemStack buyBtn = GuiButtons.button(
					GuiButtons.CART,
					Component.literal("§a[ยืนยันการซื้อทันที]"),
					List.of(
							Component.literal("§7คลิกเพื่อซื้อสินค้านี้ในราคา §6" + listing.price + " " + currency),
							Component.literal("§eไอเท็มจะถูกส่งไปยังกล่องพัสดุ (/mail) ของคุณทันที")
					)
			);
			container.setItem(11, buyBtn);
		}

		// Slot 15: Back Button
		ItemStack backBtn = GuiButtons.button(
				GuiButtons.BACK,
				Component.literal("§e< ย้อนกลับ"),
				List.of(Component.literal("§7กลับไปยังหน้ารายการสินค้า"))
		);
		container.setItem(15, backBtn);

		VirtualChestMenu.open(
				sp,
				Component.literal("§0" + (isOwner ? "จัดการสินค้าของคุณ" : "ยืนยันการซื้อสินค้า")),
				3,
				true,
				container,
				(slotIndex, button, input, player) -> {
					if (slotIndex == 11) {
						if (isOwner) {
							PlayerMarket.Result r = market.cancel(player.getUUID(), false, listing.id);
							if (r == PlayerMarket.Result.OK) {
								player.sendSystemMessage(Component.literal("§a[ตลาดผู้เล่น] ยกเลิกการลงขายสำเร็จ! ไอเท็มถูกส่งเข้า /mail"));
							} else {
								player.sendSystemMessage(Component.literal("§c[ตลาดผู้เล่น] ไม่สามารถยกเลิกได้: " + marketError(r)));
							}
							open(player, market, economy, currency, returnPage, returnMyOnly);
						} else {
							long bal = economy.balance(player.getUUID());
							if (bal < listing.price) {
								player.sendSystemMessage(Component.literal("§c[ตลาดผู้เล่น] เงินในบัญชีไม่พอ (ราคา "
										+ listing.price + " " + currency + ", มียอดคงเหลือ: " + bal + ")"));
								open(player, market, economy, currency, returnPage, returnMyOnly);
								return;
							}
							PlayerMarket.Result r = market.buy(player.getUUID(), player.getGameProfile().name(), listing.id);
							if (r == PlayerMarket.Result.OK) {
								player.sendSystemMessage(Component.literal("§a[ตลาดผู้เล่น] ซื้อไอเท็ม " + listing.label
										+ " x" + listing.count + " สำเร็จ! จ่ายไป " + listing.price + " " + currency
										+ " §7(ตรวจสอบไอเท็มได้ที่ /mail)"));
							} else {
								player.sendSystemMessage(Component.literal("§c[ตลาดผู้เล่น] ไม่สามารถซื้อได้: " + marketError(r)));
							}
							open(player, market, economy, currency, returnPage, returnMyOnly);
						}
					} else if (slotIndex == 15) {
						open(player, market, economy, currency, returnPage, returnMyOnly);
					}
				},
				null
		);
	}

	private static String marketError(PlayerMarket.Result r) {
		return switch (r) {
			case OWN_LISTING -> "ไม่สามารถซื้อไอเท็มของตัวเองได้";
			case INSUFFICIENT -> "เงินในบัญชีของคุณไม่เพียงพอ";
			case FROZEN -> "บัญชีการเงินของคุณถูกระงับ";
			case CLOSED -> "รายการนี้ถูกซื้อหรือปิดไปแล้ว";
			case NOT_FOUND -> "ไม่พบรายการสินค้านี้";
			case LIMIT_REACHED -> "คุณลงขายสินค้าครบจำนวนจำกัดแล้ว";
			case DISABLED -> "ระบบตลาดซื้อขายปิดปรับปรุงชั่วคราว";
			case FORBIDDEN -> "คุณไม่มีสิทธิ์จัดการรายการนี้";
			case IO_ERROR -> "กล่องรับของปลายทางเต็ม หรือเกิดข้อผิดพลาดในการส่งไอเท็ม";
			case BAD_REQUEST -> "ข้อมูลไม่ถูกต้อง";
			default -> "เกิดข้อผิดพลาด (" + r + ")";
		};
	}
}
