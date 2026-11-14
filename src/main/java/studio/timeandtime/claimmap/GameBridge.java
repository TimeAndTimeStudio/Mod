package studio.timeandtime.claimmap;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a task on the Minecraft server thread and waits for its result (R3).
 * Callers must not hold {@link Market#LOCK} while calling this (deadlock).
 */
public final class GameBridge {
	public static final long DEFAULT_TIMEOUT_MS = 5000L;

	/** Thrown when the server is stopping, gone, or did not answer in time. */
	public static final class GameUnavailableException extends RuntimeException {
		public GameUnavailableException(String message) {
			super(message);
		}

		public GameUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private GameBridge() {
	}

	public static <T> T call(MinecraftServer server, Supplier<T> task, long timeoutMs)
			throws GameUnavailableException {
		if (server == null) throw new GameUnavailableException("server not running");
		if (task == null) throw new GameUnavailableException("no task");
		if (server.isSameThread()) return task.get();

		long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
		CompletableFuture<T> future;
		try {
			future = server.submit(task);
		} catch (RuntimeException e) {
			throw new GameUnavailableException("could not schedule task", e);
		}
		try {
			return future.get(timeout, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			throw new GameUnavailableException("server thread did not answer in time");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw new GameUnavailableException("interrupted while waiting for the server thread");
		} catch (ExecutionException e) {
			throw new GameUnavailableException("task failed on the server thread", e.getCause());
		}
	}
}
