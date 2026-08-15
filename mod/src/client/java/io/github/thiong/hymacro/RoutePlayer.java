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

	/** How far off the line before it is worth steering back. */
	private static final double DRIFT_ALLOWED = 0.35;

	/** Wandering further than this earns the full sideways correction. */
	private static final double DRIFT_FULL = 1.2;

	/** Rounds a wanted heading to the eight a keyboard can express. */
	private static final double PRESS_ABOVE = 0.38;

	private static final String[] MOVEMENT = {"w", "a", "s", "d"};

	/** Long enough for the game to count a press as a click. */
	private static final int CLICK_TICKS = 3;

	/** Getting this much closer counts as progress and clears the stall. */
	private static final double PROGRESS = 0.5;

	private int index;
	private int ticksInLeg;
	private int stalledTicks;
	private double closestYet;
	private double startX;
	private double startZ;
	private int pausing;
	private final int stallTicks;
	private boolean finished;

	public RoutePlayer(Minecraft client, Route route) {
		this.client = client;
		this.route = route;
		this.stallTicks = Math.max(40, (int) Math.round(route.stallSeconds * 20.0));
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
		work();

		if (arrived()) {
			advance();
			return;
		}

		// Giving up on a leg is about being stuck, not about being slow. A long
		// row honestly takes minutes; a player wedged against a block gets no
		// closer at all, however long you wait.
		double away = distanceToTarget();
		if (away < closestYet - PROGRESS) {
			closestYet = away;
			stalledTicks = 0;
		} else if (++stalledTicks >= stallTicks) {
			Chat.client("Point " + waypointNumber() + " got no closer for "
				+ Math.round(route.stallSeconds) + "s, skipping it.", true);
			Chat.clientNote("Something is in the way, or the point cannot be reached from here.");
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
		return distanceToTarget()
			<= route.waypoints.get(index).radiusOr(route.arrivalRadius);
	}

	private double distanceToTarget() {
		Route.Waypoint target = route.waypoints.get(index);
		double dx = client.player.getX() - target.x;
		double dz = client.player.getZ() - target.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Presses whatever gets the player closer, without turning to face the way
	 * they are going.
	 *
	 * <p>Holding a fixed key and hoping is fine until something knocks you off
	 * line: from then on the key points somewhere the destination is not, and the
	 * leg arrives nowhere until it times out. Working out the keys each tick makes
	 * a leg correct itself instead.
	 *
	 * <p>It steers by how far it has strayed from the line between the two points,
	 * not by where the point is from here. A keyboard can only express eight
	 * directions, so aiming straight at a point that is mostly sideways presses
	 * forward as well and leaves at forty five degrees, wandering off and coming
	 * back. Holding the line means that only happens by the width of the drift it
	 * allows.
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

		double lineX = target.x - startX;
		double lineZ = target.z - startZ;
		double span = Math.sqrt(lineX * lineX + lineZ * lineZ);
		if (span < 0.001) {
			return;
		}
		lineX /= span;
		lineZ /= span;

		// Signed distance from the line, measured across it.
		double offX = client.player.getX() - startX;
		double offZ = client.player.getZ() - startZ;
		double drift = offX * lineZ - offZ * lineX;

		// Along the line, plus as much of a sideways nudge as the drift earns.
		double correction = 0.0;
		if (Math.abs(drift) > DRIFT_ALLOWED) {
			correction = -Math.max(-1.0, Math.min(1.0, drift / DRIFT_FULL));
		}
		double wantX = lineX + lineZ * correction;
		double wantZ = lineZ - lineX * correction;
		double magnitude = Math.sqrt(wantX * wantX + wantZ * wantZ);
		wantX /= magnitude;
		wantZ /= magnitude;

		double facing = Math.toRadians(target.yaw);
		double ahead = wantX * -Math.sin(facing) + wantZ * Math.cos(facing);
		double side = wantX * -Math.cos(facing) + wantZ * -Math.sin(facing);

		Keys.set("w", ahead > PRESS_ABOVE);
		Keys.set("s", ahead < -PRESS_ABOVE);
		Keys.set("d", side > PRESS_ABOVE);
		Keys.set("a", side < -PRESS_ABOVE);
	}

	/**
	 * Works the keys the tick loop owns: the ones clicked repeatedly, and the
	 * ones clicked once as the leg begins.
	 */
	private void work() {
		for (Route.Action action : route.waypoints.get(index).actions) {
			if (action.isOnce()) {
				Keys.set(action.key, ticksInLeg <= CLICK_TICKS);
			} else if (action.isSpam()) {
				Keys.set(action.key, (ticksInLeg / action.intervalTicks) % 2 == 0);
			}
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
			startX = client.player.getX();
			startZ = client.player.getZ();
		}
		stalledTicks = 0;
		closestYet = client.player == null ? Double.MAX_VALUE : distanceToTarget();

		for (Route.Action action : target.actions) {
			if (!Keys.isKnown(action.key)) {
				continue;
			}
			if (target.walk && isMovement(action.key)) {
				continue;
			}
			if (action.isTimed()) {
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

	private void send(String text) {
		sendChat(client, text);
	}

	/** A leading slash means a command; anything else is said out loud. */
	static void sendChat(Minecraft client, String text) {
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
