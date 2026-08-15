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

	/** F10 is bound to nothing in the game, and sits next to the other two. */
	private static final int HUNT_KEY = GLFW.GLFW_KEY_F10;

	/** How far from point 1 counts as being somewhere else entirely. */
	private static final double START_TOLERANCE = 10.0;
	private static final int CONFIRM_TICKS = 100;

	private boolean playWasDown;
	private boolean stopWasDown;
	private boolean huntWasDown;
	private int confirmTicks;

	/** True while the rule is already satisfied, so it fires on the way past. */
	/** Rules are checked four times a second: none of them changes faster. */
	private static final int RULE_EVERY = 5;

	private int ruleTick;
	private boolean ruleFired;

	/** The macro is paused for a hunt and wants to be started again after. */
	private boolean resumeAfterHunt;

	/** The macro is waiting to be back where it belongs. */
	private boolean resumeWhenBack;

	private RouteBook book = new RouteBook();
	private RoutePlayer player;
	private final Pests pests = new Pests();
	private final PestHunter hunter = new PestHunter(pests);

	@Override
	public void onInitializeClient() {
		book = RouteBook.load();
		Commands.register(this);
		RouteView.register(this::route);
		PestView.register(() -> book.pests ? pests : null);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		LOGGER.info("HyMacro ready. Run /hymacro for the commands. "
			+ "F9 plays, F10 hunts pests, F12 stops both.");
		if (route() != null) {
			LOGGER.info("Macro '{}' has {} points", book.activeName(), route().waypoints.size());
		}
	}

	@Override
	public RouteBook book() {
		return book;
	}

	@Override
	public Pests pests() {
		return pests;
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

	/**
	 * Starts the macro again after something interrupted it, from the beginning.
	 *
	 * <p>No check of where you are, unlike pressing play. A hunt ends wherever
	 * the last pest was, which is exactly the case the check exists to catch, and
	 * refusing to carry on there would leave the macro stopped in a field. Leg 1
	 * is the way back to point 1, which is what it is for.
	 */
	private void resume() {
		Minecraft client = Minecraft.getInstance();
		Route route = route();
		if (route == null || route.isEmpty() || client.player == null) {
			return;
		}
		player = new RoutePlayer(client, route);
		Chat.client("Back to '" + book.activeName() + "'.", false);
	}

	/**
	 * Watches whatever the macro asked to be watched.
	 *
	 * <p>Only while one is running, and only on the way past the number rather
	 * than for as long as it is above it: a rule that fired every tick would stop
	 * and start the macro faster than it could take a step.
	 */
	/**
	 * Notices being somewhere the macro does not belong, and getting back.
	 *
	 * <p>A restart puts you in the Hub with a macro still walking a route whose
	 * points are on another island: it is the one interruption that cannot be
	 * ridden out. Not knowing where you are is a third answer and treated as
	 * neither, since the board is blank for a moment between worlds and reading
	 * that as elsewhere would fire on every loading screen.
	 */
	private void watchPlace(Minecraft client, Route route) {
		String here = Skyblock.location(client);
		if (here == null) {
			return;
		}

		if (here.equalsIgnoreCase(route.when.place)) {
			if (resumeWhenBack && player == null) {
				resumeWhenBack = false;
				ruleFired = false;
				Chat.client("Back on " + here + ".", false);
				resume();
			}
			ruleFired = false;
			return;
		}
		if (ruleFired) {
			return;
		}
		ruleFired = true;

		Chat.client("This is " + here + ", not " + route.when.place + ".", true);
		stop();
		if (Route.When.SEND.equals(route.when.then)) {
			RoutePlayer.sendChat(client, route.when.text);
			resumeWhenBack = true;
			Chat.clientNote("Sent " + route.when.text + ". It carries on once back.");
		}
	}

	private void watchRule(Minecraft client) {
		Route route = route();
		if (route == null || route.when == null) {
			ruleFired = false;
			return;
		}

		// What the server says is alive across the Garden, not only what is close
		// enough to be an entity. A rule that counted the ones in range would
		// almost never fire, since they are rarely on the plot being farmed.
		if (route.when.watchesPlace()) {
			watchPlace(client, route);
			return;
		}

		int seen = Route.When.PESTS.equals(route.when.watch)
			? Math.max(pests.count(), Pests.aliveEverywhere(client))
			: 0;
		if (seen < route.when.atLeast) {
			ruleFired = false;
			return;
		}
		if (ruleFired || player == null) {
			return;
		}
		ruleFired = true;

		switch (route.when.then) {
			case Route.When.HUNT -> {
				Chat.client(seen + " pests. Pausing the macro to deal with them.", false);
				stop();
				hunter.start();
				resumeAfterHunt = true;
			}
			case Route.When.SEND -> {
				Chat.client(seen + " pests, sending " + route.when.text, false);
				RoutePlayer.sendChat(client, route.when.text);
			}
			default -> {
				Chat.client(seen + " pests. Stopping, as this macro asks.", true);
				stop();
			}
		}
	}

	/**
	 * Hunting and running a macro are the same two hands.
	 *
	 * <p>Both work the movement keys and the right button every tick, so the two
	 * of them at once is a fight neither wins. Starting a hunt stops the macro
	 * and says so, rather than producing a player that walks nowhere in
	 * particular.
	 */
	@Override
	public void hunt() {
		if (!hunter.isOn() && player != null) {
			stop();
			Chat.clientNote("The macro was stopped: it and the hunt cannot both hold the keys.");
		}
		hunter.toggle();
	}

	@Override
	public PestHunter hunter() {
		return hunter;
	}

	@Override
	public void stop() {
		confirmTicks = 0;
		resumeAfterHunt = false;
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
			hunter.stop("Stopped hunting.");
		}
		if (pressed(HUNT_KEY, huntWasDown)) {
			hunt();
		}
		playWasDown = Keys.isKeyDown(PLAY_KEY);
		stopWasDown = Keys.isKeyDown(STOP_KEY);
		huntWasDown = Keys.isKeyDown(HUNT_KEY);
		if (confirmTicks > 0) {
			confirmTicks--;
		}

		// Looking for pests is not part of running a macro: they eat the plot
		// whether or not anything is walking it, and the mark is as useful to
		// somebody farming by hand.
		// The hunt is fed by the same search that draws them, so turning the
		// drawing off must not leave it hunting nothing.
		Route watching = route();
		boolean ruleNeedsThem = watching != null && watching.when != null;
		if (book.pests || hunter.isOn() || ruleNeedsThem) {
			pests.tick(client);
		} else {
			pests.forget();
		}
		hunter.tick(client);

		// The hunt has run out of pests, so the macro that stood aside for it
		// gets its keys back.
		if (resumeAfterHunt && hunter.isIdle()) {
			resumeAfterHunt = false;
			hunter.stop(null);
			resume();
		}
		if (++ruleTick >= RULE_EVERY) {
			ruleTick = 0;
			watchRule(client);
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
