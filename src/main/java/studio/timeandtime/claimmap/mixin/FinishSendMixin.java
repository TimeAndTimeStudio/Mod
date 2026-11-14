package studio.timeandtime.claimmap.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.timeandtime.claimmap.AuthGate;

/**
 * Stops the configuration phase from ending before the password is correct.
 *
 * <p>{@code ClientboundFinishConfigurationPacket} is terminal: the configuration
 * encoder removes itself from the pipeline as soon as the packet is written.
 * The dialog therefore has to be sent before that packet, and the packet itself
 * has to be held back until authentication succeeds.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class FinishSendMixin {

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void claimmap$holdFinishConfiguration(Packet<?> packet, CallbackInfo ci) {
		if (!(packet instanceof ClientboundFinishConfigurationPacket)) return;
		ServerCommonPacketListenerImpl self = (ServerCommonPacketListenerImpl) (Object) this;
		if (!AuthGate.allowFinishConfigurationPacket(self)) {
			ci.cancel();
		}
	}
}
