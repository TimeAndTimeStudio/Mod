package studio.timeandtime.claimmap.lod;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LodService: Handles client handshake, requests, and streaming of pregenerated LOD tiles via tml:lod.
 */
public class LodService {
	private static final Logger LOGGER = LoggerFactory.getLogger("timesmp-lod");
	private static final LodService INSTANCE = new LodService();

	public static LodService instance() {
		return INSTANCE;
	}

	public static final CustomPacketPayload.Type<LodPayload> PAYLOAD_TYPE =
			new CustomPacketPayload.Type<>(LodProtocol.CHANNEL);

	public record LodPayload(byte[] data) implements CustomPacketPayload {
		public static final StreamCodec<FriendlyByteBuf, LodPayload> STREAM_CODEC =
				CustomPacketPayload.codec(LodPayload::write, LodPayload::new);

		public LodPayload(FriendlyByteBuf buf) {
			this(readBytes(buf));
		}

		private static byte[] readBytes(FriendlyByteBuf buf) {
			byte[] b = new byte[buf.readableBytes()];
			buf.readBytes(b);
			return b;
		}

		public void write(FriendlyByteBuf buf) {
			buf.writeBytes(data);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PAYLOAD_TYPE;
		}
	}

	private final Set<UUID> tmlClients = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Integer> clientDistances = new ConcurrentHashMap<>();

	public void registerNetworking() {
		PayloadTypeRegistry.serverboundPlay().register(PAYLOAD_TYPE, LodPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PAYLOAD_TYPE, LodPayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(PAYLOAD_TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			byte[] raw = payload.data();
			if (raw.length == 0) return;

			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
			try {
				handleClientRequest(player, buf);
			} catch (Exception e) {
				LOGGER.warn("Error handling tml:lod packet from {}: {}", player.getScoreboardName(), e.getMessage());
			}
		});
	}

	public void onPlayerJoin(ServerPlayer player) {
		tmlClients.add(player.getUUID());
		sendOffer(player, 64);
	}

	public void onPlayerDisconnect(ServerPlayer player) {
		tmlClients.remove(player.getUUID());
		clientDistances.remove(player.getUUID());
	}

	public void sendOffer(ServerPlayer player, int distance) {
		clientDistances.put(player.getUUID(), distance);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(LodProtocol.VERSION);
		buf.writeVarInt(Math.min(distance, LodProtocol.MAX_DISTANCE));
		buf.writeVarInt(LodProtocol.IN_FLIGHT);
		buf.writeVarInt(LodProtocol.PER_SECOND);

		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		ServerPlayNetworking.send(player, new LodPayload(out));
	}

	private void handleClientRequest(ServerPlayer player, FriendlyByteBuf buf) {
		int id = buf.readVarInt();
		int startX = buf.readInt();
		int startZ = buf.readInt();
		int stride = buf.readVarInt();
		boolean approx = buf.readBoolean();

		ServerLevel level = player.level();
		sendTileReply(player, level, id, startX, startZ, stride);
	}

	private void sendTileReply(ServerPlayer player, ServerLevel level, int id, int startX, int startZ, int stride) {
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		out.writeVarInt(id);

		ChunkPos chunkPos = new ChunkPos(startX >> 4, startZ >> 4);
		ChunkAccess chunk = level.getChunkSource().getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL, false);

		if (chunk == null) {
			out.writeByte(1); // refusal
			sendPacket(player, out);
			return;
		}

		out.writeByte(0); // success

		List<String> biomeNames = new ArrayList<>();
		Map<String, Integer> biomeMap = new HashMap<>();

		FriendlyByteBuf colBuf = new FriendlyByteBuf(Unpooled.buffer());

		for (int cz = 0; cz < LodProtocol.SIDE; cz++) {
			for (int cx = 0; cx < LodProtocol.SIDE; cx++) {
				int bx = startX + cx * stride;
				int bz = startZ + cz * stride;

				int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx & 15, bz & 15);
				if (surfaceY <= level.getMinY()) {
					surfaceY = 64;
				}

				BlockPos bPos = new BlockPos(bx, surfaceY, bz);
				Holder<Biome> biomeHolder = level.getBiome(bPos);
				String biomeName = biomeHolder.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");

				int biomeIdx = biomeMap.computeIfAbsent(biomeName, k -> {
					biomeNames.add(k);
					return biomeNames.size() - 1;
				});

				BlockState state = chunk.getBlockState(bPos);
				int stateId = net.minecraft.world.level.block.Block.getId(state);

				colBuf.writeVarInt(biomeIdx);
				colBuf.writeVarInt(1); // 1 span
				colBuf.writeVarInt(stateId);
				colBuf.writeVarInt(surfaceY - 1);
				colBuf.writeVarInt(surfaceY);
			}
		}

		out.writeVarInt(biomeNames.size());
		for (String b : biomeNames) {
			out.writeUtf(b);
		}

		byte[] colBytes = new byte[colBuf.readableBytes()];
		colBuf.readBytes(colBytes);
		out.writeBytes(colBytes);

		sendPacket(player, out);
	}

	private void sendPacket(ServerPlayer player, FriendlyByteBuf buf) {
		byte[] b = new byte[buf.readableBytes()];
		buf.readBytes(b);
		ServerPlayNetworking.send(player, new LodPayload(b));
	}
}
