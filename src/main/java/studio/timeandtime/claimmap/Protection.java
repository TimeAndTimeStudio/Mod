package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import studio.timeandtime.claimmap.world.EndArenaManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks breaking / placing / using containers / fluids / fire inside claimed
 * cells that the actor does not have access to (plan.md: ป้องกันเขต).
 * Also protects the 500x500 End dragon arena from destroying natural terrain,
 * while allowing placing/breaking temporary blocks and iron bars.
 */
public final class Protection {
	private static final Component DENY = Component.literal("Protected area! Only the owner and members can do that here.");
	private static final Component DENY_END_COMMON = Component.literal("§cพื้นที่ส่วนกลางเกาะมังกร (The End): ไม่สามารถขุดบล็อกธรรมชาติได้!");
	private static final long DENY_COOLDOWN_MS = 2000L;

	private final RegionManager regions;
	private final EndArenaManager endArena;
	private final Map<String, Long> lastDeny = new ConcurrentHashMap<>();

	public Protection(RegionManager regions, EndArenaManager endArena) {
		this.regions = regions;
		this.endArena = endArena;
	}

	public void register() {
		PlayerBlockBreakEvents.BEFORE.register(this::onBreak);
		PlayerBlockBreakEvents.AFTER.register(this::onAfterBreak);
		AttackBlockCallback.EVENT.register(this::onAttack);
		AttackEntityCallback.EVENT.register(this::onAttackEntity);
		UseBlockCallback.EVENT.register(this::onUseBlock);
		UseEntityCallback.EVENT.register(this::onUseEntity);
		UseItemCallback.EVENT.register(this::onUseItem);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allowPvpDamage);
	}

	// ---- break --------------------------------------------------------------

	private boolean onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
		if (level != null && EndArenaManager.isMainIsland(level, pos)) {
			if (isAdmin(player)) return true;
			// Allow breaking iron bars (vanilla crystal cages regenerate when summoning dragon)
			if (state.is(Blocks.IRON_BARS)) return true;
			// Allow breaking player-placed blocks
			if (endArena != null && endArena.isPlayerPlaced(pos)) return true;
			denyEnd(player);
			return false;
		}
		if (allowed(level, player, pos)) return true;
		deny(player);
		return false;
	}

	private void onAfterBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
		if (level != null && endArena != null && EndArenaManager.isMainIsland(level, pos)) {
			endArena.recordBroken(pos);
		}
	}

	private InteractionResult onAttack(Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) {
		if (isAdmin(player)) return InteractionResult.PASS;
		if (level != null && EndArenaManager.isMainIsland(level, pos)) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.IRON_BARS) || (endArena != null && endArena.isPlayerPlaced(pos))) {
				return InteractionResult.PASS;
			}
			denyEnd(player);
			return InteractionResult.FAIL;
		}
		if (allowed(level, player, pos)) return InteractionResult.PASS;
		deny(player);
		return InteractionResult.FAIL;
	}

	private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (isAdmin(player)) return InteractionResult.PASS;
		BlockPos targetPos = hit.getBlockPos();
		if (level != null && EndArenaManager.isMainIsland(level, targetPos)) {
			// In End main island, placing blocks or using items on blocks is allowed!
			// We track placed blocks if placing against this block
			BlockPos placePos = targetPos.relative(hit.getDirection());
			if (endArena != null && EndArenaManager.isMainIsland(level, placePos)) {
				ItemStack inHand = player.getItemInHand(hand);
				if (!inHand.isEmpty() && (inHand.getItem() instanceof net.minecraft.world.item.BlockItem || inHand.getItem() instanceof BucketItem)) {
					endArena.recordPlaced(placePos);
				}
			}
			return InteractionResult.PASS;
		}
		// The lobby is adventure-like for building, but doors, fence gates and
		// switches are furniture: players must be able to use them.
		if (level != null && studio.timeandtime.claimmap.world.WorldManager.isLobby(level)
				&& isLobbyInteraction(level.getBlockState(targetPos))) {
			return InteractionResult.PASS;
		}
		if (allowed(level, player, targetPos)) return InteractionResult.PASS;
		deny(player);
		return InteractionResult.FAIL;
	}

	private static boolean isLobbyInteraction(BlockState state) {
		return state.is(BlockTags.DOORS)
				|| state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.BUTTONS)
				|| state.is(BlockTags.PRESSURE_PLATES)
				|| state.is(Blocks.LEVER);
	}

	private InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (isAdmin(player)) return InteractionResult.PASS;
		if (!isFluidFireItem(player.getItemInHand(hand))) return InteractionResult.PASS;
		if (level != null && EndArenaManager.isMainIsland(level, player.blockPosition())) {
			if (endArena != null) {
				endArena.recordPlaced(player.blockPosition());
			}
			return InteractionResult.PASS;
		}
		if (!allowed(level, player, player.blockPosition())) return InteractionResult.FAIL; // silently, spraying messages
		return InteractionResult.PASS;
	}


	private InteractionResult onAttackEntity(Player player, Level level, InteractionHand hand,
			Entity entity, EntityHitResult hit) {
		return entityInteraction(player, level, entity);
	}

	private InteractionResult onUseEntity(Player player, Level level, InteractionHand hand,
			Entity entity, EntityHitResult hit) {
		return entityInteraction(player, level, entity);
	}

	private InteractionResult entityInteraction(Player player, Level level, Entity entity) {
		if (isAdmin(player) || level == null || entity instanceof Player) return InteractionResult.PASS;
		if (studio.timeandtime.claimmap.world.WorldManager.isLobby(level)) {
			// Lobby decorations (item frames, paintings, armor stands) are fixed.
			return isDecoration(entity) ? InteractionResult.FAIL : InteractionResult.PASS;
		}
		if (level.getServer() == null || !RegionManager.isClaimableWorld(level)) return InteractionResult.PASS;
		if (allowed(level, player, entity.blockPosition())) return InteractionResult.PASS;
		deny(player);
		return InteractionResult.FAIL;
	}

	private static boolean isDecoration(Entity entity) {
		return entity instanceof HangingEntity || entity instanceof ArmorStand;
	}

	/**
	 * Player-vs-player damage inside a claim follows that claim's own PvP flag.
	 * Wilderness keeps the server-wide {@code pvp} setting. The owner toggles
	 * the flag from the claim GUI; it applies to every claimed cell.
	 */
	private boolean allowPvpDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer victim)) return true;
		if (!(source.getEntity() instanceof ServerPlayer attacker)) return true;
		if (attacker == victim) return true;
		Level level = victim.level();
		if (level == null || level.getServer() == null || !RegionManager.isClaimableWorld(level)) return true;
		// Inside End dragon arena, allow PvP according to server standard
		if (EndArenaManager.isMainIsland(level, victim.blockPosition())) return true;
		RegionManager.CellData cell = regions.get(regions.cellIdAt(level, victim.getBlockX(), victim.getBlockZ()));
		if (cell == null || cell.pvp) return true;
		attacker.sendSystemMessage(Component.literal(
				"§cเขต " + cell.id + " ปิด PvP อยู่ (เจ้าของเขตสามารถเปิดได้จากเมนูจัดการเขต)"));
		return false;
	}

	// ---- helpers ------------------------------------------------------------

	private boolean allowed(Level level, Player player, BlockPos pos) {
		if (level != null && studio.timeandtime.claimmap.world.WorldManager.isLobby(level)) {
			return isAdmin(player);
		}
		if (level != null && level.getServer() != null && !RegionManager.isClaimableWorld(level)) {
			return true; // Claim system and protection only exist in claimable worlds!
		}
		if (level != null && EndArenaManager.isMainIsland(level, pos)) {
			return true;
		}
		String id = regions.cellIdAt(level, pos.getX(), pos.getZ());
		RegionManager.CellData cell = regions.get(id);
		if (cell == null) return true;                       // unclaimed cell: free
		if (cell.hasAccess(player.getUUID().toString())) return true;
		return false;
	}

	private boolean isAdmin(Player player) {
		return player instanceof ServerPlayer sp
				&& sp.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
	}

	private void denyEnd(Player player) {
		String uuid = player.getUUID().toString();
		long now = System.currentTimeMillis();
		Long last = lastDeny.get(uuid);
		if (last == null || now - last > DENY_COOLDOWN_MS) {
			lastDeny.put(uuid, now);
			player.sendSystemMessage(DENY_END_COMMON);
		}
	}

	private boolean isFluidFireItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = stack.getItem();
		if (item instanceof BucketItem) return true;
		return item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE;
	}

	private void deny(Player player) {
		long now = System.currentTimeMillis();
		String key = player.getUUID().toString();
		Long last = lastDeny.get(key);
		if (last != null && now - last < DENY_COOLDOWN_MS) return;
		lastDeny.put(key, now);
		player.sendSystemMessage(DENY);
	}
}
