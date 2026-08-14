package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The serpentine route, driven one client tick at a time.
 *
 * <p>Nothing here simulates operating system input. It sets the state of the key
 * mappings the game already reads every tick, so the packets leaving the client
 * are the ones a player produces, and window focus never enters into it.
 */
public final class RouteRunner {
	private enum Phase { OUTWARD, STEP_AFTER_OUTWARD, RETURN, STEP_AFTER_RETURN, WARP }

	private final Minecraft client;
	private final MacroConfig config;

	private final List<KeyMapping> held = new ArrayList<>();
	private Phase phase = Phase.OUTWARD;
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
				attack();
			}
			case STEP_AFTER_OUTWARD, STEP_AFTER_RETURN -> {
				ticksLeft = MacroConfig.toTicks(config.stepSeconds);
				hold(config.keys.get(next == Phase.STEP_AFTER_OUTWARD ? 1 : 3));
				attack();
			}
			case RETURN -> {
				ticksLeft = MacroConfig.toTicks(config.returnSeconds);
				hold(config.keys.get(2));
				attack();
			}
			case WARP -> ticksLeft = MacroConfig.toTicks(1.0);
		}
	}

	private void hold(String name) {
		KeyMapping mapping = mappingFor(name);
		if (mapping == null) {
			HyMacroClient.LOGGER.warn("Unknown movement key {}", name);
			return;
		}
		KeyMapping.set(mapping.getKey(), true);
		held.add(mapping);
	}

	private void attack() {
		KeyMapping attack = client.options.keyAttack;
		KeyMapping.set(attack.getKey(), true);
		held.add(attack);
	}

	private void release() {
		for (KeyMapping mapping : held) {
			KeyMapping.set(mapping.getKey(), false);
		}
		held.clear();
	}

	private KeyMapping mappingFor(String name) {
		return switch (name.toLowerCase()) {
			case "w" -> client.options.keyUp;
			case "s" -> client.options.keyDown;
			case "a" -> client.options.keyLeft;
			case "d" -> client.options.keyRight;
			default -> null;
		};
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
