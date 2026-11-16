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

public final class AuctionGui {
	private static final int PAGE_SIZE = 45;

	public enum ViewMode {
		ALL,
		ITEMS,
		CELLS
	}

	private AuctionGui() {
	}

	public static void open(ServerPlayer player, AuctionManager auctions, EconomyManager economy, String currency, int page) {
		open(player, auctions, economy, currency, page, ViewMode.ALL);
	}

	public static void open(ServerPlayer player, AuctionManager auctions, EconomyManager economy, String currency, int page, ViewMode mode) {
		if (auctions.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบตลาดถูกปิดชั่วคราว"));
			return;
		}

		List<AuctionManager.Auction> active = auctions.all().stream()
				.filter(AuctionManager.Auction::isActive)
				.filter(a -> switch (mode) {
					case ALL -> true;
					case ITEMS -> !a.isCellAuction();
					case CELLS -> a.isCellAuction();
				})
				.toList();

		int maxPages = Math.max(1, (active.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int curPage = Math.max(0, Math.min(page, maxPages - 1));
		int startIdx = curPage * PAGE_SIZE;
		int endIdx = Math.min(startIdx + PAGE_SIZE, active.size());

		SimpleContainer container = new SimpleContainer(54);

		for (int i = startIdx; i < endIdx; i++) {
			AuctionManager.Auction a = active.get(i);
			int slot = i - startIdx;
			ItemStack displayStack;

			if (a.isCellAuction()) {
				displayStack = new ItemStack(Items.FILLED_MAP);
				displayStack.set(DataComponents.CUSTOM_NAME, Component.literal("§eประมูลเขตที่ดิน " + a.cellId));
			} else {
				var decoded = ItemCodecs.decode(a.itemJson, player.level().registryAccess());
				displayStack = decoded.orElseGet(() -> new ItemStack(Items.PAPER));
				if (a.count > 1) displayStack.setCount(Math.min(a.count, displayStack.getMaxStackSize()));
			}

			long minNextBid = auctions.minBid(a);
			long currentPrice = a.highBid > 0 ? a.highBid : a.startPrice;
			long timeLeftMs = Math.max(0L, a.endsAt - System.currentTimeMillis());
			long minutesLeft = timeLeftMs / 60_000L;
			String timeStr = minutesLeft >= 60 ? (minutesLeft / 60) + " ชม. " + (minutesLeft % 60) + " นาที" : minutesLeft + " นาที";

			List<Component> lore = new ArrayList<>();
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§eผู้ลงขาย: §f" + a.sellerName));
			lore.add(Component.literal("§eราคาประมูลปัจจุบัน: §6" + currentPrice + " " + currency
					+ (a.highBidderName != null ? " §7(" + a.highBidderName + ")" : " §7(ยังไม่มีผู้ประมูล)")));
			lore.add(Component.literal("§eราคาเสนอขั้นต่ำ: §a" + minNextBid + " " + currency));
			lore.add(Component.literal("§eเวลาที่เหลือ: §f" + timeStr));
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§a[คลิก] ดูรายละเอียด & เลือกจำนวนเงินเสนอราคา"));

			displayStack.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(slot, displayStack);
		}

		if (curPage > 0) {
			ItemStack prev = GuiButtons.button(
					GuiButtons.BACK,
					Component.literal("§e< หน้าก่อนหน้า (" + curPage + "/" + maxPages + ")"),
					List.of()
			);
			container.setItem(45, prev);
		}

		// Mode buttons (Custom Icon Items)
		ItemStack itemsBtn = GuiButtons.button(
				GuiButtons.CART,
				Component.literal((mode == ViewMode.ITEMS ? "§a" : "§f") + "ของที่ผู้เล่นลงขาย"),
				List.of(Component.literal("§7คลิกเพื่อกรองดูเฉพาะสินค้าไอเท็ม"))
		);
		container.setItem(46, itemsBtn);

		ItemStack cellsBtn = GuiButtons.button(
				GuiButtons.MAP,
				Component.literal((mode == ViewMode.CELLS ? "§a" : "§f") + "ที่ดินประมูล"),
				List.of(Component.literal("§7คลิกเพื่อกรองดูเฉพาะเขตที่ดิน"))
		);
		container.setItem(47, cellsBtn);

		ItemStack myAuctions = GuiButtons.button(
				GuiButtons.TAG,
				Component.literal("§b[รายการของฉัน]"),
				List.of(Component.literal("§7คลิกเพื่อดูและจัดการรายการที่คุณลงขาย"))
		);
		container.setItem(48, myAuctions);

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

		ItemStack allBtn = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal((mode == ViewMode.ALL ? "§a" : "§f") + "แสดงทั้งหมด"),
				List.of(Component.literal("§7แสดงทั้งสินค้าไอเท็มและที่ดิน"))
		);
		container.setItem(50, allBtn);

		ItemStack howTo = GuiButtons.button(
				GuiButtons.PARCEL,
				Component.literal("§a[+] วิธีลงขาย & ประมูล"),
				List.of(
						Component.literal("§e[ลงประมูลของให้ผู้เล่น]:"),
						Component.literal("§7ถือของในมือแล้วพิมพ์: §a/ah sell <ราคาเริ่มต้น> [ชั่วโมง]"),
						Component.literal("§7ตัวอย่าง: §b/ah sell 100 24"),
						Component.literal("§e[ลงประมูลที่ดิน/เขต]:"),
						Component.literal("§7คลิกเขตของตนเองใน §e/claimmap §7แล้วกดเปิดประมูล"),
						Component.literal("§7หรือพิมพ์: §e/claim auction <ราคาเริ่มต้น> [ชั่วโมง]"),
						Component.literal("§e[เจ้าของยกเลิกการประมูล]:"),
						Component.literal("§7คลิกที่รายการของคุณแล้วกดปุ่ม §c[ยกเลิกการประมูล]"),
						Component.literal("§7หากมีผู้เสนอราคา ระบบจะคืนเงินให้ผู้เสนอราคาทันที")
				)
		);
		container.setItem(51, howTo);

		if (curPage < maxPages - 1) {
			ItemStack next = GuiButtons.button(
					GuiButtons.NEXT,
					Component.literal("§eหน้าถัดไป > (" + (curPage + 2) + "/" + maxPages + ")"),
					List.of()
			);
			container.setItem(53, next);
		}

		String titleStr = switch (mode) {
			case ALL -> "§0ตลาดผู้เล่น (หน้า " + (curPage + 1) + "/" + maxPages + ")";
			case ITEMS -> "§0ของที่ผู้เล่นลงขาย (หน้า " + (curPage + 1) + "/" + maxPages + ")";
			case CELLS -> "§0ประมูลที่ดิน (หน้า " + (curPage + 1) + "/" + maxPages + ")";
		};

		VirtualChestMenu.open(
				player,
				Component.literal(titleStr),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex >= 0 && slotIndex < endIdx - startIdx) {
						AuctionManager.Auction selected = active.get(startIdx + slotIndex);
						openAuctionDetail(sp, selected, auctions, economy, currency, curPage, mode);
					} else if (slotIndex == 45 && curPage > 0) {
						open(sp, auctions, economy, currency, curPage - 1, mode);
					} else if (slotIndex == 53 && curPage < maxPages - 1) {
						open(sp, auctions, economy, currency, curPage + 1, mode);
					} else if (slotIndex == 46) {
						open(sp, auctions, economy, currency, 0, ViewMode.ITEMS);
					} else if (slotIndex == 47) {
						open(sp, auctions, economy, currency, 0, ViewMode.CELLS);
					} else if (slotIndex == 48) {
						openMyAuctions(sp, auctions, economy, currency);
					} else if (slotIndex == 49) {
						open(sp, auctions, economy, currency, curPage, mode);
					} else if (slotIndex == 50) {
						open(sp, auctions, economy, currency, 0, ViewMode.ALL);
					}
				},
				null
		);
	}

	public static void openAuctionDetail(ServerPlayer player, AuctionManager.Auction a,
										 AuctionManager auctions, EconomyManager economy,
										 String currency, int returnPage, ViewMode returnMode) {
		SimpleContainer container = new SimpleContainer(27);
		String myUuid = player.getUUID().toString();
		boolean isOwner = a.seller.equals(myUuid);
		long balance = economy.balance(player.getUUID());
		long currentPrice = a.highBid > 0 ? a.highBid : a.startPrice;
		long minNextBid = auctions.minBid(a);
		long nowMs = System.currentTimeMillis();
		long timeLeftMs = Math.max(0L, a.endsAt - nowMs);
		long minutesLeft = timeLeftMs / 60_000L;
		String timeStr = minutesLeft >= 60 ? (minutesLeft / 60) + " ชม. " + (minutesLeft % 60) + " นาที" : minutesLeft + " นาที";

		// Slot 0: Back button
		ItemStack back = GuiButtons.button(
				GuiButtons.BACK,
				Component.literal("§e< กลับไปตลาดประมูล"),
				List.of()
		);
		container.setItem(0, back);

		// Slot 4: Item display
		ItemStack displayStack;
		if (a.isCellAuction()) {
			displayStack = GuiButtons.button(
					GuiButtons.MAP,
					Component.literal("§eประมูลเขตที่ดิน " + a.cellId),
					List.of()
			);
		} else {
			var decoded = ItemCodecs.decode(a.itemJson, player.level().registryAccess());
			displayStack = decoded.orElseGet(() -> new ItemStack(Items.PAPER));
			if (a.count > 1) displayStack.setCount(Math.min(a.count, displayStack.getMaxStackSize()));
		}
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("§7------------------------"));
		lore.add(Component.literal("§eผู้ลงประมูล: §f" + a.sellerName));
		lore.add(Component.literal("§eราคาเริ่มต้น: §7" + a.startPrice + " " + currency));
		lore.add(Component.literal("§eราคาประมูลปัจจุบัน: §6" + currentPrice + " " + currency));
		lore.add(Component.literal("§eผู้เสนอราคาสูงสุด: §f" + (a.highBidderName != null ? a.highBidderName : "ยังไม่มีผู้ประมูล")));
		lore.add(Component.literal("§eราคาขั้นต่ำครั้งถัดไป: §a" + minNextBid + " " + currency));
		lore.add(Component.literal("§eเวลาที่เหลือ: §f" + timeStr));
		lore.add(Component.literal("§7------------------------"));
		displayStack.set(DataComponents.LORE, new ItemLore(lore));
		container.setItem(4, displayStack);

		// Slot 8: Balance info
		ItemStack balItem = GuiButtons.button(
				GuiButtons.COIN,
				Component.literal("§6[ยอดเงินของคุณ]: " + balance + " " + currency),
				List.of()
		);
		container.setItem(8, balItem);

		if (isOwner) {
			// Owner controls: Cancel auction and return item/land
			List<Component> cancelLore = new ArrayList<>();
			cancelLore.add(Component.literal("§7คุณเป็นเจ้าของรายการประมูลนี้"));
			cancelLore.add(Component.literal("§7------------------------"));
			if (a.highBidder != null) {
				cancelLore.add(Component.literal("§eมีผู้เสนอราคาสูงสุด: §f" + a.highBidderName + " (" + a.highBid + " " + currency + ")"));
				cancelLore.add(Component.literal("§7หากกดยกเลิก ระบบจะคืนเงินเต็มจำนวนให้ผู้เสนอราคา"));
			} else {
				cancelLore.add(Component.literal("§7ยังไม่มีผู้ใดเสนอราคา"));
			}
			cancelLore.add(Component.literal("§c[คลิก] เพื่อยกเลิกการประมูลและรับของคืนเข้า /mail"));
			cancelLore.add(Component.literal("§7------------------------"));
			ItemStack cancelBtn = GuiButtons.button(
					GuiButtons.ABANDON,
					Component.literal("§c[ยกเลิกการประมูล]"),
					cancelLore
			);
			container.setItem(13, cancelBtn);
		} else {
			boolean isTopBidder = myUuid.equals(a.highBidder);
			if (isTopBidder) {
				ItemStack topItem = GuiButtons.button(
						GuiButtons.TAG,
						Component.literal("§aคุณเป็นผู้เสนอราคาสูงสุดอยู่แล้ว"),
						List.of(
								Component.literal("§7ยอดเสนอราคาของคุณ: §6" + a.highBid + " " + currency),
								Component.literal("§7รอผู้เล่นอื่นเสนอราคาแข่ง หรือรอเจ้าของกดปิดประมูล")
						)
				);
				container.setItem(13, topItem);
			} else {
				// Choices to increase bid:
				// Option 1: Minimum required bid (Slot 10)
				ItemStack b1 = GuiButtons.button(
						GuiButtons.COIN,
						Component.literal("§aเสนอราคาขั้นต่ำ (" + minNextBid + " " + currency + ")"),
						List.of(Component.literal("§7คลิกเพื่อเสนอราคาขั้นต่ำทันที: §6" + minNextBid + " " + currency))
				);
				container.setItem(10, b1);

				// Option 2: Min + 500 (Slot 12)
				long bid2 = minNextBid + 500L;
				ItemStack b2 = GuiButtons.button(
						GuiButtons.COIN,
						Component.literal("§aเพิ่มอีก +500 (" + bid2 + " " + currency + ")"),
						List.of(Component.literal("§7คลิกเพื่อเสนอราคา: §6" + bid2 + " " + currency))
				);
				container.setItem(12, b2);

				// Option 3: Min + 1,000 (Slot 14)
				long bid3 = minNextBid + 1_000L;
				ItemStack b3 = GuiButtons.button(
						GuiButtons.COIN,
						Component.literal("§aเพิ่มอีก +1,000 (" + bid3 + " " + currency + ")"),
						List.of(Component.literal("§7คลิกเพื่อเสนอราคา: §6" + bid3 + " " + currency))
				);
				container.setItem(14, b3);

				// Option 4: Min + 5,000 (Slot 16)
				long bid4 = minNextBid + 5_000L;
				ItemStack b4 = GuiButtons.button(
						GuiButtons.COIN,
						Component.literal("§aเพิ่มอีก +5,000 (" + bid4 + " " + currency + ")"),
						List.of(Component.literal("§7คลิกเพื่อเสนอราคา: §6" + bid4 + " " + currency))
				);
				container.setItem(16, b4);
			}

			// Slot 22: Custom bid in chat
			ItemStack custom = GuiButtons.button(
					GuiButtons.CHAT,
					Component.literal("§e[กำหนดราคาเองในแชท]"),
					List.of(
							Component.literal("§7หากต้องการเสนอราคาอื่น สามารถพิมพ์ในแชท:"),
							Component.literal("§a/ah bid " + a.id + " <จำนวนเงิน>"),
							Component.literal("§7(ยอดเงินต้องไม่ต่ำกว่า " + minNextBid + " " + currency + ")")
					)
			);
			container.setItem(22, custom);
		}

		VirtualChestMenu.open(
				player,
				Component.literal("§0เสนอราคา: " + (a.isCellAuction() ? "เขต " + a.cellId : a.label)),
				3,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 0) {
						open(sp, auctions, economy, currency, returnPage, returnMode);
					} else if (isOwner && slotIndex == 13) {
						AuctionManager.Result res = auctions.cancel(sp.getUUID(), false, a.id);
						if (res == AuctionManager.Result.OK) {
							sp.sendSystemMessage(Component.literal("§aยกเลิกการประมูลสำเร็จ! สินค้า/โฉนดถูกส่งคืนเข้ากล่อง /mail"));
							open(sp, auctions, economy, currency, returnPage, returnMode);
						} else {
							sp.sendSystemMessage(Component.literal("§cไม่สามารถยกเลิกการประมูลได้: " + res));
						}
					} else if (!isOwner && (slotIndex == 10 || slotIndex == 12 || slotIndex == 14 || slotIndex == 16)) {
						long targetBid = minNextBid;
						if (slotIndex == 12) targetBid += 500L;
						else if (slotIndex == 14) targetBid += 1_000L;
						else if (slotIndex == 16) targetBid += 5_000L;

						long bal = economy.balance(sp.getUUID());
						if (bal < targetBid) {
							sp.sendSystemMessage(Component.literal("§cเงินของคุณไม่พอ ต้องการ " + targetBid + " " + currency + " (คุณมี " + bal + " " + currency + ")"));
							return;
						}
						AuctionManager.BidOutcome bo = auctions.bid(sp.getUUID(), sp.getGameProfile().name(), a.id, targetBid);
						if (bo.result() == AuctionManager.Result.OK) {
							sp.sendSystemMessage(Component.literal("§aเสนอราคาประมูลสำเร็จ! ยอดเสนอ " + targetBid + " " + currency));
							AuctionManager.Auction updated = auctions.byId(a.id);
							if (updated != null && updated.isActive()) {
								openAuctionDetail(sp, updated, auctions, economy, currency, returnPage, returnMode);
							} else {
								open(sp, auctions, economy, currency, returnPage, returnMode);
							}
						} else {
							sp.sendSystemMessage(Component.literal("§cประมูลไม่ได้: " + auctionError(bo.result())));
							AuctionManager.Auction updated = auctions.byId(a.id);
							if (updated != null && updated.isActive()) {
								openAuctionDetail(sp, updated, auctions, economy, currency, returnPage, returnMode);
							} else {
								open(sp, auctions, economy, currency, returnPage, returnMode);
							}
						}
					}
				},
				null
		);
	}

	public static void openMyAuctions(ServerPlayer player, AuctionManager auctions, EconomyManager economy, String currency) {
		String myUuid = player.getUUID().toString();
		List<AuctionManager.Auction> mine = auctions.all().stream()
				.filter(a -> a.seller.equals(myUuid) && a.isActive())
				.toList();

		SimpleContainer container = new SimpleContainer(54);

		for (int i = 0; i < Math.min(mine.size(), PAGE_SIZE); i++) {
			AuctionManager.Auction a = mine.get(i);
			ItemStack displayStack;
			if (a.isCellAuction()) {
				displayStack = new ItemStack(Items.FILLED_MAP);
				displayStack.set(DataComponents.CUSTOM_NAME, Component.literal("§eประมูลเขต " + a.cellId));
			} else {
				var decoded = ItemCodecs.decode(a.itemJson, player.level().registryAccess());
				displayStack = decoded.orElseGet(() -> new ItemStack(Items.PAPER));
				if (a.count > 1) displayStack.setCount(Math.min(a.count, displayStack.getMaxStackSize()));
			}

			long currentPrice = a.highBid > 0 ? a.highBid : a.startPrice;
			List<Component> lore = new ArrayList<>();
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§eราคาประมูลปัจจุบัน: §6" + currentPrice + " " + currency));
			lore.add(Component.literal("§eผู้เสนอราคาสูงสุด: §f" + (a.highBidderName != null ? a.highBidderName : "ยังไม่มี")));
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§c[คลิก] เพื่อดูรายละเอียด & ขอยกเลิกรายการ"));

			displayStack.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(i, displayStack);
		}

		ItemStack back = GuiButtons.button(
				GuiButtons.BACK,
				Component.literal("§e< กลับไปตลาดผู้เล่น"),
				List.of()
		);
		container.setItem(45, back);

		VirtualChestMenu.open(
				player,
				Component.literal("§0รายการประมูลของฉัน (" + mine.size() + " รายการ)"),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 45) {
						open(sp, auctions, economy, currency, 0, ViewMode.ALL);
					} else if (slotIndex >= 0 && slotIndex < Math.min(mine.size(), PAGE_SIZE)) {
						openAuctionDetail(sp, mine.get(slotIndex), auctions, economy, currency, 0, ViewMode.ALL);
					}
				},
				null
		);
	}

	private static String auctionError(AuctionManager.Result r) {
		return switch (r) {
			case OWN_AUCTION -> "ประมูลรายการของตัวเองไม่ได้";
			case ALREADY_HIGHEST -> "คุณเป็นผู้เสนอราคาสูงสุดอยู่แล้ว";
			case BID_TOO_LOW -> "ราคาเสนอต่ำเกินไป";
			case USE_BUYOUT -> "ต้องใช้การเสนอราคา";
			case LIMIT_REACHED -> "ลงขายเกินจำนวนจำกัดแล้ว";
			case INSUFFICIENT -> "เงินในบัญชีไม่พอ";
			case FROZEN -> "บัญชีถูกระงับ";
			case CLOSED -> "รายการประมูลปิดแล้ว";
			case NOT_FOUND -> "ไม่พบรายการประมูลนี้";
			case DISABLED -> "ระบบตลาดปิดปรับปรุง";
			case ALREADY_OWNER -> "คุณเป็นเจ้าของเขตนี้อยู่แล้ว";
			case FORBIDDEN -> "ไม่มีสิทธิ์จัดการรายการนี้";
			case BAD_REQUEST -> "ข้อมูลไม่ถูกต้อง";
			case IO_ERROR -> "เกิดข้อผิดพลาดในการบันทึกข้อมูล";
			default -> "เกิดข้อผิดพลาด (" + r + ")";
		};
	}
}
