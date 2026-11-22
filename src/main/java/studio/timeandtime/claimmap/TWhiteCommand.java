package studio.timeandtime.claimmap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

/**
 * TWhiteCommand: Manages whitelist specifically for offline/cracked accounts,
 * preserving the EXACT casing entered by the user (ตัวพิมพ์ใหญ่-ตัวพิมพ์เล็กตามที่พิมพ์).
 */
public class TWhiteCommand {

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("twhite")
				.requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
				.then(Commands.literal("add")
						.then(Commands.argument("username", StringArgumentType.word())
								.executes(ctx -> {
									String username = StringArgumentType.getString(ctx, "username");
									return addWhitelist(ctx.getSource(), username);
								})))
				.then(Commands.literal("remove")
						.then(Commands.argument("username", StringArgumentType.word())
								.executes(ctx -> {
									String username = StringArgumentType.getString(ctx, "username");
									return removeWhitelist(ctx.getSource(), username);
								})))
				.then(Commands.literal("list")
						.executes(ctx -> listWhitelist(ctx.getSource())))
				.then(Commands.literal("reload")
						.executes(ctx -> reloadWhitelist(ctx.getSource()))));
	}

	private static int addWhitelist(CommandSourceStack source, String rawUsername) {
		MinecraftServer server = source.getServer();
		if (server == null) return 0;

		// Calculate exact offline UUID based on rawUsername (preserving exact case)
		UUID offlineUuid = UUIDUtil.createOfflinePlayerUUID(rawUsername);
		NameAndId nameAndId = new NameAndId(offlineUuid, rawUsername);

		UserWhiteList whitelist = server.getPlayerList().getWhiteList();
		UserWhiteListEntry entry = new UserWhiteListEntry(nameAndId);

		// Add to server UserWhiteList
		whitelist.add(entry);
		try {
			whitelist.save();
		} catch (Exception e) {
			source.sendFailure(Component.literal("§cบันทึก whitelist.json ไม่สำเร็จ: " + e.getMessage()));
			return 0;
		}

		// Also save exact case to config/timesmp/twhite.txt for persistence and custom tracking
		saveToTWhiteFile(rawUsername, true);

		source.sendSuccess(() -> Component.literal(String.format("§a[TWhite] เพิ่ม §e%s §a(UUID เถื่อน: §7%s§a) เข้า Whitelist เรียบร้อยแล้ว (คงตัวพิมพ์ตามที่พิมพ์)", rawUsername, offlineUuid)), true);
		return 1;
	}

	private static int removeWhitelist(CommandSourceStack source, String rawUsername) {
		MinecraftServer server = source.getServer();
		if (server == null) return 0;

		UUID offlineUuid = UUIDUtil.createOfflinePlayerUUID(rawUsername);
		NameAndId nameAndId = new NameAndId(offlineUuid, rawUsername);

		UserWhiteList whitelist = server.getPlayerList().getWhiteList();
		whitelist.remove(nameAndId);
		try {
			whitelist.save();
		} catch (Exception e) {
			source.sendFailure(Component.literal("§cบันทึก whitelist.json ไม่สำเร็จ: " + e.getMessage()));
			return 0;
		}

		saveToTWhiteFile(rawUsername, false);

		source.sendSuccess(() -> Component.literal(String.format("§c[TWhite] ลบ §e%s §cออกจาก Whitelist เรียบร้อยแล้ว", rawUsername)), true);
		return 1;
	}

	private static int listWhitelist(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		if (server == null) return 0;

		String[] names = server.getPlayerList().getWhiteListNames();
		if (names.length == 0) {
			source.sendSuccess(() -> Component.literal("§7[TWhite] ไม่มีรายชื่อใน Whitelist"), false);
			return 1;
		}

		source.sendSuccess(() -> Component.literal("§6[TWhite] รายชื่อใน Whitelist (" + names.length + " คน): §e" + String.join("§7, §e", names)), false);
		return 1;
	}

	private static int reloadWhitelist(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		if (server == null) return 0;

		server.getPlayerList().reloadWhiteList();
		source.sendSuccess(() -> Component.literal("§a[TWhite] โหลด Whitelist ใหม่เรียบร้อยแล้ว"), true);
		return 1;
	}

	private static void saveToTWhiteFile(String username, boolean add) {
		try {
			File dir = new File("config/timesmp");
			if (!dir.exists()) dir.mkdirs();
			File file = new File(dir, "twhite.txt");

			Set<String> set = new LinkedHashSet<>();
			if (file.exists()) {
				try (Scanner scanner = new Scanner(file)) {
					while (scanner.hasNextLine()) {
						String line = scanner.nextLine().trim();
						if (!line.isEmpty()) set.add(line);
					}
				}
			}

			if (add) {
				set.add(username);
			} else {
				set.remove(username);
			}

			try (FileWriter fw = new FileWriter(file, false)) {
				for (String name : set) {
					fw.write(name + "\n");
				}
			}
		} catch (Exception ignored) {}
	}
}
