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

	/** How far from point 1 counts as being somewhere else entirely. */
	private static final double START_TOLERANCE = 10.0;
	private static final int CONFIRM_TICKS = 100;

	private boolean playWasDown;
	private boolean stopWasDown;
	private int confirmTicks;

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
			Chat.client("No macro to play. Build one with /hymacro", true);
			return;
		}
		if (client.player == null) {
			return;
		}

		double away = distanceToStart(client, route);
		if (away > START_TOLERANCE && confirmTicks == 0) {
			confirmTicks = CONFIRM_TICKS;
			Chat.client("You are " + Math.round(away) + " blocks from point 1 of '"
				+ book.activeName() + "'.", true);
			Chat.clientNote("Walk there, or press F9 again to start anyway.");
			return;
		}

		confirmTicks = 0;
		player = new RoutePlayer(client, route);
		Chat.client("Following '" + book.activeName() + "', "
			+ route.waypoints.size() + " points.", false);
	}

	@Override
	public void stop() {
		confirmTicks = 0;
		if (player == null) {
			return;
		}
		player.stop();
		player = null;
		Chat.client("Stopped.", false);
	}

	/**
	 * How far the player is from where the macro begins.
	 *
	 * <p>Starting anywhere else is almost always a macro left selected from
	 * another plot: the first leg holds keys trying to reach a point it is not
	 * walking towards, and arrives nowhere until it times out.
	 */
	private static double distanceToStart(Minecraft client, Route route) {
		Route.Waypoint first = route.waypoints.get(0);
		double dx = client.player.getX() - first.x;
		double dz = client.player.getZ() - first.z;
		return Math.sqrt(dx * dx + dz * dz);
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
		if (confirmTicks > 0) {
			confirmTicks--;
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
