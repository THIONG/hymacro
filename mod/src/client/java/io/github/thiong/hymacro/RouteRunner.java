package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The serpentine route, advanced one client tick at a time.
 *
 * <p>Nothing here simulates operating system input. It sets the state of keys
 * the game already reads every tick, so the packets leaving the client are the
 * ones a player produces and window focus never enters into it.
 *
 * <p>Ticks run at a fixed twenty per second regardless of frame rate, so a two
 * minute leg is exactly 2400 ticks and cannot drift with performance.
 */
public final class RouteRunner {
	private enum Phase { OUTWARD, STEP_AFTER_OUTWARD, RETURN, STEP_AFTER_RETURN, WARP }

	private final Minecraft client;
	private final MacroConfig config;

	private final List<Integer> held = new ArrayList<>();
	private Phase phase;
	private int ticksLeft;
	private int lapsDone;
	private boolean finished;

	public RouteRunner(Minecraft client, MacroConfig config) {
		this.client = client;
		this.config = config;
		enter(Phase.OUTWARD);
	}

	public boolean isFinished() {
		return finished;
	}

	public void tick() {
		if (finished) {
			return;
		}
		if (client.player == null) {
			stop();
			return;
		}

		ticksLeft--;
		if (ticksLeft > 0) {
			return;
		}

		release();
		switch (phase) {
			case OUTWARD -> enter(Phase.STEP_AFTER_OUTWARD);
			case STEP_AFTER_OUTWARD -> enter(Phase.RETURN);
			case RETURN -> enter(Phase.STEP_AFTER_RETURN);
			case STEP_AFTER_RETURN -> {
				lapsDone++;
				if (lapsDone >= config.routesPerWarp) {
					enter(Phase.WARP);
				} else {
					enter(Phase.OUTWARD);
				}
			}
			case WARP -> {
				sendWarp();
				lapsDone = 0;
				enter(Phase.OUTWARD);
			}
		}
	}

	/** Releases everything. A stop must never leave a key stuck down. */
	public void stop() {
		release();
		finished = true;
	}

	private void enter(Phase next) {
		phase = next;
		switch (next) {
			case OUTWARD -> {
				ticksLeft = MacroConfig.toTicks(config.forwardSeconds);
				hold(config.keys.get(0));
			}
			case STEP_AFTER_OUTWARD -> {
				ticksLeft = MacroConfig.toTicks(config.stepSeconds);
				hold(config.keys.get(1));
			}
			case RETURN -> {
				ticksLeft = MacroConfig.toTicks(config.returnSeconds);
				hold(config.keys.get(2));
			}
			case STEP_AFTER_RETURN -> {
				ticksLeft = MacroConfig.toTicks(config.stepSeconds);
				hold(config.keys.get(3));
			}
			case WARP -> ticksLeft = MacroConfig.toTicks(1.0);
		}
	}

	private void hold(String name) {
		int code = Keys.codeFor(name);
		if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) {
			HyMacroClient.LOGGER.warn("Unknown movement key {}", name);
		} else {
			Keys.set(code, true);
			held.add(code);
		}
		Keys.set(Keys.ATTACK, true);
		held.add(Keys.ATTACK);
	}

	private void release() {
		for (int code : held) {
			Keys.set(code, false);
		}
		held.clear();
	}

	private void sendWarp() {
		if (client.player == null) {
			return;
		}
		String command = config.warpCommand.startsWith("/")
			? config.warpCommand.substring(1)
			: config.warpCommand;
		client.player.connection.sendCommand(command);
	}
}
