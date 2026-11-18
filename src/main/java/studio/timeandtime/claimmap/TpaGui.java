package studio.timeandtime.claimmap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.*;

public final class TpaGui {
	private TpaGui() {}

	public static void open(ServerPlayer player, TeleportManager teleports) {
		SimpleContainer container = new SimpleContainer(54);
		var srv = ClaimMap.getServer();
		List<TeleportManager.Request> incomingReqs = teleports != null ? teleports.getIncomingRequests(player.getUUID()) : Collections.emptyList();
		TeleportManager.Request outgoingReq = teleports != null ? teleports.getOutgoingRequest(player.getUUID()) : null;

		// Slot 4: Brand Header
		ItemStack header = GuiButtons.button(
				GuiButtons.BRAND,
				Component.literal("§6§lระบบเทเลพอร์ต §e(Teleport Menu)"),
				List.of(
						Component.literal("§7จัดการคำขอวาร์ปและเลือกผู้เล่นที่ต้องการวาร์ปไปหา"),
						Component.literal("§7--------------------------------"),
						Component.literal("§a[คลิกซ้ายที่คำขอ] §7ยอมรับคำขอ (รอ 5 วิ)"),
						Component.literal("§c[คลิกขวาที่คำขอ] §7ปฏิเสธ / ยกเลิกคำขอ"),
						Component.literal("§b[คลิกที่ผู้เล่น] §7ส่งคำขอวาร์ปไปหาผู้เล่นนั้น")
				)
		);
		container.setItem(4, header);

		// Map slots to click actions
		Map<Integer, Runnable> leftClicks = new HashMap<>();
		Map<Integer, Runnable> rightClicks = new HashMap<>();

		// Row 2 (Slots 10-16): Incoming Requests
		int[] reqSlots = new int[]{10, 11, 12, 13, 14, 15, 16};
		if (incomingReqs.isEmpty()) {
			ItemStack emptyReq = GuiButtons.button(
					GuiButtons.HELP,
					Component.literal("§7ไม่มีคำขอวาร์ปที่ค้างอยู่"),
					List.of(
							Component.literal("§7เมื่อมีผู้เล่นส่งคำขอวาร์ปมาหาคุณ"),
							Component.literal("§7รายการคำขอจะแสดงที่นี่แยกตามชื่อผู้เล่น"),
							Component.literal("§7--------------------------------"),
							Component.literal("§aคลิกซ้าย = ยอมรับ §7| §cคลิกขวา = ยกเลิก")
					)
			);
			container.setItem(13, emptyReq);
		} else {
			for (int i = 0; i < incomingReqs.size() && i < reqSlots.length; i++) {
				int slot = reqSlots[i];
				TeleportManager.Request req = incomingReqs.get(i);
				long remainingSec = Math.max(0, (TeleportManager.TIMEOUT_MS - (System.currentTimeMillis() - req.createdAt())) / 1000L);
				String timeStr = remainingSec >= 60 ? (remainingSec / 60) + " นาที " + (remainingSec % 60) + " วินาที" : remainingSec + " วินาที";

				ItemStack reqStack = new ItemStack(Items.PLAYER_HEAD);
				var srvSender = srv != null ? srv.getPlayerList().getPlayer(req.senderUuid()) : null;
				if (srvSender != null) {
					reqStack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(srvSender.getGameProfile()));
				} else {
					reqStack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(req.senderName()));
				}
				reqStack.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lคำขอจาก: §6" + req.senderName()));
				reqStack.set(DataComponents.LORE, new ItemLore(List.of(
						Component.literal("§7ผู้เล่นต้องการวาร์ปมายังตำแหน่งของคุณ"),
						Component.literal("§7เวลาคงเหลือ: §e" + timeStr),
						Component.literal("§7--------------------------------"),
						Component.literal("§a[คลิกซ้าย] §7ยอมรับคำขอ (ผู้เล่นจะวาร์ปมาใน 5 วิ)"),
						Component.literal("§c[คลิกขวา] §7ยกเลิก / ปฏิเสธคำขอนี้")
				)));
				container.setItem(slot, reqStack);

				leftClicks.put(slot, () -> {
					player.closeContainer();
					teleports.accept(player, req.senderName());
				});
				rightClicks.put(slot, () -> {
					teleports.deny(player, req.senderName());
					open(player, teleports);
				});
			}
		}

		// Row 3 to 5 (Slots 19-25, 28-34, 37-43): Online Players List
		int[] playerSlots = new int[]{
				19, 20, 21, 22, 23, 24, 25,
				28, 29, 30, 31, 32, 33, 34,
				37, 38, 39, 40, 41, 42, 43
		};

		List<ServerPlayer> otherPlayers = new ArrayList<>();
		if (srv != null) {
			for (ServerPlayer sp : srv.getPlayerList().getPlayers()) {
				if (!sp.getUUID().equals(player.getUUID())) {
					otherPlayers.add(sp);
				}
			}
		}

		if (otherPlayers.isEmpty()) {
			ItemStack noPlayers = GuiButtons.button(
					GuiButtons.HELP,
					Component.literal("§cไม่มีผู้เล่นอื่นออนไลน์"),
					List.of(
							Component.literal("§7ขณะนี้ไม่มีผู้เล่นคนอื่นออนไลน์อยู่ในเซิร์ฟเวอร์")
					)
			);
			container.setItem(31, noPlayers);
		} else {
			for (int i = 0; i < otherPlayers.size() && i < playerSlots.length; i++) {
				int slot = playerSlots[i];
				ServerPlayer target = otherPlayers.get(i);
				String worldName = target.level().dimension().identifier().toString().replace("minecraft:", "");

				ItemStack targetStack = new ItemStack(Items.PLAYER_HEAD);
				targetStack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(target.getGameProfile()));
				targetStack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lผู้เล่น: §f" + target.getScoreboardName()));
				targetStack.set(DataComponents.LORE, new ItemLore(List.of(
						Component.literal("§7โลกปัจจุบัน: §e" + worldName),
						Component.literal("§7--------------------------------"),
						Component.literal("§b[คลิกซ้าย] §7ส่งคำขอวาร์ปไปหาผู้เล่นนี้"),
						Component.literal("§7(คำสั่ง: /tpa " + target.getScoreboardName() + ")")
				)));
				container.setItem(slot, targetStack);

				leftClicks.put(slot, () -> {
					player.closeContainer();
					teleports.request(player, target);
				});
			}
		}

		// Slot 49: Outgoing request status or Help Tip
		if (outgoingReq != null) {
			ItemStack outStack = GuiButtons.button(
					GuiButtons.ABANDON,
					Component.literal("§6คำขอที่คุณส่งไปหา: §e" + outgoingReq.targetName()),
					List.of(
							Component.literal("§7กำลังรอผู้เล่นปลายทางกดยอมรับ..."),
							Component.literal("§7--------------------------------"),
							Component.literal("§c[คลิกซ้าย หรือคลิกขวา] §7ยกเลิกคำขอนี้")
					)
			);
			container.setItem(49, outStack);
			Runnable cancelAction = () -> {
				teleports.cancel(player);
				open(player, teleports);
			};
			leftClicks.put(49, cancelAction);
			rightClicks.put(49, cancelAction);
		}

		VirtualChestMenu.open(
				player,
				Component.literal("§0ระบบเทเลพอร์ต (TPA)"),
				6,
				true,
				container,
				(slotIndex, button, input, sp) -> {
					if (button == 0) { // Left-click
						Runnable action = leftClicks.get(slotIndex);
						if (action != null) action.run();
					} else if (button == 1) { // Right-click
						Runnable action = rightClicks.get(slotIndex);
						if (action != null) action.run();
					}
				},
				null
		);
	}
}
