package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.text.SimpleDateFormat;
import java.util.*;

public final class HomeGui {
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

	private HomeGui() {}

	public static void open(ServerPlayer player, HomeManager homeManager, TeleportManager teleports) {
		Map<String, HomeManager.HomeEntry> homes = homeManager != null ? homeManager.getHomes(player.getUUID()) : Collections.emptyMap();
		SimpleContainer container = new SimpleContainer(54);

		// Header Banner (Slot 4)
		ItemStack banner = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal("§6§lระบบจุดเซฟบ้าน §e(Homes)"),
				List.of(
						Component.literal("§7จัดการจุดเซฟบ้านและวาร์ปได้อย่างสะดวก"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eจำนวนบ้านที่เซฟไว้: §f" + homes.size() + "§7/" + HomeManager.MAX_HOMES),
						Component.literal("§fพิมพ์ §a/sethome <ชื่อ> §fเพื่อบันทึกบ้าน ณ จุดที่ยืน"),
						Component.literal("§fพิมพ์ §c/delhome <ชื่อ> §fเพื่อลบบ้าน"),
						Component.literal("§7--------------------------------"),
						Component.literal("§eกดปุ่ม Esc เพื่อปิดหน้าต่าง")
				)
		);
		container.setItem(4, banner);

		if (homes.isEmpty()) {
			ItemStack emptyItem = GuiButtons.button(
					GuiButtons.HELP,
					Component.literal("§cยังไม่มีจุดเซฟบ้าน"),
					List.of(
							Component.literal("§7คุณยังไม่ได้บันทึกจุดเซฟบ้านใดๆ"),
							Component.literal("§7--------------------------------"),
							Component.literal("§eวิธีบันทึกบ้าน:"),
							Component.literal("§f1. เดินไปยังตำแหน่งที่คุณต้องการสร้างบ้าน"),
							Component.literal("§f2. พิมพ์คำสั่ง §a/sethome <ชื่อบ้าน>"),
							Component.literal("§f3. กดเปิดหน้านี้อีกครั้งด้วย §b/home")
					)
			);
			container.setItem(22, emptyItem);
		} else {
			// Slots to populate: rows 2 to 5 (slots 10-16, 19-25, 28-34, 37-43)
			int[] slots = new int[]{
					10, 11, 12, 13, 14, 15, 16,
					19, 20, 21, 22, 23, 24, 25,
					28, 29, 30, 31, 32, 33, 34,
					37, 38, 39, 40, 41, 42, 43
			};

			List<HomeManager.HomeEntry> list = new ArrayList<>(homes.values());
			Map<Integer, HomeManager.HomeEntry> slotMap = new HashMap<>();

			for (int i = 0; i < list.size() && i < slots.length; i++) {
				int slot = slots[i];
				HomeManager.HomeEntry h = list.get(i);
				slotMap.put(slot, h);

				String friendlyWorld = h.world.replace("minecraft:", "");
				String timeStr = h.createdAt > 0 ? DATE_FORMAT.format(new Date(h.createdAt)) : "-";

				ItemStack item = GuiButtons.button(
						GuiButtons.WARP,
						Component.literal("§e§lบ้าน: §6" + h.name),
						List.of(
								Component.literal("§7โลก: §f" + friendlyWorld),
								Component.literal("§7พิกัด: §fX: " + (int)h.x + ", Y: " + (int)h.y + ", Z: " + (int)h.z),
								Component.literal("§7บันทึกเมื่อ: §8" + timeStr),
								Component.literal("§7--------------------------------"),
								Component.literal("§a[คลิกซ้าย] §7วาร์ปไปบ้านนี้ §e(รอ 5 วินาที)"),
								Component.literal("§c[คลิกขวา] §7ลบจุดเซฟบ้านนี้")
						)
				);
				container.setItem(slot, item);
			}

			VirtualChestMenu.open(
					player,
					Component.literal("§0จุดเซฟบ้านของคุณ (Homes)"),
					6,
					true,
					container,
					(slotIndex, button, input, sp) -> {
						HomeManager.HomeEntry clickedHome = slotMap.get(slotIndex);
						if (clickedHome != null) {
							if (button == 0) { // Left click: teleport
								sp.closeContainer();
								if (teleports != null) {
									teleports.startWarmup(sp, Component.literal("บ้าน " + clickedHome.name),
											() -> homeManager.teleport(sp, clickedHome));
								} else {
									homeManager.teleport(sp, clickedHome);
								}
							} else if (button == 1) { // Right click: delete
								homeManager.deleteHome(sp.getUUID(), clickedHome.name);
								sp.sendSystemMessage(Component.literal("§6[Home] §eลบจุดเซฟบ้าน §f" + clickedHome.name + " §eเรียบร้อยแล้ว"));
								open(sp, homeManager, teleports);
							}
						}
					},
					null
			);
			return;
		}

		VirtualChestMenu.open(
				player,
				Component.literal("§0จุดเซฟบ้านของคุณ (Homes)"),
				6,
				true,
				container,
				null,
				null
		);
	}
}
