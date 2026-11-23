package studio.timeandtime.claimmap.mixin;

import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;

/**
 * The configuration listener overrides the common handler, so the common
 * mixin never sees this packet. Handle the mod-pushed pack here and stop
 * vanilla from trying to finish a server_resource_pack task that it never
 * created (which throws "Unexpected request for task finish").
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigurationResourcePackResponseMixin {
	@Inject(method = "handleResourcePackResponse", at = @At("HEAD"), cancellable = true)
	private void claimmap$onConfigurationResourcePack(ServerboundResourcePackPacket packet, CallbackInfo ci) {
		AuthGate.onResourcePackResponse((ServerConfigurationPacketListenerImpl) (Object) this, packet.action());
		ci.cancel();
	}
}
