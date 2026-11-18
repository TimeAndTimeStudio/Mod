package studio.timeandtime.claimmap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public final class ClaimMapGui {
	private ClaimMapGui() {
	}

	private static ItemStack itemById(String id) {
		var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
		return item != null ? new ItemStack(item) : new ItemStack(Items.PAPER);
	}

	public static void open(ServerPlayer player, RegionManager regions, ClaimMapConfig config,
							EconomyManager economy, int centerCx, int centerCz) {
		if (player.level() != player.level().getServer().overworld()) {
			player.sendSystemMessage(Component.literal("§cระบบแผนที่เรดาร์และเขตที่ดินเปิดใช้งานเฉพาะในโลกหลักเท่านั้น"));
			return;
		}
		int playerCx = Math.floorDiv(player.getBlockX(), config.cellSize);
		int playerCz = Math.floorDiv(player.getBlockZ(), config.cellSize);
		String myUuid = player.getUUID().toString();
		int myClaimCount = regions.countClaimedBy(myUuid);

		long landPrice = ClaimMap.instance() != null && ClaimMap.instance().landPrices() != null
				? ClaimMap.instance().landPrices().currentPrice()
				: 100_000L;

		SimpleContainer container = new SimpleContainer(54);

		// Grid: 5 rows (0..4), 7 columns (1..7) -> 35 cells
		for (int r = 0; r < 5; r++) {
			for (int c = 1; c <= 7; c++) {
				int slot = r * 9 + c;
				int cellX = centerCx + (c - 4);
				int cellZ = centerCz + (r - 2);
				String cellId = cellX + "," + cellZ;

				RegionManager.CellData cellData = regions.get(cellId);

				ItemStack stack;
				if (cellData != null) {
					if (cellData.isOwner(myUuid)) {
						stack = itemById("minecraft:lime_stained_glass_pane");
						stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a[เขตของคุณ] ช่อง " + cellId));
					} else {
						stack = itemById("minecraft:red_stained_glass_pane");
						stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c[เขตของ " + cellData.ownerName + "] ช่อง " + cellId));
					}
				} else {
					stack = itemById("minecraft:light_blue_stained_glass_pane");
					stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f[เขตว่าง] ช่อง " + cellId));
				}

				List<Component> lore = new ArrayList<>();
				lore.add(Component.literal("§7พิกัด X: " + (cellX * config.cellSize) + ".." + (cellX * config.cellSize + config.cellSize - 1)));
				lore.add(Component.literal("§7พิกัด Z: " + (cellZ * config.cellSize) + ".." + (cellZ * config.cellSize + config.cellSize - 1)));

				if (cellData != null) {
					lore.add(Component.literal("§eเจ้าของ: §f" + cellData.ownerName));
					lore.add(Component.literal("§eสมาชิก: §7" + (cellData.members == null || cellData.members.isEmpty() ? "ไม่มี" : cellData.members.size() + " คน")));
					lore.add(Component.literal("§ePvP: " + (cellData.pvp ? "§aเปิด" : "§cปิด")));
					if (cellData.isOwner(myUuid) || player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
						lore.add(Component.literal("§7------------------------"));
						lore.add(Component.literal("§b[คลิก] จัดการเขตนี้"));
					}
				} else if (!isCommon) {
					boolean isFree = myClaimCount < config.freeCellsPerPlayer;
					lore.add(Component.literal("§eสถานะ: §a" + (isFree ? "รับฟรี (" + (myClaimCount + 1) + "/" + config.freeCellsPerPlayer + " ช่อง)"
							: "ราคาซื้อ: " + landPrice + " " + config.currencyName)));
					lore.add(Component.literal("§7------------------------"));
					lore.add(Component.literal("§a[คลิก] " + (isFree ? "รับสิทธิ์ครอบครองฟรี" : "ซื้อที่ดินเขตนี้")));
				}

				stack.set(DataComponents.LORE, new ItemLore(lore));
				container.setItem(slot, stack);
			}
		}

		// Info item
		ItemStack info = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal("§e[ข้อมูล] การถือครองเขต"),
				List.of(
						Component.literal("§7ถือครองแล้ว: §f" + myClaimCount + " ช่อง"),
						Component.literal("§7สิทธิ์ฟรี: §a" + Math.min(myClaimCount, config.freeCellsPerPlayer) + "/" + config.freeCellsPerPlayer + " ช่อง (ซื้อเพิ่มได้ไม่จำกัด)"),
						Component.literal("§7ราคาที่ดินปัจจุบัน: §6" + landPrice + " " + config.currencyName),
						Component.literal("§7เงินของคุณ: §6" + economy.balance(player.getUUID()) + " " + config.currencyName)
				)
		);
		container.setItem(0, info);

		// Navigation buttons (Custom Icon Items)
		ItemStack west = GuiButtons.button(GuiButtons.NAV_WEST, Component.literal("§eตะวันตก (West)"), List.of());
		container.setItem(45, west);

		ItemStack north = GuiButtons.button(GuiButtons.NAV_NORTH, Component.literal("§eเหนือ (North)"), List.of());
		container.setItem(46, north);

		ItemStack south = GuiButtons.button(GuiButtons.NAV_SOUTH, Component.literal("§eใต้ (South)"), List.of());
		container.setItem(47, south);

		ItemStack east = GuiButtons.button(GuiButtons.NAV_EAST, Component.literal("§eตะวันออก (East)"), List.of());
		container.setItem(48, east);

		ItemStack center = GuiButtons.button(
				GuiButtons.NAV_CENTER,
				Component.literal("§6เลื่อนไปยังตำแหน่งของคุณ (" + playerCx + ", " + playerCz + ")"),
				List.of(Component.literal("§7คลิกเพื่อเลื่อนศูนย์กลางแผนที่มาที่ตัวคุณ"))
		);
		container.setItem(49, center);

		ItemStack claimHere = GuiButtons.button(
				GuiButtons.CLAIM,
				Component.literal("§aจองเขตที่ยืนอยู่ทันที"),
				List.of(Component.literal("§7คลิกเพื่อจับจองแปลงที่ดิน ณ พิกัดปัจจุบันของคุณ"))
		);
		container.setItem(50, claimHere);

		VirtualChestMenu.open(
				player,
				Component.literal("§0แผนที่เขต (ศูนย์กลาง: " + centerCx + "," + centerCz + ")"),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 45) { // West
						open(sp, regions, config, economy, centerCx - 1, centerCz);
					} else if (slotIndex == 46) { // North
						open(sp, regions, config, economy, centerCx, centerCz - 1);
					} else if (slotIndex == 47) { // South
						open(sp, regions, config, economy, centerCx, centerCz + 1);
					} else if (slotIndex == 48) { // East
						open(sp, regions, config, economy, centerCx + 1, centerCz);
					} else if (slotIndex == 49) { // Move center to player's current location
						int pcx = Math.floorDiv(sp.getBlockX(), config.cellSize);
						int pcz = Math.floorDiv(sp.getBlockZ(), config.cellSize);
						open(sp, regions, config, economy, pcx, pcz);
					} else if (slotIndex == 50) { // Claim current
						sp.closeContainer();
						var server = ClaimMap.getServer();
						if (server != null) {
							server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "claim");
						}
					} else {
						int r = slotIndex / 9;
						int c = slotIndex % 9;
						if (r < 5 && c >= 1 && c <= 7) {
							int cellX = centerCx + (c - 4);
							int cellZ = centerCz + (r - 2);
							String id = cellX + "," + cellZ;

							RegionManager.CellData cd = regions.get(id);
							if (cd != null) {
								if (cd.isOwner(myUuid) || sp.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
									openCellDetail(sp, cd, regions, config, economy, centerCx, centerCz);
								} else {
									sp.sendSystemMessage(Component.literal("§cเขตนี้เป็นของ " + cd.ownerName + " อยู่แล้ว"));
								}
							} else {
								int currentClaims = regions.countClaimedBy(myUuid);
								boolean isFree = currentClaims < config.freeCellsPerPlayer;
								if (isFree) {
									RegionManager.ClaimResult cr = regions.claim(cellX, cellZ, myUuid, sp.getGameProfile().name());
									if (cr == RegionManager.ClaimResult.OK) {
										sp.sendSystemMessage(Component.literal("§aรับกรรมสิทธิ์เขต " + id + " ฟรีสำเร็จ! (ฟรี "
												+ (currentClaims + 1) + "/" + config.freeCellsPerPlayer + " ช่อง)"));
									} else {
										sp.sendSystemMessage(Component.literal("§cจองเขตไม่สำเร็จ: " + cr));
									}
									open(sp, regions, config, economy, centerCx, centerCz);
								} else {
									long price = ClaimMap.instance() != null && ClaimMap.instance().landPrices() != null
											? ClaimMap.instance().landPrices().currentPrice()
											: 100_000L;
									long bal = economy.balance(sp.getUUID());
									if (bal < price) {
										sp.sendSystemMessage(Component.literal("§cเงินไม่พอซื้อที่ดิน ต้องการ " + price
												+ " " + config.currencyName + " (คุณมี " + bal + " " + config.currencyName + ")"));
										return;
									}
									TxResult tx = economy.withdraw(sp.getUUID(), price, "land.purchase:" + id, id);
									if (tx != TxResult.OK) {
										sp.sendSystemMessage(Component.literal("§cการหักเงินไม่สำเร็จ (" + tx + ")"));
										return;
									}
									RegionManager.ClaimResult cr = regions.claim(cellX, cellZ, myUuid, sp.getGameProfile().name());
									if (cr != RegionManager.ClaimResult.OK) {
										economy.deposit(sp.getUUID(), price, "land.refund:" + id, id);
										sp.sendSystemMessage(Component.literal("§cจองเขตไม่สำเร็จ: " + cr));
										return;
									}
									if (ClaimMap.instance() != null && ClaimMap.instance().landPrices() != null) {
										ClaimMap.instance().landPrices().onCellPurchased();
									}
									sp.sendSystemMessage(Component.literal("§aซื้อกรรมสิทธิ์เขต " + id + " สำเร็จ! จ่าย " + price + " " + config.currencyName));
									open(sp, regions, config, economy, centerCx, centerCz);
								}
							}
						}
					}
				},
				null
		);
	}

	public static void openCellDetail(ServerPlayer player, RegionManager.CellData cell,
									  RegionManager regions, ClaimMapConfig config,
									  EconomyManager economy, int returnCx, int returnCz) {
		SimpleContainer container = new SimpleContainer(27);

		boolean hasAuction = ClaimMap.instance() != null && ClaimMap.instance().auctions() != null
				&& ClaimMap.instance().auctions().hasActiveCellAuction(cell.id);

		ItemStack cellInfo = GuiButtons.button(
				GuiButtons.MAP,
				Component.literal("§6เขต " + cell.id),
				List.of(
						Component.literal("§eเจ้าของ: §f" + cell.ownerName),
						Component.literal("§eสมาชิก: §7" + (cell.members == null || cell.members.isEmpty() ? "ไม่มี" : cell.members.size() + " คน")),
						Component.literal("§ePvP: " + (cell.pvp ? "§aเปิด" : "§cปิด")),
						Component.literal("§7พิกัด: " + (cell.cx * config.cellSize) + ", " + (cell.cz * config.cellSize)),
						hasAuction ? Component.literal("§a[ประมูล] กำลังเปิดประมูลอยู่") : Component.literal("§7สถานะ: ปกติ")
				)
		);
		container.setItem(4, cellInfo);

		ItemStack abandon = GuiButtons.button(
				GuiButtons.ABANDON,
				Component.literal("§c[!] ปล่อยสิทธิ์เขตนี้"),
				hasAuction ? List.of(
						Component.literal("§cไม่สามารถปล่อยเขตได้เนื่องจากกำลังเปิดประมูลอยู่")
				) : List.of(
						Component.literal("§7ของในเขตจะไม่ถูกลบ แต่พื้นที่จะกลับเป็นที่ว่าง"),
						Component.literal("§cคลิกเพื่อปล่อยเขตทันที")
				)
		);
		container.setItem(11, abandon);

		boolean canTogglePvp = cell.isOwner(player.getUUID().toString())
				|| player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		ItemStack pvp = new ItemStack(cell.pvp ? Items.IRON_SWORD : Items.SHIELD);
		pvp.set(DataComponents.CUSTOM_NAME, Component.literal(cell.pvp ? "§a[PvP] กำลังเปิดในเขตนี้" : "§c[PvP] กำลังปิดในเขตนี้"));
		pvp.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("§7เจ้าของเขตเลือกได้ว่าจะให้ผู้เล่น"),
				Component.literal("§7ต่อสู้กันในเขตนี้หรือไม่ (ทุกเขตใช้กฎนี้)"),
				Component.literal("§7สถานะ: " + (cell.pvp ? "§aเปิด (ตีกันได้)" : "§cปิด (ตีกันไม่ได้)")),
				canTogglePvp ? Component.literal("§eคลิกเพื่อ" + (cell.pvp ? "ปิด" : "เปิด") + " PvP")
						: Component.literal("§8เฉพาะเจ้าของเขตหรือแอดมินเท่านั้น")
		)));
		container.setItem(12, pvp);

		ItemStack warp = GuiButtons.button(
				GuiButtons.WARP,
				Component.literal("§b[วาร์ป] เทเลพอร์ตไปยังเขตนี้"),
				List.of(
						Component.literal("§7สุ่มตำแหน่ง X, Z ในเขตอย่างปลอดภัย"),
						Component.literal("§7คำนวณความสูง Y อัตโนมัติไม่ให้ติดบล็อก"),
						Component.literal("§bคลิกเพื่อวาร์ปทันที")
				)
		);
		container.setItem(13, warp);

		ItemStack auction = GuiButtons.button(
				GuiButtons.GAVEL,
				Component.literal("§e[ประมูล] เปิดประมูลเขตนี้"),
				hasAuction ? List.of(
						Component.literal("§aเขตนี้กำลังเปิดประมูลอยู่แล้วในตลาด /ah"),
						Component.literal("§7คุณสามารถตรวจสอบสถานะได้ที่คำสั่ง /ah")
				) : List.of(
						Component.literal("§7คลิกเพื่อเปิดประมูลเขตนี้ (ระยะเวลา 24 ชม.)"),
						Component.literal("§7หรือกำหนดราคาเองด้วยคำสั่ง:"),
						Component.literal("§e/claim auction <ราคาเริ่ม> [ชั่วโมง]")
				)
		);
		container.setItem(15, auction);

		ItemStack back = GuiButtons.button(
				GuiButtons.BACK,
				Component.literal("§e< กลับไปยังแผนที่"),
				List.of()
		);
		container.setItem(22, back);

		VirtualChestMenu.open(
				player,
				Component.literal("§0จัดการเขต " + cell.id),
				3,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (slotIndex == 11) { // Abandon
						if (ClaimMap.instance() != null && ClaimMap.instance().auctions() != null
								&& ClaimMap.instance().auctions().hasActiveCellAuction(cell.id)) {
							sp.sendSystemMessage(Component.literal("§cไม่สามารถปล่อยเขตนี้ได้เนื่องจากกำลังเปิดประมูลอยู่"));
							return;
						}
						boolean ok = regions.unclaim(cell.id, sp.getUUID().toString(), sp.permissions().hasPermission(Permissions.COMMANDS_ADMIN));
						if (ok) {
							sp.sendSystemMessage(Component.literal("§aปล่อยกรรมสิทธิ์เขต " + cell.id + " เรียบร้อยแล้ว"));
						} else {
							sp.sendSystemMessage(Component.literal("§cไม่สามารถปล่อยเขตนี้ได้"));
						}
						open(sp, regions, config, economy, returnCx, returnCz);
					} else if (slotIndex == 12) { // PvP toggle
						boolean admin = sp.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
						boolean enable = !cell.pvp;
						if (regions.setPvp(cell.id, sp.getUUID().toString(), enable, admin)) {
							sp.sendSystemMessage(Component.literal(enable
									? "§aเปิด PvP ในเขต " + cell.id + " แล้ว"
									: "§cปิด PvP ในเขต " + cell.id + " แล้ว"));
						} else {
							sp.sendSystemMessage(Component.literal("§cเฉพาะเจ้าของเขตหรือแอดมินเท่านั้นที่เปลี่ยน PvP ได้"));
						}
						openCellDetail(sp, cell, regions, config, economy, returnCx, returnCz);
					} else if (slotIndex == 13) { // Warp
						sp.closeContainer();
						ClaimCommands.teleportToCell(sp, cell.cx, cell.cz, config);
					} else if (slotIndex == 15) { // Auction
						if (ClaimMap.instance() != null && ClaimMap.instance().auctions() != null) {
							var am = ClaimMap.instance().auctions();
							if (am.hasActiveCellAuction(cell.id)) {
								sp.sendSystemMessage(Component.literal("§cเขตนี้กำลังอยู่ระหว่างการประมูล"));
								return;
							}
							long defaultStart = 50_000L;
							if (ClaimMap.instance().landPrices() != null) {
								defaultStart = Math.max(10_000L, ClaimMap.instance().landPrices().currentPrice() / 2);
							}
							var outcome = am.createCellAuction(cell.id, sp.getUUID().toString(), sp.getGameProfile().name(), defaultStart, null, 24);
							if (outcome.result() == AuctionManager.Result.OK) {
								sp.sendSystemMessage(Component.literal("§aเปิดประมูลเขต " + cell.id + " สำเร็จ! (รหัส " + outcome.auctionId()
										+ " ราคาเริ่ม " + defaultStart + " " + config.currencyName + " ระยะเวลา 24 ชม.)"));
								sp.sendSystemMessage(Component.literal("§7คุณสามารถกำหนดราคาเองได้ด้วย: §e/claim auction <ราคาเริ่ม> [ชั่วโมง]"));
								sp.closeContainer();
							} else {
								sp.sendSystemMessage(Component.literal("§cเปิดประมูลไม่สำเร็จ: " + outcome.result()));
							}
						}
					} else if (slotIndex == 22) { // Back
						open(sp, regions, config, economy, returnCx, returnCz);
					}
				},
				null
		);
	}
}
