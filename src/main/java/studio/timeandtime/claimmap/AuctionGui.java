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

	private AuctionGui() {
	}

	public static void open(ServerPlayer player, AuctionManager auctions, EconomyManager economy, String currency, int page) {
		if (auctions.isDisabled()) {
			player.sendSystemMessage(Component.literal("§cระบบตลาดประมูลถูกปิดชั่วคราว"));
			return;
		}

		List<AuctionManager.Auction> active = auctions.all().stream()
				.filter(AuctionManager.Auction::isActive)
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
			if (a.buyout != null) {
				lore.add(Component.literal("§eราคาซื้อทันที: §a" + a.buyout + " " + currency));
			}
			lore.add(Component.literal("§eเวลาที่เหลือ: §f" + timeStr));
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§a[คลิกซ้าย] เพื่อเสนอราคา: §6" + minNextBid + " " + currency));
			if (a.buyout != null) {
				lore.add(Component.literal("§6[คลิกขวา] เพื่อซื้อทันที: §a" + a.buyout + " " + currency));
			}

			displayStack.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(slot, displayStack);
		}

		if (curPage > 0) {
			ItemStack prev = new ItemStack(Items.ARROW);
			prev.set(DataComponents.CUSTOM_NAME, Component.literal("§e◀ หน้าก่อนหน้า (" + curPage + "/" + maxPages + ")"));
			container.setItem(45, prev);
		}

		ItemStack myAuctions = new ItemStack(Items.CHEST);
		myAuctions.set(DataComponents.CUSTOM_NAME, Component.literal("§b📦 รายการประมูลของฉัน"));
		myAuctions.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7คลิกเพื่อดูและจัดการรายการที่คุณลงขาย"))));
		container.setItem(48, myAuctions);

		long balance = economy.balance(player.getUUID());
		ItemStack info = new ItemStack(Items.GOLD_INGOT);
		info.set(DataComponents.CUSTOM_NAME, Component.literal("§6💰 ยอดเงินของคุณ: " + balance + " " + currency));
		info.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§7หน้า " + (curPage + 1) + "/" + maxPages + " (ทั้งหมด " + active.size() + " รายการ)"),
				Component.literal("§eคลิกเพื่อรีเฟรช")
		)));
		container.setItem(49, info);

		ItemStack howTo = new ItemStack(Items.EMERALD);
		howTo.set(DataComponents.CUSTOM_NAME, Component.literal("§a➕ วิธีลงประมูลของ & ที่ดิน"));
		howTo.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§e[ลงประมูลไอเท็ม]:"),
				Component.literal("§7ถือของในมือแล้วพิมพ์: §e/ah sell <ราคาเริ่ม> [ซื้อทันที] [ชั่วโมง]"),
				Component.literal("§e[ลงประมูลที่ดิน/เขต]:"),
				Component.literal("§7คลิกเขตของตนเองใน §e/claimmap §7แล้วกดเปิดประมูล"),
				Component.literal("§7หรือพิมพ์: §e/claim auction <ราคาเริ่ม> [ซื้อทันที] [ชั่วโมง]")
		)));
		container.setItem(50, howTo);

		if (curPage < maxPages - 1) {
			ItemStack next = new ItemStack(Items.ARROW);
			next.set(DataComponents.CUSTOM_NAME, Component.literal("§eหน้าถัดไป ▶ (" + (curPage + 2) + "/" + maxPages + ")"));
			container.setItem(53, next);
		}

		VirtualChestMenu.open(
				player,
				Component.literal("§0ตลาดประมูล (หน้า " + (curPage + 1) + "/" + maxPages + ")"),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex >= 0 && slotIndex < endIdx - startIdx) {
						AuctionManager.Auction selected = active.get(startIdx + slotIndex);
						if (button == 0) { // Left-click: Bid
							long minBid = auctions.minBid(selected);
							AuctionManager.BidOutcome bo = auctions.bid(sp.getUUID(), sp.getGameProfile().name(), selected.id, minBid);
							if (bo.result() == AuctionManager.Result.OK) {
								sp.sendSystemMessage(Component.literal("§aเสนอราคาประมูลสำเร็จ! " + minBid + " " + currency));
							} else if (bo.result() == AuctionManager.Result.USE_BUYOUT) {
								sp.sendSystemMessage(Component.literal("§eราคาประมูลแตะราคาซื้อทันทีแล้ว กรุณาคลิกขวาเพื่อซื้อทันที"));
							} else {
								sp.sendSystemMessage(Component.literal("§cประมูลไม่ได้: " + auctionError(bo.result())));
							}
							open(sp, auctions, economy, currency, curPage);
						} else if (button == 1) { // Right-click: Buyout
							if (selected.buyout != null) {
								AuctionManager.Result r = auctions.buyout(sp.getUUID(), sp.getGameProfile().name(), selected.id);
								if (r == AuctionManager.Result.OK) {
									sp.sendSystemMessage(Component.literal("§aซื้อทันทีสำเร็จ! จ่าย " + selected.buyout + " " + currency + " (ของส่งเข้า /mail เรียบร้อยแล้ว)"));
								} else {
									sp.sendSystemMessage(Component.literal("§cซื้อไม่สำเร็จ: " + auctionError(r)));
								}
								open(sp, auctions, economy, currency, curPage);
							}
						}
					} else if (slotIndex == 45 && curPage > 0) {
						open(sp, auctions, economy, currency, curPage - 1);
					} else if (slotIndex == 53 && curPage < maxPages - 1) {
						open(sp, auctions, economy, currency, curPage + 1);
					} else if (slotIndex == 48) {
						openMyAuctions(sp, auctions, economy, currency);
					} else if (slotIndex == 49) {
						open(sp, auctions, economy, currency, curPage);
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
			if (a.buyout != null) lore.add(Component.literal("§eราคาซื้อทันที: §a" + a.buyout + " " + currency));
			lore.add(Component.literal("§7------------------------"));
			if (a.highBidder == null) {
				lore.add(Component.literal("§c[คลิกเพื่อยกเลิก] และรับของคืนเข้า /mail"));
			} else {
				lore.add(Component.literal("§7มีผู้เสนอราคาแล้ว ไม่สามารถยกเลิกได้"));
			}

			displayStack.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(i, displayStack);
		}

		ItemStack back = new ItemStack(Items.ARROW);
		back.set(DataComponents.CUSTOM_NAME, Component.literal("§e◀ กลับไปตลาดประมูล"));
		container.setItem(45, back);

		VirtualChestMenu.open(
				player,
				Component.literal("§0รายการประมูลของฉัน (" + mine.size() + " รายการ)"),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 45) {
						open(sp, auctions, economy, currency, 0);
					} else if (slotIndex >= 0 && slotIndex < Math.min(mine.size(), PAGE_SIZE)) {
						AuctionManager.Auction a = mine.get(slotIndex);
						AuctionManager.Result r = auctions.cancel(sp.getUUID(), false, a.id);
						if (r == AuctionManager.Result.OK) {
							sp.sendSystemMessage(Component.literal("§aยกเลิกรายการประมูลสำเร็จ! ของถูกส่งคืนเข้า /mail แล้ว"));
							openMyAuctions(sp, auctions, economy, currency);
						} else {
							sp.sendSystemMessage(Component.literal("§cยกเลิกไม่ได้: " + auctionError(r)));
						}
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
			case USE_BUYOUT -> "ต้องใช้การซื้อทันที";
			case LIMIT_REACHED -> "ลงขายเกินจำนวนจำกัดแล้ว";
			case INSUFFICIENT -> "เงินในบัญชีไม่พอ";
			case FROZEN -> "บัญชีถูกระงับ";
			case HAS_BIDS -> "มีผู้เสนอราคาแล้ว ไม่สามารถยกเลิกได้";
			case CLOSED -> "รายการประมูลปิดแล้ว";
			case NOT_FOUND -> "ไม่พบรายการประมูลนี้";
			case ALREADY_OWNER -> "คุณเป็นเจ้าของเขตนี้อยู่แล้ว";
			default -> "เกิดข้อผิดพลาด (" + r + ")";
		};
	}
}
