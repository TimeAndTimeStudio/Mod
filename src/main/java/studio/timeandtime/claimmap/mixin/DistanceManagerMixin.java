package studio.timeandtime.claimmap.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

	@SuppressWarnings("unchecked")
	@WrapOperation(
			method = "removePlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V",
			at = @At(
					value = "INVOKE",
					target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;"
			)
	)
	private Object getOrCreatePlayerSet(Long2ObjectMap<ObjectSet<ServerPlayer>> map, long key, Operation<Object> original) {
		Object res = original.call(map, key);
		if (res == null) {
			return new ObjectOpenHashSet<ServerPlayer>();
		}
		return res;
	}
}
