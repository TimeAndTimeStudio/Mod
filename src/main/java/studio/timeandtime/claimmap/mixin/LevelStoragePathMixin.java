package studio.timeandtime.claimmap.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import studio.timeandtime.claimmap.world.WorldStoragePaths;

import java.nio.file.Path;

/**
 * Managed worlds are complete saves in their own folder. Their chunks live in
 * {@code worlds/<name>/region}, not in a vanilla per-dimension subfolder, so
 * route chunk storage for marked storage sessions to the save root.
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStoragePathMixin {

	@Inject(method = "getDimensionPath", at = @At("HEAD"), cancellable = true)
	private void claimmap$managedRoot(ResourceKey<Level> dimension,
			CallbackInfoReturnable<Path> cir) {
		LevelStorageSource.LevelStorageAccess self = (LevelStorageSource.LevelStorageAccess) (Object) this;
		if (WorldStoragePaths.isManaged(self)) {
			cir.setReturnValue(self.getLevelPath(LevelResource.ROOT));
		}
	}
}
