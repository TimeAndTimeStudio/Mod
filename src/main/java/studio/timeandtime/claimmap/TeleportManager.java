package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player-to-player teleport requests (/tpa), teleport warmups (5 seconds),
 * and direct admin teleports (/tp).
 * Requests expire after 10 minutes (600 seconds).
 */
public class TeleportManager {
	public static final long TIMEOUT_MS = 600_000L;
	public static final int WARMUP_SECONDS = 5;
	public static final int WARMUP_TICKS = WARMUP_SECONDS * 20;

	public record Request(UUID senderUuid, String senderName, UUID targetUuid, String targetName, long createdAt) {
		public boolean isExpired(long now) {
			return now - createdAt > TIMEOUT_MS;
		}
	}

	public static class WarmupTask {
		public final UUID playerUuid;
		public final Vec3 startPos;
		public final ResourceKey<Level> startDimension;
		public final Component destinationName;
		public final Runnable teleportAction;
		public final Runnable onCancel;
		public int remainingTicks;

		public WarmupTask(UUID playerUuid, Vec3 startPos, ResourceKey<Level> startDimension,
				Component destinationName, Runnable teleportAction, Runnable onCancel) {
			this.playerUuid = playerUuid;
			this.startPos = startPos;
			this.startDimension = startDimension;
			this.destinationName = destinationName;
			this.teleportAction = teleportAction;
			this.onCancel = onCancel;
			this.remainingTicks = WARMUP_TICKS;
		}
	}

	// Key: target UUID -> (sender UUID -> Request)
	private final Map<UUID, Map<UUID, Request>> incoming = new ConcurrentHashMap<>();
	// Key: player UUID -> active warmup
	private final Map<UUID, WarmupTask> activeWarmups = new ConcurrentHashMap<>();

	public void startWarmup(ServerPlayer player, Component destinationName, Runnable teleportAction) {
		startWarmup(player, destinationName, teleportAction, null);
	}

	public void startWarmup(ServerPlayer player, Component destinationName, Runnable teleportAction, Runnable onCancel) {
		UUID uuid = player.getUUID();
		if (activeWarmups.containsKey(uuid)) {
			player.sendSystemMessage(Component.literal("§c[Teleport] คุณกำลังอยู่ในระหว่างการเตรียมวาร์ปอยู่แล้ว"));
			return;
		}
		WarmupTask task = new WarmupTask(uuid, player.position(), player.level().dimension(), destinationName, teleportAction, onCancel);
		activeWarmups.put(uuid, task);
		player.sendSystemMessage(Component.literal("§6[Teleport] §eกำลังเตรียมวาร์ปไป §f" + destinationName.getString()
				+ " §eในอีก §c" + WARMUP_SECONDS + " §eวินาที... §cห้ามขยับตัว"));
	}

	public void cancelWarmup(UUID uuid, String reason) {
		WarmupTask task = activeWarmups.remove(uuid);
		if (task != null) {
			MinecraftServer srv = ClaimMap.getServer();
			if (srv != null) {
				ServerPlayer player = srv.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.sendSystemMessage(Component.literal("§c[Teleport] " + reason));
				}
			}
			if (task.onCancel != null) {
				task.onCancel.run();
			}
		}
	}

	public void tick(MinecraftServer server) {
		if (activeWarmups.isEmpty()) return;
		for (Map.Entry<UUID, WarmupTask> entry : activeWarmups.entrySet()) {
			UUID uuid = entry.getKey();
			WarmupTask task = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null || !player.isAlive()) {
				activeWarmups.remove(uuid);
				if (task.onCancel != null) task.onCancel.run();
				continue;
			}
			if (!player.level().dimension().equals(task.startDimension)
					|| player.position().distanceToSqr(task.startPos) > 0.5) {
				activeWarmups.remove(uuid);
				ActionBar.send(player, "§c[Teleport] การวาร์ปถูกยกเลิกเนื่องจากคุณขยับตัว!");
				if (task.onCancel != null) task.onCancel.run();
				continue;
			}
			task.remainingTicks--;
			if (task.remainingTicks <= 0) {
				activeWarmups.remove(uuid);
				ActionBar.send(player, "§a[Teleport] วาร์ปสำเร็จ!");
				try {
					task.teleportAction.run();
				} catch (Exception e) {
					player.sendSystemMessage(Component.literal("§c[Teleport] เกิดข้อผิดพลาดในการวาร์ป: " + e.getMessage()));
				}
			} else if (task.remainingTicks % 20 == 0) {
				int sec = task.remainingTicks / 20;
				ActionBar.send(player, "§e[Teleport] กำลังวาร์ปในอีก §c" + sec + " §eวินาที... §7(ห้ามขยับตัว)");
			}
		}
	}

	public void request(ServerPlayer sender, ServerPlayer target) {
		if (sender.getUUID().equals(target.getUUID())) {
			sender.sendSystemMessage(Component.literal("§c[Teleport] ไม่สามารถส่งคำขอวาร์ปหาตัวเองได้"));
			return;
		}

		long now = System.currentTimeMillis();
		Request req = new Request(sender.getUUID(), sender.getScoreboardName(), target.getUUID(), target.getScoreboardName(), now);
		incoming.computeIfAbsent(target.getUUID(), k -> new ConcurrentHashMap<>()).put(sender.getUUID(), req);

		sender.sendSystemMessage(Component.literal("§6[Teleport] §eส่งคำขอวาร์ปไปยัง §f" + target.getScoreboardName() + " §eแล้ว (มีอายุ 10 นาที)"));
		target.sendSystemMessage(Component.literal("§6[Teleport] §f" + sender.getScoreboardName() + " §eต้องการวาร์ปมาหาคุณ\n§7พิมพ์ §a/tpa §7เพื่อเปิดเมนูตอบรับ (คลิกซ้ายยอมรับ / คลิกขวาปฏิเสธ)"));
	}

	public List<Request> getIncomingRequests(UUID targetUuid) {
		Map<UUID, Request> map = incoming.get(targetUuid);
		if (map == null || map.isEmpty()) return Collections.emptyList();
		long now = System.currentTimeMillis();
		map.values().removeIf(r -> r.isExpired(now));
		return new ArrayList<>(map.values());
	}

	public Request getOutgoingRequest(UUID senderUuid) {
		long now = System.currentTimeMillis();
		for (Map<UUID, Request> map : incoming.values()) {
			Request r = map.get(senderUuid);
			if (r != null) {
				if (r.isExpired(now)) {
					map.remove(senderUuid);
				} else {
					return r;
				}
			}
		}
		return null;
	}

	public boolean hasPending(UUID targetUuid) {
		return !getIncomingRequests(targetUuid).isEmpty();
	}

	public void accept(ServerPlayer target, String specificSender) {
		long now = System.currentTimeMillis();
		Map<UUID, Request> map = incoming.get(target.getUUID());
		if (map == null || map.isEmpty()) {
			target.sendSystemMessage(Component.literal("§c[Teleport] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

		Request req = null;
		if (specificSender != null && !specificSender.isBlank()) {
			String name = specificSender.trim();
			for (Request r : map.values()) {
				if (r.senderName().equalsIgnoreCase(name)) {
					req = r;
					break;
				}
			}
			if (req == null || req.isExpired(now)) {
				if (req != null) map.remove(req.senderUuid());
				target.sendSystemMessage(Component.literal("§c[Teleport] ไม่พบคำขอวาร์ปจากผู้เล่น " + specificSender));
				return;
			}
		} else {
			for (Request r : map.values()) {
				if (!r.isExpired(now) && (req == null || r.createdAt() > req.createdAt())) {
					req = r;
				}
			}
			if (req == null) {
				map.clear();
				target.sendSystemMessage(Component.literal("§c[Teleport] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
				return;
			}
		}

		map.remove(req.senderUuid());
		var srv = ClaimMap.getServer();
		if (srv == null) {
			target.sendSystemMessage(Component.literal("§c[Teleport] เซิร์ฟเวอร์ยังไม่พร้อม"));
			return;
		}
		ServerPlayer sender = srv.getPlayerList().getPlayer(req.senderUuid());
		if (sender == null) {
			target.sendSystemMessage(Component.literal("§c[Teleport] ผู้เล่น " + req.senderName() + " ออฟไลน์ไปแล้ว"));
			return;
		}

		final Request chosenReq = req;
		target.sendSystemMessage(Component.literal("§6[Teleport] §aยอมรับคำขอวาร์ปจาก §f" + sender.getScoreboardName() + " §aแล้ว (ผู้เล่นกำลังเตรียมวาร์ป 5 วินาที)"));
		startWarmup(sender, Component.literal("หา " + target.getScoreboardName()), () -> {
			var currentSrv = ClaimMap.getServer();
			if (currentSrv == null) return;
			ServerPlayer currentTarget = currentSrv.getPlayerList().getPlayer(chosenReq.targetUuid());
			if (currentTarget == null || !currentTarget.isAlive()) {
				sender.sendSystemMessage(Component.literal("§c[Teleport] ผู้เล่น " + chosenReq.targetName() + " ออฟไลน์หรือไม่พร้อม"));
				return;
			}
			doTeleport(sender, currentTarget);
			currentTarget.sendSystemMessage(Component.literal("§6[Teleport] §a" + sender.getScoreboardName() + " วาร์ปมาหาคุณแล้ว"));
			sender.sendSystemMessage(Component.literal("§6[Teleport] §aวาร์ปไปยัง §f" + currentTarget.getScoreboardName() + " §aสำเร็จ"));
		}, () -> {
			var currentSrv = ClaimMap.getServer();
			if (currentSrv != null) {
				ServerPlayer currentTarget = currentSrv.getPlayerList().getPlayer(chosenReq.targetUuid());
				if (currentTarget != null) {
					currentTarget.sendSystemMessage(Component.literal("§6[Teleport] §cการวาร์ปของ " + chosenReq.senderName() + " ถูกยกเลิก"));
				}
			}
		});
	}

	public void deny(ServerPlayer target, String specificSender) {
		long now = System.currentTimeMillis();
		Map<UUID, Request> map = incoming.get(target.getUUID());
		if (map == null || map.isEmpty()) {
			target.sendSystemMessage(Component.literal("§c[Teleport] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

		Request req = null;
		if (specificSender != null && !specificSender.isBlank()) {
			String name = specificSender.trim();
			for (Request r : map.values()) {
				if (r.senderName().equalsIgnoreCase(name)) {
					req = r;
					break;
				}
			}
		} else {
			for (Request r : map.values()) {
				if (!r.isExpired(now) && (req == null || r.createdAt() > req.createdAt())) {
					req = r;
				}
			}
		}

		if (req == null || req.isExpired(now)) {
			if (req != null) map.remove(req.senderUuid());
			target.sendSystemMessage(Component.literal("§c[Teleport] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

		map.remove(req.senderUuid());
		target.sendSystemMessage(Component.literal("§6[Teleport] §eปฏิเสธคำขอวาร์ปจาก §f" + req.senderName() + " §eแล้ว"));
		var srv = ClaimMap.getServer();
		if (srv != null) {
			ServerPlayer sender = srv.getPlayerList().getPlayer(req.senderUuid());
			if (sender != null) {
				sender.sendSystemMessage(Component.literal("§6[Teleport] §c" + target.getScoreboardName() + " §cปฏิเสธคำขอวาร์ปของคุณ"));
			}
		}
	}

	public void cancel(ServerPlayer sender) {
		UUID sUuid = sender.getUUID();
		Request found = null;
		for (Map<UUID, Request> map : incoming.values()) {
			Request r = map.remove(sUuid);
			if (r != null) {
				found = r;
				break;
			}
		}

		if (found != null) {
			sender.sendSystemMessage(Component.literal("§6[Teleport] §eยกเลิกคำขอวาร์ปไปยัง §f" + found.targetName() + " §eแล้ว"));
		} else {
			sender.sendSystemMessage(Component.literal("§c[Teleport] คุณไม่มีคำขอวาร์ปที่ค้างอยู่"));
		}
	}

	public void teleportDirect(ServerPlayer sender, ServerPlayer target) {
		if (sender.getUUID().equals(target.getUUID())) {
			sender.sendSystemMessage(Component.literal("§c[TP] คุณอยู่ที่ตำแหน่งนี้อยู่แล้ว"));
			return;
		}
		doTeleport(sender, target);
		sender.sendSystemMessage(Component.literal("§6[TP] §aวาร์ปไปยัง §f" + target.getScoreboardName() + " §aสำเร็จ"));
	}

	private void doTeleport(ServerPlayer sender, ServerPlayer target) {
		ServerLevel destLevel = (ServerLevel) target.level();
		Vec3 pos = target.position();
		TeleportTransition transition = new TeleportTransition(destLevel, pos, Vec3.ZERO,
				target.getYRot(), target.getXRot(), Set.of(), TeleportTransition.DO_NOTHING);
		sender.teleport(transition);
	}
}
