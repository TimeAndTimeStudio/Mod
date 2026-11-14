package studio.timeandtime.claimmap.mixin;

import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;

/**
 * Holds the connection in the configuration phase until the password dialog
 * has been passed. Without this, a client could enter the world before the
 * login gate finishes.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigurationFinishMixin {

	@Inject(method = "handleConfigurationFinished", at = @At("HEAD"), cancellable = true)
	private void claimmap$gateConfigurationFinish(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
		ServerConfigurationPacketListenerImpl self = (ServerConfigurationPacketListenerImpl) (Object) this;
		if (!AuthGate.allowConfigurationFinished(self)) {
			ci.cancel();
		}
	}
}
