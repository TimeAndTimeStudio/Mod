package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player-to-player teleport requests (/tpa), teleport warmups (5 seconds),
 * and direct admin teleports (/tp).
 * Requests expire after 60 seconds.
 */
public class TeleportManager {
	public static final long TIMEOUT_MS = 60_000L;
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

	// Key: target UUID -> active request
	private final Map<UUID, Request> pending = new ConcurrentHashMap<>();
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
		player.displayClientMessage(Component.literal("§eกำลังเตรียมวาร์ปในอีก §c" + WARMUP_SECONDS + " §eวินาที... §7(ห้ามขยับตัว)"), true);
	}

	public void cancelWarmup(UUID uuid, String reason) {
		WarmupTask task = activeWarmups.remove(uuid);
		if (task != null) {
			MinecraftServer srv = ClaimMap.getServer();
			if (srv != null) {
				ServerPlayer player = srv.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.displayClientMessage(Component.literal("§c" + reason), true);
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
				player.displayClientMessage(Component.literal("§cการวาร์ปถูกยกเลิกเนื่องจากคุณขยับตัว!"), true);
				player.sendSystemMessage(Component.literal("§c[Teleport] การวาร์ปถูกยกเลิกเนื่องจากคุณขยับตัว!"));
				if (task.onCancel != null) task.onCancel.run();
				continue;
			}
			task.remainingTicks--;
			if (task.remainingTicks <= 0) {
				activeWarmups.remove(uuid);
				player.displayClientMessage(Component.literal("§aวาร์ปสำเร็จ!"), true);
				try {
					task.teleportAction.run();
				} catch (Exception e) {
					player.sendSystemMessage(Component.literal("§c[Teleport] เกิดข้อผิดพลาดในการวาร์ป: " + e.getMessage()));
				}
			} else if (task.remainingTicks % 20 == 0) {
				int sec = task.remainingTicks / 20;
				player.displayClientMessage(Component.literal("§eกำลังวาร์ปในอีก §c" + sec + " §eวินาที... §7(ห้ามขยับตัว)"), true);
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
		pending.put(target.getUUID(), req);

		sender.sendSystemMessage(Component.literal("§6[Teleport] §eส่งคำขอวาร์ปไปยัง §f" + target.getScoreboardName() + " §eแล้ว (มีอายุ 60 วินาที)"));
		target.sendSystemMessage(Component.literal("§6[Teleport] §f" + sender.getScoreboardName() + " §eต้องการวาร์ปมาหาคุณ\n§7พิมพ์ §a/tpa §7เพื่อยอมรับ (§c/tpa deny §7เพื่อปฏิเสธ)"));
	}

	public boolean hasPending(UUID targetUuid) {
		Request req = pending.get(targetUuid);
		return req != null && !req.isExpired(System.currentTimeMillis());
	}

	public void accept(ServerPlayer target, String specificSender) {
		long now = System.currentTimeMillis();
		Request req = pending.get(target.getUUID());
		if (req == null || req.isExpired(now)) {
			pending.remove(target.getUUID());
			target.sendSystemMessage(Component.literal("§c[Teleport] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

		if (specificSender != null && !specificSender.isBlank() && !req.senderName().equalsIgnoreCase(specificSender.trim())) {
			target.sendSystemMessage(Component.literal("§c[Teleport] ไม่พบคำขอวาร์ปจากผู้เล่น " + specificSender));
			return;
		}

		pending.remove(target.getUUID());
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

		target.sendSystemMessage(Component.literal("§6[Teleport] §aยอมรับคำขอวาร์ปจาก §f" + sender.getScoreboardName() + " §aแล้ว (ผู้เล่นกำลังเตรียมวาร์ป 5 วินาที)"));
		startWarmup(sender, Component.literal("หา " + target.getScoreboardName()), () -> {
			var currentSrv = ClaimMap.getServer();
			if (currentSrv == null) return;
			ServerPlayer currentTarget = currentSrv.getPlayerList().getPlayer(req.targetUuid());
			if (currentTarget == null || !currentTarget.isAlive()) {
				sender.sendSystemMessage(Component.literal("§c[Teleport] ผู้เล่น " + req.targetName() + " ออฟไลน์หรือไม่พร้อม"));
				return;
			}
			doTeleport(sender, currentTarget);
			currentTarget.sendSystemMessage(Component.literal("§6[Teleport] §a" + sender.getScoreboardName() + " วาร์ปมาหาคุณแล้ว"));
			sender.sendSystemMessage(Component.literal("§6[Teleport] §aวาร์ปไปยัง §f" + currentTarget.getScoreboardName() + " §aสำเร็จ"));
		}, () -> {
			var currentSrv = ClaimMap.getServer();
			if (currentSrv != null) {
				ServerPlayer currentTarget = currentSrv.getPlayerList().getPlayer(req.targetUuid());
				if (currentTarget != null) {
					currentTarget.sendSystemMessage(Component.literal("§6[Teleport] §cการวาร์ปของ " + req.senderName() + " ถูกยกเลิก"));
				}
			}
		});
	}

	public void deny(ServerPlayer target, String specificSender) {
		long now = System.currentTimeMillis();
		Request req = pending.remove(target.getUUID());
		if (req == null || req.isExpired(now)) {
			target.sendSystemMessage(Component.literal("§c[Teleport] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

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
		Optional<UUID> targetKey = pending.entrySet().stream()
				.filter(e -> e.getValue().senderUuid().equals(sUuid))
				.map(Map.Entry::getKey)
				.findFirst();

		if (targetKey.isPresent()) {
			Request req = pending.remove(targetKey.get());
			sender.sendSystemMessage(Component.literal("§6[Teleport] §eยกเลิกคำขอวาร์ปไปยัง §f" + req.targetName() + " §eแล้ว"));
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
