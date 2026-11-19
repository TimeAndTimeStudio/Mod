package studio.timeandtime.claimmap.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import studio.timeandtime.claimmap.ClaimMap;
import studio.timeandtime.claimmap.RegionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Ensures creeper explosions damage entities normally, but do not destroy blocks
 * inside claimed regions (cells) in the overworld. In other worlds or outside claims,
 * blocks break normally.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	public abstract Entity getDirectSourceEntity();

	@Shadow
	public abstract LivingEntity getIndirectSourceEntity();

	@Shadow
	private ServerLevel level;

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> filterClaimedBlocks(List<BlockPos> positions) {
		if (this.level == null || this.level.getServer() == null) {
			return positions;
		}

		// Protect End dragon arena natural blocks from ANY explosion (crystals, beds, anchors, dragons)
		if (this.level.dimension() == net.minecraft.world.level.Level.END) {
			studio.timeandtime.claimmap.world.EndArenaManager arena = ClaimMap.instance() != null ? ClaimMap.instance().endArena() : null;
			List<BlockPos> filtered = new ArrayList<>(positions.size());
			for (BlockPos pos : positions) {
				if (studio.timeandtime.claimmap.world.EndArenaManager.isMainIsland(this.level, pos)) {
					// Only allow destroying blocks if placed by player
					if (arena != null && arena.isPlayerPlaced(pos)) {
						filtered.add(pos);
					}
				} else {
					filtered.add(pos);
				}
			}
			return filtered;
		}

		if (!RegionManager.isClaimableWorld(this.level)) {
			return positions;
		}
		Entity direct = this.getDirectSourceEntity();
		LivingEntity indirect = this.getIndirectSourceEntity();
		if (direct instanceof Creeper || indirect instanceof Creeper) {
			if (ClaimMap.instance() != null && ClaimMap.instance().regions() != null) {
				RegionManager rm = ClaimMap.instance().regions();
				List<BlockPos> filtered = new ArrayList<>(positions.size());
				for (BlockPos pos : positions) {
					String cellId = rm.cellIdAt(this.level, pos.getX(), pos.getZ());
					if (rm.get(cellId) == null) {
						filtered.add(pos);
					}
				}
				return filtered;
			}
		}
		return positions;
	}
}
