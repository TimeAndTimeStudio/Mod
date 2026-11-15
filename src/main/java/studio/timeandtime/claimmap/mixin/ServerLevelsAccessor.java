package studio.timeandtime.claimmap.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.Executor;

/** Adds/removes managed worlds from the server's live level map. */
@Mixin(MinecraftServer.class)
public interface ServerLevelsAccessor {

	@Accessor("levels")
	Map<ResourceKey<Level>, ServerLevel> claimmap$levels();

	@Accessor("executor")
	Executor claimmap$executor();
}
