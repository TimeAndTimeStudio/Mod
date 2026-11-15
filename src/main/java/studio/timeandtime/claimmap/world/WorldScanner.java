package studio.timeandtime.claimmap.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds direct child folders of {@code worlds/} and checks that each one looks
 * like a complete Minecraft save before it is registered.
 *
 * <p>Nothing here creates, deletes or writes world files. Discovery only reads
 * {@code level.dat}, so an invalid or partially copied folder is never touched.
 */
public final class WorldScanner {
	/** Safe subset of names that can be used as a Brigadier literal. */
	private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

	public record Candidate(String folderName, String name, Path folder, boolean valid, String reason) {
	}

	public List<Candidate> scan(Path worldsDirectory) {
		List<Candidate> result = new ArrayList<>();
		if (!Files.isDirectory(worldsDirectory, LinkOption.NOFOLLOW_LINKS)) {
			return result;
		}
		try (DirectoryStream<Path> children = Files.newDirectoryStream(worldsDirectory)) {
			for (Path child : children) {
				String folderName = child.getFileName().toString();
				if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String normalized = folderName.toLowerCase(Locale.ROOT);
				if (!SAFE_NAME.matcher(normalized).matches()) {
					result.add(new Candidate(folderName, normalized, child, false,
							"ชื่อโฟลเดอร์ใช้เป็นคำสั่งไม่ได้ (ต้องเป็น a-z, 0-9, _ หรือ - และยาวไม่เกิน 32)"));
					continue;
				}
				String invalid = validate(child);
				result.add(new Candidate(folderName, normalized, child, invalid == null, invalid));
			}
		} catch (IOException e) {
			result.add(new Candidate("", "", worldsDirectory, false, "อ่านโฟลเดอร์ worlds ไม่สำเร็จ: " + e.getMessage()));
		}
		result.sort(Comparator.comparing(c -> c.folderName(), String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	/**
	 * @return null when the folder is a valid save, otherwise a short reason.
	 */
	public String validate(Path folder) {
		if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
			return "ไม่ใช่โฟลเดอร์";
		}
		Path levelDat = folder.resolve("level.dat");
		if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)) {
			return "ไม่พบ level.dat";
		}
		try {
			CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
			if (root == null || !root.contains("Data")) {
				return "level.dat ไม่มีข้อมูล Data";
			}
			CompoundTag data = root.getCompoundOrEmpty("Data");
			int version = data.getIntOr("DataVersion", 0);
			if (version <= 0) {
				return "level.dat ไม่มี DataVersion";
			}
			return null;
		} catch (Exception e) {
			return "อ่าน level.dat ไม่สำเร็จ: " + e.getMessage();
		}
	}
}
