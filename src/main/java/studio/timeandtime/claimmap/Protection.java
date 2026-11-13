package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks breaking / placing / using containers / fluids / fire inside claimed
 * cells that the actor does not have access to (plan.md: ป้องกันเขต).
 */
public final class Protection {
	private static final Component DENY = Component.literal("Protected area! Only the owner and members can do that here.");
	private static final long DENY_COOLDOWN_MS = 2000L;

	private final RegionManager regions;
	private final Map<String, Long> lastDeny = new ConcurrentHashMap<>();

	public Protection(RegionManager regions) {
		this.regions = regions;
	}

	public void register() {
		PlayerBlockBreakEvents.BEFORE.register(this::onBreak);
		AttackBlockCallback.EVENT.register(this::onAttack);
		UseBlockCallback.EVENT.register(this::onUseBlock);
		UseItemCallback.EVENT.register(this::onUseItem);
	}

	// ---- break --------------------------------------------------------------

	private boolean onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
		if (allowed(player, pos)) return true;
		deny(player);
		return false;
	}

	private InteractionResult onAttack(Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) {
		if (isAdmin(player) || allowed(player, pos)) return InteractionResult.PASS;
		deny(player);
		return InteractionResult.FAIL;
	}

	private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (isAdmin(player) || allowed(player, hit.getBlockPos())) return InteractionResult.PASS;
		deny(player);
		return InteractionResult.FAIL;
	}

	private InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (isAdmin(player)) return InteractionResult.PASS;
		if (!isFluidFireItem(player.getItemInHand(hand))) return InteractionResult.PASS;
		if (!allowed(player, player.blockPosition())) return InteractionResult.FAIL; // silently, spraying messages
		return InteractionResult.PASS;
	}

	// ---- helpers ------------------------------------------------------------

	private boolean allowed(Player player, BlockPos pos) {
		String id = regions.cellIdAt(pos.getX(), pos.getZ());
		RegionManager.CellData cell = regions.get(id);
		if (cell == null) return true;                       // unclaimed cell: free
		if (cell.hasAccess(player.getUUID().toString())) return true;
		return false;
	}

	private boolean isAdmin(Player player) {
		return player instanceof ServerPlayer sp
				&& sp.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
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
