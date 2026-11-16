package studio.timeandtime.claimmap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles:
 * 1. Random Overworld spawn within 10,000 x 10,000 range (-5000..5000) for new players & bedless respawns.
 * 2. 30-second damage immunity & protection upon entering a world / joining / respawning.
 */
public final class WorldSpawnProtection {
	private static final Gson GSON = new Gson();
	private static final int RANGE = 5000; // -5000 to +5000 (10000x10000 area)
	private static final int PROTECTION_SECONDS = 30;

	private final Path file;
	private final Set<UUID> spawnedPlayers = new HashSet<>();
	private final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();

	public WorldSpawnProtection(Path dataDir) {
		this.file = dataDir.resolve("spawned-players.json");
		load();
	}

	public void register() {
		// 1. On Player Join:
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (player == null) return;

			boolean isNew = false;
			synchronized (spawnedPlayers) {
				if (!spawnedPlayers.contains(player.getUUID())) {
					spawnedPlayers.add(player.getUUID());
					save();
					isNew = true;
				}
			}

			final boolean firstTime = isNew;
			server.execute(() -> {
				if (!player.isAlive()) return;
				if (firstTime) {
					ServerLevel overworld = server.overworld();
					randomSpawnPlayer(player, overworld, true);
				} else {
					grantProtection(player);
				}
			});
		});

		// 2. On Player Respawn:
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (newPlayer.getRespawnConfig() == null) {
				// No bed or anchor: random spawn in Overworld
				ServerLevel overworld = newPlayer.level().getServer().overworld();
				newPlayer.level().getServer().execute(() -> {
					randomSpawnPlayer(newPlayer, overworld, false);
				});
			} else {
				// Has bed: respawn at bed, grant protection
				grantProtection(newPlayer);
			}
		});

		// 3. On Dimension / World Change:
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			grantProtection(player);
		});

		// 4. Damage Cancellation while protected:
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				if (isProtected(player.getUUID())) {
					return false;
				}
			}
			return true;
		});

		// 5. Server Tick to expire protection cleanly:
		ServerTickEvents.END_SERVER_TICK.register(server -> tick());
	}

	public void tick() {
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, Long> entry : protectedUntil.entrySet()) {
			if (now >= entry.getValue()) {
				protectedUntil.remove(entry.getKey());
				if (ClaimMap.getServer() != null) {
					ServerPlayer sp = ClaimMap.getServer().getPlayerList().getPlayer(entry.getKey());
					if (sp != null) {
						sp.sendSystemMessage(Component.literal("§e[ระบบคุ้มครอง] หมดเวลาคุ้มครองความปลอดภัย 30 วินาทีแล้ว"));
					}
				}
			}
		}
	}

	public boolean isProtected(UUID uuid) {
		Long until = protectedUntil.get(uuid);
		return until != null && System.currentTimeMillis() < until;
	}

	public void grantProtection(ServerPlayer player) {
		protectedUntil.put(player.getUUID(), System.currentTimeMillis() + (PROTECTION_SECONDS * 1000L));
		player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, PROTECTION_SECONDS * 20, 4, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, PROTECTION_SECONDS * 20, 0, false, false, true));
		player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, PROTECTION_SECONDS * 20, 0, false, false, true));
		player.sendSystemMessage(Component.literal("§a[ระบบคุ้มครอง] คุณได้รับการคุ้มครองความปลอดภัย 30 วินาที"));
	}

	public void randomSpawnPlayer(ServerPlayer player, ServerLevel level, boolean firstJoin) {
		BlockPos safePos = findSafeSpawnLocation(level, -RANGE, RANGE, -RANGE, RANGE);
		player.teleportTo(level, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
				Set.of(), player.getYRot(), player.getXRot(), true);

		if (firstJoin) {
			player.sendSystemMessage(Component.literal("§6[จุดเกิดสุ่ม] ยินดีต้อนรับ! คุณเกิดที่พิกัด X: " + safePos.getX() + ", Z: " + safePos.getZ()));
		} else {
			player.sendSystemMessage(Component.literal("§6[จุดเกิดสุ่ม] ไม่มีจุดเกิดเตียง คุณได้เกิดใหม่ที่พิกัด X: " + safePos.getX() + ", Z: " + safePos.getZ()));
		}

		grantProtection(player);
	}

	public static BlockPos findSafeSpawnLocation(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
		RandomSource rnd = level.getRandom();
		for (int attempt = 0; attempt < 40; attempt++) {
			int rx = minX + rnd.nextInt(maxX - minX + 1);
			int rz = minZ + rnd.nextInt(maxZ - minZ + 1);

			int targetY = -1;
			int topY = Math.min(level.getMaxY() - 2, 260);
			int minY = Math.max(level.getMinY() + 1, 60);

			for (int y = topY; y >= minY; y--) {
				BlockPos pBelow = new BlockPos(rx, y - 1, rz);
				BlockPos pFeet = new BlockPos(rx, y, rz);
				BlockPos pHead = new BlockPos(rx, y + 1, rz);

				BlockState stateBelow = level.getBlockState(pBelow);
				BlockState stateFeet = level.getBlockState(pFeet);
				BlockState stateHead = level.getBlockState(pHead);

				if (stateBelow.isSolid()
						&& !stateBelow.is(Blocks.LAVA)
						&& !stateBelow.is(Blocks.WATER)
						&& !stateBelow.is(Blocks.FIRE)
						&& !stateBelow.is(Blocks.MAGMA_BLOCK)
						&& !stateBelow.is(Blocks.CACTUS)) {
					if ((stateFeet.isAir() || !stateFeet.isSolid())
							&& (stateHead.isAir() || !stateHead.isSolid())
							&& !stateFeet.is(Blocks.LAVA)
							&& !stateFeet.is(Blocks.WATER)
							&& !stateHead.is(Blocks.LAVA)) {
						targetY = y;
						break;
					}
				}
			}

			if (targetY != -1) {
				return new BlockPos(rx, targetY, rz);
			}
		}

		// Fallback
		int fbX = minX + rnd.nextInt(maxX - minX + 1);
		int fbZ = minZ + rnd.nextInt(maxZ - minZ + 1);
		int fallbackY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fbX, fbZ) + 1;
		return new BlockPos(fbX, Math.max(64, fallbackY), fbZ);
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			Set<UUID> set = GSON.fromJson(json, new TypeToken<Set<UUID>>(){}.getType());
			if (set != null) {
				synchronized (spawnedPlayers) {
					spawnedPlayers.addAll(set);
				}
			}
		} catch (Exception e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not load spawned-players.json: {}", e.getMessage());
		}
	}

	private void save() {
		try {
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			synchronized (spawnedPlayers) {
				Files.writeString(file, GSON.toJson(spawnedPlayers), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			ClaimMap.LOGGER.warn("[timesmp] Could not save spawned-players.json: {}", e.getMessage());
		}
	}
}
