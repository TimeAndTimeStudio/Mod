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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Managed worlds are complete saves in their own folder. Route chunk storage
 * for marked storage sessions to the save itself:
 *
 * <ul>
 *   <li>Modern saves (1.21.4+) keep the overworld under
 *       {@code dimensions/minecraft/overworld}; that folder is used when it
 *       exists so exported maps load with their real terrain.</li>
 *   <li>Older saves keep the chunks in the save root ({@code region/}), so the
 *       root is used as the fallback.</li>
 * </ul>
 *
 * <p>Managed levels are mounted under a custom dimension key (for example
 * {@code timesmp:lobby}), but their chunks always come from the save's
 * overworld data.
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStoragePathMixin {

	@Inject(method = "getDimensionPath", at = @At("HEAD"), cancellable = true)
	private void claimmap$managedDimensionPath(ResourceKey<Level> dimension,
			CallbackInfoReturnable<Path> cir) {
		LevelStorageSource.LevelStorageAccess self = (LevelStorageSource.LevelStorageAccess) (Object) this;
		if (!WorldStoragePaths.isManaged(self)) return;
		Path root = self.getLevelPath(LevelResource.ROOT);
		Path modernOverworld = root.resolve("dimensions")
				.resolve("minecraft")
				.resolve("overworld");
		if (Files.isDirectory(modernOverworld)) {
			cir.setReturnValue(modernOverworld);
		} else {
			cir.setReturnValue(root);
		}
	}
}
