package studio.timeandtime.claimmap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player-to-player teleport requests (/tpa) and direct admin teleports (/tp).
 * Requests expire after 60 seconds.
 */
public class TeleportManager {
	public static final long TIMEOUT_MS = 60_000L;

	public record Request(UUID senderUuid, String senderName, UUID targetUuid, String targetName, long createdAt) {
		public boolean isExpired(long now) {
			return now - createdAt > TIMEOUT_MS;
		}
	}

	// Key: target UUID -> active request
	private final Map<UUID, Request> pending = new ConcurrentHashMap<>();

	public void request(ServerPlayer sender, ServerPlayer target) {
		if (sender.getUUID().equals(target.getUUID())) {
			sender.sendSystemMessage(Component.literal("§c[TPA] ไม่สามารถส่งคำขอวาร์ปหาตัวเองได้"));
			return;
		}

		long now = System.currentTimeMillis();
		Request req = new Request(sender.getUUID(), sender.getScoreboardName(), target.getUUID(), target.getScoreboardName(), now);
		pending.put(target.getUUID(), req);

		sender.sendSystemMessage(Component.literal("§6[TPA] §eส่งคำขอวาร์ปไปยัง §f" + target.getScoreboardName() + " §eแล้ว (มีอายุ 60 วินาที)"));
		target.sendSystemMessage(Component.literal("§6[TPA] §f" + sender.getScoreboardName() + " §eส่งคำขอวาร์ปมาหาคุณ\n§7พิมพ์ §a/tpaccept §7หรือ §a/tpa accept §7เพื่อยอมรับ (§c/tpdeny §7เพื่อปฏิเสธ)"));
	}

	public void accept(ServerPlayer target, String specificSender) {
		long now = System.currentTimeMillis();
		Request req = pending.get(target.getUUID());
		if (req == null || req.isExpired(now)) {
			pending.remove(target.getUUID());
			target.sendSystemMessage(Component.literal("§c[TPA] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

		if (specificSender != null && !specificSender.isBlank() && !req.senderName().equalsIgnoreCase(specificSender.trim())) {
			target.sendSystemMessage(Component.literal("§c[TPA] ไม่พบคำขอวาร์ปจากผู้เล่น " + specificSender));
			return;
		}

		pending.remove(target.getUUID());
		var srv = ClaimMap.getServer();
		if (srv == null) {
			target.sendSystemMessage(Component.literal("§c[TPA] เซิร์ฟเวอร์ยังไม่พร้อม"));
			return;
		}
		ServerPlayer sender = srv.getPlayerList().getPlayer(req.senderUuid());
		if (sender == null) {
			target.sendSystemMessage(Component.literal("§c[TPA] ผู้เล่น " + req.senderName() + " ออฟไลน์ไปแล้ว"));
			return;
		}

		doTeleport(sender, target);
		target.sendSystemMessage(Component.literal("§6[TPA] §aยอมรับคำขอวาร์ปจาก §f" + sender.getScoreboardName() + " §aแล้ว"));
		sender.sendSystemMessage(Component.literal("§6[TPA] §aวาร์ปไปยัง §f" + target.getScoreboardName() + " §aสำเร็จ"));
	}

	public void deny(ServerPlayer target, String specificSender) {
		long now = System.currentTimeMillis();
		Request req = pending.remove(target.getUUID());
		if (req == null || req.isExpired(now)) {
			target.sendSystemMessage(Component.literal("§c[TPA] ไม่มีคำขอวาร์ปที่รอยอมรับ หรือคำขอหมดอายุแล้ว"));
			return;
		}

		target.sendSystemMessage(Component.literal("§6[TPA] §eปฏิเสธคำขอวาร์ปจาก §f" + req.senderName() + " §eแล้ว"));
		var srv = ClaimMap.getServer();
		if (srv != null) {
			ServerPlayer sender = srv.getPlayerList().getPlayer(req.senderUuid());
			if (sender != null) {
				sender.sendSystemMessage(Component.literal("§6[TPA] §c" + target.getScoreboardName() + " §cปฏิเสธคำขอวาร์ปของคุณ"));
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
			sender.sendSystemMessage(Component.literal("§6[TPA] §eยกเลิกคำขอวาร์ปไปยัง §f" + req.targetName() + " §eแล้ว"));
		} else {
			sender.sendSystemMessage(Component.literal("§c[TPA] คุณไม่มีคำขอวาร์ปที่ค้างอยู่"));
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
