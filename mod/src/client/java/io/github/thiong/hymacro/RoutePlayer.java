package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Walks a recorded route, one client tick at a time.
 *
 * <p>A leg ends on arrival, not after a fixed time. That is what removes the
 * calibration: a route cannot drift, because falling short simply means holding
 * the keys a little longer.
 *
 * <p>The look direction is part of a leg, not decoration. On a wall of crops the
 * player faces the wall while moving sideways, so a camera that wanders by a
 * degree ruins the run just as surely as a mistimed key.
 *
 * <p>The timeout is the safety net. Something that never arrives, because the
 * player is stuck against a block or was teleported away, must not hold a key
 * down forever.
 */
public final class RoutePlayer {
	private final Minecraft client;
	private final Route route;
	private final List<String> held = new ArrayList<>();

	private static final int PAUSE_AFTER_SEND = 20;

	/** Closer than this along an axis and the key would only judder. */
	private static final double DEADZONE = 0.2;
	private static final String[] MOVEMENT = {"w", "a", "s", "d"};

	private int index;
	private int ticksInLeg;
	private int pausing;
	private final int timeoutTicks;
	private boolean finished;

	public RoutePlayer(Minecraft client, Route route) {
		this.client = client;
		this.route = route;
		this.timeoutTicks = Math.max(20, (int) Math.round(route.segmentTimeoutSeconds * 20.0));
		beginLeg();
	}

	public boolean isFinished() {
		return finished;
	}

	public int waypointNumber() {
		return index + 1;
	}

	public void tick() {
		if (finished) {
			return;
		}
		if (client.player == null) {
			stop();
			return;
		}

		if (pausing > 0) {
			pausing--;
			if (pausing == 0) {
				beginLeg();
			}
			return;
		}

		ticksInLeg++;
		steer();
		spam();

		if (arrived() || ticksInLeg >= timeoutTicks) {
			if (ticksInLeg >= timeoutTicks) {
				HyMacroClient.LOGGER.warn(
					"Waypoint {} not reached within {}s, moving on",
					waypointNumber(), route.segmentTimeoutSeconds);
			}
			advance();
		}
	}

	/** Releases everything. A stop must never leave a key held down. */
	public void stop() {
		pausing = 0;
		release();
		finished = true;
	}

	private boolean arrived() {
		Route.Waypoint target = route.waypoints.get(index);
		double dx = client.player.getX() - target.x;
		double dz = client.player.getZ() - target.z;
		return dx * dx + dz * dz <= route.arrivalRadius * route.arrivalRadius;
	}

	/**
	 * Presses whatever gets the player closer, without turning to face the way
	 * they are going.
	 *
	 * <p>Holding a fixed key and hoping is fine until something knocks you off
	 * line: from then on the key points somewhere the destination is not, and the
	 * leg arrives nowhere until it times out. Working out the keys each tick from
	 * where the point actually is makes a leg correct itself instead.
	 *
	 * <p>The look direction is left alone on purpose. On a wall of crops the
	 * player faces the wall and travels sideways, so turning to face the way they
	 * are walking would point the tool at nothing.
	 */
	private void steer() {
		Route.Waypoint target = route.waypoints.get(index);
		if (!target.walk) {
			return;
		}

		double dx = target.x - client.player.getX();
		double dz = target.z - client.player.getZ();
		double facing = Math.toRadians(target.yaw);
		double ahead = dx * -Math.sin(facing) + dz * Math.cos(facing);
		double side = dx * -Math.cos(facing) + dz * -Math.sin(facing);

		Keys.set("w", ahead > DEADZONE);
		Keys.set("s", ahead < -DEADZONE);
		Keys.set("d", side > DEADZONE);
		Keys.set("a", side < -DEADZONE);
	}

	/** Toggles the keys that were recorded as repeated clicks rather than holds. */
	private void spam() {
		for (Route.Action action : route.waypoints.get(index).actions) {
			if (!action.isSpam()) {
				continue;
			}
			boolean down = (ticksInLeg / action.intervalTicks) % 2 == 0;
			Keys.set(action.key, down);
		}
	}

	/**
	 * Leaves the point just reached and starts for the next one, wrapping round
	 * at the end.
	 *
	 * <p>Anything the point sends goes out here, followed by a pause. A warp
	 * needs a moment to land, and starting the next leg mid teleport would hold
	 * keys wherever it came out.
	 */
	private void advance() {
		release();
		Route.Waypoint reached = route.waypoints.get(index);
		index = (index + 1) % route.waypoints.size();

		if (reached.sends()) {
			send(reached.send);
			pausing = PAUSE_AFTER_SEND;
			return;
		}
		beginLeg();
	}

	private void beginLeg() {
		ticksInLeg = 0;
		if (route.waypoints.isEmpty()) {
			stop();
			return;
		}

		Route.Waypoint target = route.waypoints.get(index);
		if (client.player != null) {
			client.player.setYRot(target.yaw);
			client.player.setXRot(target.pitch);
		}

		for (Route.Action action : target.actions) {
			if (!Keys.isKnown(action.key)) {
				continue;
			}
			if (target.walk && isMovement(action.key)) {
				continue;
			}
			if (action.isSpam()) {
				held.add(action.key);
			} else {
				Keys.set(action.key, true);
				held.add(action.key);
			}
		}
	}

	private void release() {
		for (String name : held) {
			Keys.set(name, false);
		}
		held.clear();
		for (String name : MOVEMENT) {
			Keys.set(name, false);
		}
	}

	private static boolean isMovement(String key) {
		for (String name : MOVEMENT) {
			if (name.equals(key)) {
				return true;
			}
		}
		return false;
	}

	/** A leading slash means a command; anything else is said out loud. */
	private void send(String text) {
		if (client.player == null) {
			return;
		}
		if (text.startsWith("/")) {
			client.player.connection.sendCommand(text.substring(1));
		} else {
			client.player.connection.sendChat(text);
		}
	}
}
