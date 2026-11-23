package studio.timeandtime.claimmap.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.skin.SkinRestorerService;

/**
 * Mixin to inject skin textures into offline-mode GameProfiles during login.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

	@Shadow
	private com.mojang.authlib.GameProfile authenticatedProfile;

	@ModifyVariable(method = "startClientVerification", at = @At("HEAD"), argsOnly = true)
	private GameProfile claimmap$modifyClientVerificationProfile(GameProfile profile) {
		return SkinRestorerService.applySkin(profile);
	}

	@Inject(method = "startClientVerification", at = @At("HEAD"))
	private void claimmap$applyOfflineSkin(GameProfile profile, CallbackInfo ci) {
		this.authenticatedProfile = SkinRestorerService.applySkin(profile);
	}

	@ModifyVariable(method = "finishLoginAndWaitForClient", at = @At("HEAD"), argsOnly = true)
	private GameProfile claimmap$modifyFinishLoginProfile(GameProfile profile) {
		return SkinRestorerService.applySkin(profile);
	}
}
