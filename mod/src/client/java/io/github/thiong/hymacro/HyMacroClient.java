package io.github.thiong.hymacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: watches two keys and advances the route each tick.
 *
 * <p>The keys are polled from the window rather than registered as bindings.
 * That gives up the controls screen, but a binding needs a category type whose
 * shape changed in this Minecraft version, and polling needs nothing but GLFW.
 */
public final class HyMacroClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("hymacro");

	private static final int START_KEY = GLFW.GLFW_KEY_F9;
	private static final int STOP_KEY = GLFW.GLFW_KEY_F12;

	private boolean startWasDown;
	private boolean stopWasDown;
	private RouteRunner runner;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		LOGGER.info("HyMacro ready: F9 starts and stops, F12 stops");
	}

	private void onTick(Minecraft client) {
		if (pressed(START_KEY, startWasDown)) {
			toggle(client);
		}
		if (pressed(STOP_KEY, stopWasDown)) {
			stop();
		}
		startWasDown = isDown(START_KEY);
		stopWasDown = isDown(STOP_KEY);

		if (runner != null) {
			runner.tick();
			if (runner.isFinished()) {
				runner = null;
			}
		}
	}

	/**
	 * Asks GLFW for the window rather than Minecraft.
	 *
	 * <p>The client tick runs on the render thread, which is the thread holding
	 * the game's GL context, so this is the game window. Going through GLFW
	 * keeps window handling out of the Minecraft API, whose accessor for the
	 * handle is not named the same in this version.
	 */
	private boolean isDown(int code) {
		long window = GLFW.glfwGetCurrentContext();
		return window != 0L && GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
	}

	/** True only on the tick the key goes down, so holding it does not repeat. */
	private boolean pressed(int code, boolean wasDown) {
		return isDown(code) && !wasDown;
	}

	private void toggle(Minecraft client) {
		if (runner != null) {
			stop();
			return;
		}
		if (client.player == null) {
			return;
		}
		runner = new RouteRunner(client, MacroConfig.load());
		LOGGER.info("Route running");
	}

	private void stop() {
		if (runner == null) {
			return;
		}
		runner.stop();
		runner = null;
		LOGGER.info("Route stopped");
	}
}
