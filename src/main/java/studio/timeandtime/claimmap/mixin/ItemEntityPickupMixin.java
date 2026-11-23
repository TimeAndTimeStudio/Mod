package studio.timeandtime.claimmap.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.ClaimMap;
import studio.timeandtime.claimmap.Protection;

@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void claimmap$preventPickupInProtectedArea(Player player, CallbackInfo ci) {
		ItemEntity item = (ItemEntity) (Object) this;
		if (item.level() == null || item.level().isClientSide()) return;

		ClaimMap mod = ClaimMap.instance();
		if (mod != null) {
			Protection protection = mod.protection();
			if (protection != null && !protection.canPickupItem(player, item.blockPosition())) {
				ci.cancel();
			}
		}
	}
}
