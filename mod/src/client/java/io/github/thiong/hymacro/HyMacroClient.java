package io.github.thiong.hymacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: a route is built with commands and driven from the tick loop.
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
	private int tick;

	private Route route = new Route();
	private RoutePlayer player;

	@Override
	public void onInitializeClient() {
		route = Route.load();
		Commands.register(this);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		LOGGER.info("HyMacro ready. Build a route with /hymacro, F9 plays it, F12 stops.");
		if (!route.isEmpty()) {
			LOGGER.info("Loaded a route of {} points", route.waypoints.size());
		}
	}

	@Override
	public Route route() {
		return route;
	}

	@Override
	public void replaceRoute(Route replacement) {
		route = replacement;
	}

	@Override
	public void play() {
		Minecraft client = Minecraft.getInstance();
		if (player != null) {
			stop();
			return;
		}
		if (route.isEmpty()) {
			LOGGER.warn("No route yet. Stand somewhere and use /hymacro point");
			return;
		}
		if (client.player == null) {
			return;
		}
		player = new RoutePlayer(client, route);
		LOGGER.info("Following a route of {} points", route.waypoints.size());
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

		tick++;
		if (route.showMarkers) {
			Markers.draw(client, route.waypoints, tick);
		}

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
