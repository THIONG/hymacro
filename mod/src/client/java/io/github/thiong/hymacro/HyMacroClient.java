package io.github.thiong.hymacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: watches a handful of keys and advances whatever is running.
 *
 * <p>The keys are polled from the window rather than registered as bindings. A
 * binding needs a category type whose shape changed in this Minecraft version,
 * and polling needs nothing but GLFW.
 */
public final class HyMacroClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("hymacro");

	private static final int RECORD_KEY = GLFW.GLFW_KEY_F6;
	private static final int MARK_KEY = GLFW.GLFW_KEY_F7;
	private static final int PLAY_KEY = GLFW.GLFW_KEY_F9;
	private static final int STOP_KEY = GLFW.GLFW_KEY_F12;

	private final Recorder recorder = new Recorder();
	private boolean recordWasDown;
	private boolean markWasDown;
	private boolean playWasDown;
	private boolean stopWasDown;

	private Route route = new Route();
	private RoutePlayer player;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		route = Route.load();
		LOGGER.info("HyMacro ready: F6 records, F7 marks a point, F9 plays, F12 stops");
		if (!route.isEmpty()) {
			LOGGER.info("Loaded a route with {} waypoints", route.waypoints.size());
		}
	}

	private void onTick(Minecraft client) {
		if (pressed(RECORD_KEY, recordWasDown)) {
			toggleRecording(client);
		}
		if (pressed(MARK_KEY, markWasDown)) {
			mark(client);
		}
		if (pressed(PLAY_KEY, playWasDown)) {
			togglePlayback(client);
		}
		if (pressed(STOP_KEY, stopWasDown)) {
			stopEverything();
		}
		recordWasDown = Keys.isKeyDown(RECORD_KEY);
		markWasDown = Keys.isKeyDown(MARK_KEY);
		playWasDown = Keys.isKeyDown(PLAY_KEY);
		stopWasDown = Keys.isKeyDown(STOP_KEY);

		recorder.tick(client);

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

	private void toggleRecording(Minecraft client) {
		if (recorder.isRecording()) {
			Route recorded = recorder.finish(route);
			if (recorded == null) {
				LOGGER.warn("Recording discarded: mark at least two points with F7");
				return;
			}
			route = recorded;
			LOGGER.info("Recorded {} waypoints, saved to {}",
				route.waypoints.size(), Route.path());
			return;
		}

		if (player != null) {
			stopEverything();
		}
		recorder.begin();
		LOGGER.info("Recording. Walk the route, press F7 at each point, F6 to finish");
	}

	private void mark(Minecraft client) {
		if (!recorder.isRecording()) {
			return;
		}
		recorder.mark(client);
		LOGGER.info("Marked point {}", recorder.markedCount());
	}

	private void togglePlayback(Minecraft client) {
		if (player != null) {
			stopEverything();
			return;
		}
		if (recorder.isRecording()) {
			LOGGER.warn("Still recording. Press F6 to finish first");
			return;
		}
		if (route.isEmpty()) {
			LOGGER.warn("No route recorded yet. Press F6 to record one");
			return;
		}
		if (client.player == null) {
			return;
		}
		player = new RoutePlayer(client, route);
		LOGGER.info("Playing a route of {} waypoints", route.waypoints.size());
	}

	private void stopEverything() {
		if (recorder.isRecording()) {
			recorder.cancel();
			LOGGER.info("Recording cancelled");
		}
		if (player != null) {
			player.stop();
			player = null;
			LOGGER.info("Route stopped");
		}
	}
}
