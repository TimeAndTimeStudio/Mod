package studio.timeandtime.claimmap.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;
import studio.timeandtime.claimmap.ClaimMap;

/** Blocks item drops until the player is authenticated. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {

	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void claimmap$blockDrop(boolean all, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		ClaimMap mod = ClaimMap.instance();
		AuthGate gate = mod == null ? null : mod.authGate();
		if (gate != null && !gate.isAuthed(self.getUUID())) {
			ci.cancel();
		}
	}
}
