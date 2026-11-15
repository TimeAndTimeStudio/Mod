package studio.timeandtime.claimmap.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import studio.timeandtime.claimmap.ClaimMap;

/**
 * {@link MinecraftServer#saveAllChunks} saves every loaded level's chunks, but
 * writes {@code level.dat} only for the primary save. Managed worlds each have
 * their own {@code level.dat}, so save their {@code WorldData} right after the
 * vanilla pass (autosave and shutdown both go through this method).
 */
@Mixin(MinecraftServer.class)
public abstract class ServerWorldSaveMixin {

	@Inject(method = "saveAllChunks", at = @At("RETURN"))
	private void claimmap$saveManagedWorldData(boolean suppressLog, boolean flush, boolean forced,
			CallbackInfoReturnable<Boolean> cir) {
		ClaimMap mod = ClaimMap.instance();
		if (mod != null && mod.worldManager() != null) {
			mod.worldManager().saveWorldData((MinecraftServer) (Object) this);
		}
	}
}
