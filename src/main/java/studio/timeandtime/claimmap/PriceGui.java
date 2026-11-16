package studio.timeandtime.claimmap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PriceGui {
	private static final int PAGE_SIZE = 45;

	private PriceGui() {
	}

	public enum Category {
		ALL("[ทั้งหมด]"),
		ORES("[แร่ & อัญมณี]"),
		FARMING("[ผลผลิต & เกษตร]"),
		BUILDING("[บล็อก & ก่อสร้าง]"),
		MOB_DROPS("[มอนสเตอร์ดรอป]");

		public final String label;

		Category(String label) {
			this.label = label;
		}

		public Category next() {
			Category[] vals = values();
			return vals[(ordinal() + 1) % vals.length];
		}
	}

	private static boolean matchesCategory(String id, Category cat) {
		if (cat == Category.ALL) return true;
		String lower = id.toLowerCase();
		return switch (cat) {
			case ALL -> true;
			case ORES -> lower.contains("diamond") || lower.contains("emerald") || lower.contains("netherite")
					|| lower.contains("gold") || lower.contains("iron") || lower.contains("copper")
					|| lower.contains("lapis") || lower.contains("redstone") || lower.contains("coal")
					|| lower.contains("quartz") || lower.contains("amethyst");
			case FARMING -> lower.contains("wheat") || lower.contains("carrot") || lower.contains("potato")
					|| lower.contains("beetroot") || lower.contains("apple") || lower.contains("bread")
					|| lower.contains("melon") || lower.contains("pumpkin") || lower.contains("beef")
					|| lower.contains("pork") || lower.contains("chicken") || lower.contains("mutton")
					|| lower.contains("egg") || lower.contains("sugar_cane") || lower.contains("kelp")
					|| lower.contains("fish") || lower.contains("salmon") || lower.contains("cod");
			case BUILDING -> lower.contains("log") || lower.contains("wood") || lower.contains("planks")
					|| lower.contains("stone") || lower.contains("cobble") || lower.contains("deepslate")
					|| lower.contains("granite") || lower.contains("diorite") || lower.contains("andesite")
					|| lower.contains("glass") || lower.contains("brick") || lower.contains("wool")
					|| lower.contains("terracotta") || lower.contains("concrete") || lower.contains("sand")
					|| lower.contains("gravel") || lower.contains("dirt");
			case MOB_DROPS -> lower.contains("rotten_flesh") || lower.contains("bone") || lower.contains("string")
					|| lower.contains("spider_eye") || lower.contains("gunpowder") || lower.contains("ender_pearl")
					|| lower.contains("blaze_rod") || lower.contains("slime_ball") || lower.contains("leather")
					|| lower.contains("feather") || lower.contains("ghast_tear") || lower.contains("shulker_shell");
		};
	}

	private static ItemStack stackFor(String itemId) {
		try {
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
			if (item != null && item != Items.AIR) {
				return new ItemStack(item);
			}
		} catch (Exception ignored) {
		}
		return new ItemStack(Items.PAPER);
	}

	private static int countInInventory(ServerPlayer player, String itemId) {
		int count = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (s != null && !s.isEmpty() && ItemCodecs.idOf(s).equals(itemId)) {
				count += s.getCount();
			}
		}
		return count;
	}

	public static void open(ServerPlayer player, PriceService prices, ShopManager shop,
							String currency, int page, int categoryIndex) {
		Category[] categories = Category.values();
		Category cat = categories[Math.max(0, Math.min(categoryIndex, categories.length - 1))];

		List<PriceService.ItemPriceDetail> allDetails = prices.detailsSnapshot().stream()
				.filter(d -> !d.blocked() && (d.sellPrice() > 0 || d.buyPrice() > 0))
				.filter(d -> matchesCategory(d.id(), cat))
				.sorted(Comparator.comparingDouble(PriceService.ItemPriceDetail::sellPrice).reversed())
				.toList();

		int maxPages = Math.max(1, (allDetails.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int curPage = Math.max(0, Math.min(page, maxPages - 1));
		int startIdx = curPage * PAGE_SIZE;
		int endIdx = Math.min(startIdx + PAGE_SIZE, allDetails.size());

		SimpleContainer container = new SimpleContainer(54);

		for (int i = startIdx; i < endIdx; i++) {
			PriceService.ItemPriceDetail d = allDetails.get(i);
			int slot = i - startIdx;
			ItemStack displayStack = stackFor(d.id());

			long sellPrice = shop.sellUnitPrice(d.id());
			long buyPrice = prices.buyPrice(d.id()).orElse(0L);
			int multPct = (int) Math.round(d.multiplier() * 100.0);
			String demandText = multPct > 100 ? "§aสูง (+" + (multPct - 100) + "%)"
					: multPct < 100 ? "§cต่ำ (" + (multPct - 100) + "%)" : "§7ปกติ (100%)";

			int inInv = countInInventory(player, d.id());

			List<Component> lore = new ArrayList<>();
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§eราคารับซื้อ: §a" + sellPrice + " " + currency));
			lore.add(Component.literal("§7  • 1 กอง (64x): §a" + (sellPrice * 64) + " " + currency));
			if (buyPrice > 0) {
				lore.add(Component.literal("§cราคาขายออก: §f" + buyPrice + " " + currency));
			} else {
				lore.add(Component.literal("§cราคาขายออก: §7ไม่มีขาย"));
			}
			lore.add(Component.literal("§eความต้องการตลาด: " + demandText));
			if (inInv > 0) {
				lore.add(Component.literal("§7------------------------"));
				lore.add(Component.literal("§bในกระเป๋าของคุณ: §f" + inInv + " ชิ้น §7(รวม §a" + (sellPrice * inInv) + " " + currency + "§7)"));
			}
			lore.add(Component.literal("§7------------------------"));
			lore.add(Component.literal("§e[คลิก] §7เพื่อดูรายละเอียดเพิ่มเติม"));

			displayStack.set(DataComponents.LORE, new ItemLore(lore));
			container.setItem(slot, displayStack);
		}

		if (curPage > 0) {
			ItemStack prev = new ItemStack(Items.ARROW);
			prev.set(DataComponents.CUSTOM_NAME, Component.literal("§e< หน้าก่อนหน้า (" + curPage + "/" + maxPages + ")"));
			container.setItem(45, prev);
		}

		ItemStack catBtn = new ItemStack(Items.COMPASS);
		catBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§6หมวดหมู่: " + cat.label));
		catBtn.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§7หมวดหมู่ปัจจุบัน: " + cat.label),
				Component.literal("§e[คลิก] §7เพื่อสลับหมวดหมู่ถัดไป")
		)));
		container.setItem(48, catBtn);

		ItemStack infoBtn = new ItemStack(Items.CHEST);
		long bal = ClaimMap.instance() != null && ClaimMap.instance().economy() != null
				? ClaimMap.instance().economy().balance(player.getUUID()) : 0L;
		infoBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§e[ตลาดราคากลาง]"));
		infoBtn.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§7รายการในหมวดนี้: §f" + allDetails.size() + " ชิ้น"),
				Component.literal("§7หน้า " + (curPage + 1) + "/" + maxPages),
				Component.literal("§6ยอดเงินของคุณ: §f" + bal + " " + currency),
				Component.literal("§7พิมพ์ §e/sell §7เพื่อเปิดเมนูขายของทันที")
		)));
		container.setItem(49, infoBtn);

		ItemStack handBtn = new ItemStack(Items.SPYGLASS);
		ItemStack held = player.getMainHandItem();
		boolean hasHeld = held != null && !held.isEmpty();
		handBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§b[ค้นหา] เช็คของที่ถือในมือ"));
		handBtn.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal(hasHeld ? "§7ในมือถือ: §f" + held.getHoverName().getString() : "§7(มือหลักว่างเปล่า)"),
				Component.literal("§e[คลิก] §7เพื่อเปิดดูราคาของที่ถือในมือทันที")
		)));
		container.setItem(50, handBtn);

		if (curPage < maxPages - 1) {
			ItemStack next = new ItemStack(Items.ARROW);
			next.set(DataComponents.CUSTOM_NAME, Component.literal("§eหน้าถัดไป > (" + (curPage + 2) + "/" + maxPages + ")"));
			container.setItem(53, next);
		}

		VirtualChestMenu.open(
				player,
				Component.literal("§0เช็คราคาไอเท็ม (" + cat.label + " " + (curPage + 1) + "/" + maxPages + ")"),
				6,
				true,
				container,
				(slotIndex, button, clickedStack, sp) -> {
					if (slotIndex >= 0 && slotIndex < endIdx - startIdx) {
						PriceService.ItemPriceDetail selected = allDetails.get(startIdx + slotIndex);
						openDetail(sp, prices, shop, currency, selected.id(), curPage, cat.ordinal());
					} else if (slotIndex == 45 && curPage > 0) {
						open(sp, prices, shop, currency, curPage - 1, cat.ordinal());
					} else if (slotIndex == 48) {
						int nextCat = (cat.ordinal() + 1) % categories.length;
						open(sp, prices, shop, currency, 0, nextCat);
					} else if (slotIndex == 50) {
						ItemStack h = sp.getMainHandItem();
						if (h != null && !h.isEmpty()) {
							openDetail(sp, prices, shop, currency, ItemCodecs.idOf(h), curPage, cat.ordinal());
						} else {
							sp.sendSystemMessage(Component.literal("§eกรุณาถือไอเท็มในมือหลักที่ต้องการเช็คราคา"));
						}
					} else if (slotIndex == 53 && curPage < maxPages - 1) {
						open(sp, prices, shop, currency, curPage + 1, cat.ordinal());
					}
				},
				null
		);
	}

	public static void openDetail(ServerPlayer player, PriceService prices, ShopManager shop,
								  String currency, String itemId, int returnPage, int returnCategory) {
		String validId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
		long sellPrice = shop.sellUnitPrice(validId);
		long buyPrice = prices.buyPrice(validId).orElse(0L);
		double mult = prices.detailsSnapshot().stream()
				.filter(d -> d.id().equals(validId))
				.mapToDouble(PriceService.ItemPriceDetail::multiplier)
				.findFirst().orElse(1.0);
		int multPct = (int) Math.round(mult * 100.0);
		String demandText = multPct > 100 ? "§aความต้องการสูง (+" + (multPct - 100) + "%)"
				: multPct < 100 ? "§cความต้องการต่ำ (" + (multPct - 100) + "%)" : "§7ความต้องการปกติ (100%)";

		int inInv = countInInventory(player, validId);

		SimpleContainer container = new SimpleContainer(27);

		ItemStack icon = stackFor(validId);
		icon.set(DataComponents.CUSTOM_NAME, Component.literal("§6" + ItemRegistryAdapter.label(validId)));
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("§7รหัสไอเท็ม: §8" + validId));
		lore.add(Component.literal("§7------------------------"));
		lore.add(Component.literal("§eราคารับซื้อ (เซิร์ฟเวอร์จ่าย):"));
		lore.add(Component.literal("§a  • 1 ชิ้น: " + sellPrice + " " + currency));
		lore.add(Component.literal("§a  • 16 ชิ้น: " + (sellPrice * 16) + " " + currency));
		lore.add(Component.literal("§a  • 64 ชิ้น: " + (sellPrice * 64) + " " + currency));
		lore.add(Component.literal("§7------------------------"));
		if (buyPrice > 0) {
			lore.add(Component.literal("§cราคาขายออก (เซิร์ฟเวอร์ขาย):"));
			lore.add(Component.literal("§f  • 1 ชิ้น: " + buyPrice + " " + currency));
			lore.add(Component.literal("§f  • 16 ชิ้น: " + (buyPrice * 16) + " " + currency));
			lore.add(Component.literal("§f  • 64 ชิ้น: " + (buyPrice * 64) + " " + currency));
		} else {
			lore.add(Component.literal("§cราคาขายออก: §7เซิร์ฟเวอร์ไม่มีขาย"));
		}
		lore.add(Component.literal("§7------------------------"));
		lore.add(Component.literal("§eแนวโน้มตลาด: " + demandText));
		lore.add(Component.literal("§bของในตัวคุณ: §f" + inInv + " ชิ้น §7(ประเมินรวม: §a" + (sellPrice * inInv) + " " + currency + "§7)"));
		icon.set(DataComponents.LORE, new ItemLore(lore));
		container.setItem(13, icon);

		ItemStack sellBtn = new ItemStack(Items.EMERALD);
		sellBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§a[ขาย] เปิดเมนูขายของ (/sell)"));
		sellBtn.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§7คลิกเพื่อเปิดหน้าต่างวางของขายทันที"),
				Component.literal("§eของที่วางในกล่องจะถูกประเมินและขายอัตโนมัติ")
		)));
		container.setItem(11, sellBtn);

		ItemStack ahBtn = new ItemStack(Items.GOLD_INGOT);
		ahBtn.set(DataComponents.CUSTOM_NAME, Component.literal("§e[ประมูล] ลงประมูลไอเท็มนี้ (/ah)"));
		ahBtn.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§7หากต้องการเปิดประมูลให้ผู้เล่นอื่นแข่งราคา"),
				Component.literal("§7ถือของในมือแล้วพิมพ์:"),
				Component.literal("§e/ah sell <ราคาเริ่ม> [ชั่วโมง]")
		)));
		container.setItem(15, ahBtn);

		ItemStack back = new ItemStack(Items.ARROW);
		back.set(DataComponents.CUSTOM_NAME, Component.literal("§e< กลับไปยังรายการราคาทั้งหมด"));
		container.setItem(22, back);

		VirtualChestMenu.open(
				player,
				Component.literal("§0ราคา: " + ItemRegistryAdapter.label(validId)),
				3,
				true,
				container,
				(slotIndex, button, clickedStack, sp) -> {
					if (slotIndex == 11) {
						sp.closeContainer();
						var server = ClaimMap.getServer();
						if (server != null) {
							server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "sell");
						}
					} else if (slotIndex == 22) {
						open(sp, prices, shop, currency, returnPage, returnCategory);
					}
				},
				null
		);
	}
}
