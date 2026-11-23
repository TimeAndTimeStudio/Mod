package studio.timeandtime.timesmpclient.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Access to {@code SkinManager}'s private {@code registerTextures}. */
@Mixin(SkinManager.class)
public interface SkinManagerInvoker {
	@Invoker("registerTextures")
	CompletableFuture<PlayerSkin> timesmp$registerTextures(UUID uuid, MinecraftProfileTextures textures);
}