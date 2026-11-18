package studio.timeandtime.claimmap;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Enforces dimension lock from {@link ServerSettings} (setting.json):
 * If Nether is disabled:
 * - Cannot ignite obsidian with flint and steel / fire charge
 * - Nether portals do not transport players
 * If End is disabled:
 * - Cannot insert eye of ender into end portal frame
 * - End portals do not transport players
 */
public final class PortalGate {
	private final ServerSettings settings;

	public PortalGate(ServerSettings settings) {
		this.settings = settings;
	}

	public void register() {
		// 1. Block igniting obsidian or placing fire adjacent to obsidian if Nether is disabled
		UseBlockCallback.EVENT.register(this::onUseBlock);

		// 2. Block portal teleportation if destination is disabled
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			var destDim = destination.dimension();
			if (destDim == Level.NETHER && !settings.isNetherEnabled()) {
				player.sendSystemMessage(Component.literal("§c[!] มิติเนเธอร์ (The Nether) ยังไม่เปิดให้บริการในขณะนี้"));
				ServerLevel overworld = destination.getServer().overworld();
				player.teleportTo(overworld, player.getX(), Math.max(overworld.getMinY() + 10, player.getY()), player.getZ(),
						java.util.Set.of(), player.getYRot(), player.getXRot(), true);
			} else if (destDim == Level.END && !settings.isEndEnabled()) {
				player.sendSystemMessage(Component.literal("§c[!] มิติแดนเดอะเอนด์ (The End) ยังไม่เปิดให้บริการในขณะนี้"));
				ServerLevel overworld = destination.getServer().overworld();
				player.teleportTo(overworld, player.getX(), Math.max(overworld.getMinY() + 10, player.getY()), player.getZ(),
						java.util.Set.of(), player.getYRot(), player.getXRot(), true);
			}
		});
	}

	private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide()) return InteractionResult.PASS;
		ItemStack stack = player.getItemInHand(hand);
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);

		// Check End Portal Frame
		if (!settings.isEndEnabled()) {
			if (state.is(Blocks.END_PORTAL_FRAME) && stack.is(Items.ENDER_EYE)) {
				if (!state.getValue(EndPortalFrameBlock.HAS_EYE)) {
					player.sendSystemMessage(Component.literal("§c[!] มิติแดนเดอะเอนด์ (The End) ยังไม่เปิดให้บริการในขณะนี้"));
					return InteractionResult.FAIL;
				}
			}
		}

		// Check Nether Portal Ignition (Flint & Steel or Fire Charge on Obsidian or adjacent)
		if (!settings.isNetherEnabled()) {
			boolean isIgniter = stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);
			if (isIgniter) {
				if (state.is(Blocks.OBSIDIAN)) {
					player.sendSystemMessage(Component.literal("§c[!] มิติเนเธอร์ (The Nether) ยังไม่เปิดให้บริการในขณะนี้"));
					return InteractionResult.FAIL;
				}
				BlockPos target = pos.relative(hit.getDirection());
				if (isNearObsidian(level, target)) {
					player.sendSystemMessage(Component.literal("§c[!] มิติเนเธอร์ (The Nether) ยังไม่เปิดให้บริการในขณะนี้"));
					return InteractionResult.FAIL;
				}
			}
		}

		return InteractionResult.PASS;
	}

	private boolean isNearObsidian(Level level, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (level.getBlockState(pos.relative(dir)).is(Blocks.OBSIDIAN)) {
				return true;
			}
		}
		return false;
	}
}
