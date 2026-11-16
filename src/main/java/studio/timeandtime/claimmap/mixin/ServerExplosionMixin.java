package studio.timeandtime.claimmap.mixin;

import net.minecraft.core.BlockPos;
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
 * inside claimed regions (cells). Outside claimed regions, blocks break normally.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	public abstract Entity getDirectSourceEntity();

	@Shadow
	public abstract LivingEntity getIndirectSourceEntity();

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> filterClaimedBlocks(List<BlockPos> positions) {
		Entity direct = this.getDirectSourceEntity();
		LivingEntity indirect = this.getIndirectSourceEntity();
		if (direct instanceof Creeper || indirect instanceof Creeper) {
			if (ClaimMap.instance() != null && ClaimMap.instance().regions() != null) {
				RegionManager rm = ClaimMap.instance().regions();
				List<BlockPos> filtered = new ArrayList<>(positions.size());
				for (BlockPos pos : positions) {
					String cellId = rm.cellIdAt(pos.getX(), pos.getZ());
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
