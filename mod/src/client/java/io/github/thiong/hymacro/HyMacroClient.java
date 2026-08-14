package io.github.thiong.hymacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: routes are built with commands, drawn in the world, and driven
 * from the tick loop.
 *
 * <p>The play and stop keys are polled from the window rather than registered as
 * bindings. A binding needs a category type whose shape changed in this
 * Minecraft version, and polling needs nothing but GLFW.
 */
public final class HyMacroClient implements ClientModInitializer, Commands.Host {
	public static final Logger LOGGER = LoggerFactory.getLogger("hymacro");

	private static final int PLAY_KEY = GLFW.GLFW_KEY_F9;
	private static final int STOP_KEY = GLFW.GLFW_KEY_F12;

	private boolean playWasDown;
	private boolean stopWasDown;

	private RouteBook book = new RouteBook();
	private RoutePlayer player;

	@Override
	public void onInitializeClient() {
		book = RouteBook.load();
		Commands.register(this);
		RouteView.register(this::route);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		LOGGER.info("HyMacro ready. Run /hymacro for the commands, F9 plays, F12 stops.");
		if (route() != null) {
			LOGGER.info("Macro '{}' has {} points", book.activeName(), route().waypoints.size());
		}
	}

	@Override
	public RouteBook book() {
		return book;
	}

	private Route route() {
		return book.active();
	}

	@Override
	public void play() {
		Minecraft client = Minecraft.getInstance();
		if (player != null) {
			stop();
			return;
		}

		Route route = route();
		if (route == null || route.isEmpty()) {
			LOGGER.warn("No macro to play. Build one with /hymacro");
			return;
		}
		if (client.player == null) {
			return;
		}
		player = new RoutePlayer(client, route);
		LOGGER.info("Following '{}', {} points", book.activeName(), route.waypoints.size());
	}

	@Override
	public void stop() {
		if (player == null) {
			return;
		}
		player.stop();
		player = null;
		LOGGER.info("Route stopped");
	}

	private void onTick(Minecraft client) {
		if (pressed(PLAY_KEY, playWasDown)) {
			play();
		}
		if (pressed(STOP_KEY, stopWasDown)) {
			stop();
		}
		playWasDown = Keys.isKeyDown(PLAY_KEY);
		stopWasDown = Keys.isKeyDown(STOP_KEY);

		if (player != null) {
			player.tick();
			if (player.isFinished()) {
				player = null;
			}
		}
	}

	/** True only on the tick the key goes down, so holding it does not repeat. */
	private boolean pressed(int code, boolean wasDown) {
		return Keys.isKeyDown(code) && !wasDown;
	}
}
