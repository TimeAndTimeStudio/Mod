package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Periodically clears dropped item entities on the ground across all loaded worlds
 * every 10 minutes (12,000 ticks) with broadcast warnings at 60s, 30s, and 10s.
 */
public final class GroundItemClearer {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-cleaner");

	// 10 minutes = 600 seconds = 12,000 ticks (at 20 TPS)
	private static final int INTERVAL_TICKS = 10 * 60 * 20;

	private static int currentTick = 0;

	private GroundItemClearer() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(GroundItemClearer::onTick);
	}

	private static void onTick(MinecraftServer server) {
		currentTick++;
		int remainingTicks = INTERVAL_TICKS - currentTick;

		// Broadcast countdown warnings
		if (remainingTicks == 60 * 20) {
			broadcast(server, Component.literal("§e[ระบบทำความสะอาด] §cไอเท็มที่ตกบนพื้นทั้งหมดจะถูกลบในอีก §f60 §cวินาที!"));
		} else if (remainingTicks == 30 * 20) {
			broadcast(server, Component.literal("§e[ระบบทำความสะอาด] §cไอเท็มที่ตกบนพื้นทั้งหมดจะถูกลบในอีก §f30 §cวินาที!"));
		} else if (remainingTicks == 10 * 20) {
			broadcast(server, Component.literal("§e[ระบบทำความสะอาด] §cไอเท็มที่ตกบนพื้นทั้งหมดจะถูกลบในอีก §f10 §cวินาที! กรุณาเก็บของด่วน"));
		} else if (currentTick >= INTERVAL_TICKS) {
			currentTick = 0;
			int totalCleared = clearGroundItems(server);
			broadcast(server, Component.literal("§e[ระบบทำความสะอาด] §aลบไอเท็มบนพื้นทั้งหมดเรียบร้อยแล้ว (§f" + totalCleared + " §aชิ้น)"));
			LOGGER.info("[timesmp] Cleared {} dropped items from ground", totalCleared);
		}
	}

	public static int clearGroundItems(MinecraftServer server) {
		int count = 0;
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> toRemove = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof ItemEntity) {
					toRemove.add(entity);
				}
			}
			for (Entity e : toRemove) {
				e.discard();
				count++;
			}
		}
		return count;
	}

	private static void broadcast(MinecraftServer server, Component message) {
		if (server.getPlayerList() != null) {
			server.getPlayerList().broadcastSystemMessage(message, false);
		}
	}
}
