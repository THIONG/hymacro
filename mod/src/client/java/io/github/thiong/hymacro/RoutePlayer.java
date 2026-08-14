package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Walks a recorded route, one client tick at a time.
 *
 * <p>A leg ends on arrival, not after a fixed time. That is what removes the
 * calibration: a route cannot drift, because being short of the target simply
 * means keeping the keys held a little longer.
 *
 * <p>The timeout is the safety net. Something that never arrives, because the
 * player is stuck against a block or was teleported away, must not hold a key
 * down forever.
 */
public final class RoutePlayer {
	private final Minecraft client;
	private final Route route;
	private final List<String> held = new ArrayList<>();

	private int index;
	private int lapsDone;
	private int ticksInLeg;
	private int timeoutTicks;
	private boolean finished;
	private boolean warping;

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

		ticksInLeg++;

		if (warping) {
			if (ticksInLeg >= 20) {
				warping = false;
				index = 0;
				beginLeg();
			}
			return;
		}

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
		release();
		finished = true;
	}

	private boolean arrived() {
		Route.Waypoint target = route.waypoints.get(index);
		double dx = client.player.getX() - target.x;
		double dz = client.player.getZ() - target.z;
		return dx * dx + dz * dz <= route.arrivalRadius * route.arrivalRadius;
	}

	private void advance() {
		release();
		index++;
		if (index < route.waypoints.size()) {
			beginLeg();
			return;
		}

		lapsDone++;
		if (lapsDone >= route.lapsPerWarp && !route.warpCommand.isBlank()) {
			lapsDone = 0;
			sendWarp();
			warping = true;
			ticksInLeg = 0;
			return;
		}
		index = 0;
		beginLeg();
	}

	private void beginLeg() {
		ticksInLeg = 0;
		if (route.waypoints.isEmpty()) {
			stop();
			return;
		}
		for (String name : route.waypoints.get(index).keys) {
			if (Keys.isKnown(name)) {
				Keys.set(name, true);
				held.add(name);
			}
		}
	}

	private void release() {
		for (String name : held) {
			Keys.set(name, false);
		}
		held.clear();
	}

	private void sendWarp() {
		if (client.player == null) {
			return;
		}
		String command = route.warpCommand.startsWith("/")
			? route.warpCommand.substring(1)
			: route.warpCommand;
		client.player.connection.sendCommand(command);
	}
}
