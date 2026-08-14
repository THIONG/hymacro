package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Records a route by watching the player walk it.
 *
 * <p>Rather than being told what to do, it samples what is held while travelling
 * between the points that get marked, and works out on its own whether a key was
 * held down or clicked repeatedly. Counting the presses is what separates the
 * two: one long press is a hold, many short ones are spam, and the average gap
 * between them becomes the rate to replay it at.
 */
public final class Recorder {
	private static final double MOSTLY = 0.5;
	private static final int SPAM_THRESHOLD = 3;

	private final Route route = new Route();
	private final Map<String, Integer> ticksHeld = new HashMap<>();
	private final Map<String, Integer> presses = new HashMap<>();
	private final Map<String, Boolean> wasHeld = new HashMap<>();
	private int ticksInSegment;
	private boolean started;

	public boolean isRecording() {
		return started;
	}

	public int markedCount() {
		return route.waypoints.size();
	}

	public void begin() {
		route.waypoints.clear();
		resetSegment();
		started = true;
	}

	public void tick(Minecraft client) {
		if (!started || client.player == null) {
			return;
		}
		ticksInSegment++;
		for (String name : Keys.RECORDABLE) {
			boolean held = Keys.isHeld(name);
			if (held) {
				ticksHeld.merge(name, 1, Integer::sum);
				if (!wasHeld.getOrDefault(name, false)) {
					presses.merge(name, 1, Integer::sum);
				}
			}
			wasHeld.put(name, held);
		}
	}

	/** Marks where the player is standing, and which way they are looking. */
	public void mark(Minecraft client) {
		if (!started || client.player == null) {
			return;
		}

		List<Route.Action> actions = new ArrayList<>();
		for (String name : Keys.RECORDABLE) {
			int held = ticksHeld.getOrDefault(name, 0);
			int pressed = presses.getOrDefault(name, 0);
			if (held == 0 || ticksInSegment == 0) {
				continue;
			}
			if (pressed >= SPAM_THRESHOLD) {
				actions.add(new Route.Action(name, Route.SPAM, Math.max(1, ticksInSegment / pressed)));
			} else if (held >= ticksInSegment * MOSTLY) {
				actions.add(new Route.Action(name, Route.HOLD, 1));
			}
		}

		route.waypoints.add(new Route.Waypoint(
			client.player.getX(),
			client.player.getY(),
			client.player.getZ(),
			client.player.getYRot(),
			client.player.getXRot(),
			actions));
		resetSegment();
	}

	/** Ends the recording and returns the route, or null when too little was captured. */
	public Route finish(Route previous) {
		started = false;
		if (route.waypoints.size() < 2) {
			return null;
		}
		route.warpCommand = previous.warpCommand;
		route.lapsPerWarp = previous.lapsPerWarp;
		route.arrivalRadius = previous.arrivalRadius;
		route.segmentTimeoutSeconds = previous.segmentTimeoutSeconds;
		route.showMarkers = previous.showMarkers;
		route.save();
		return route;
	}

	public void cancel() {
		started = false;
		route.waypoints.clear();
		resetSegment();
	}

	/** The points marked so far, so they can be drawn while recording. */
	public List<Route.Waypoint> marked() {
		return route.waypoints;
	}

	private void resetSegment() {
		ticksHeld.clear();
		presses.clear();
		wasHeld.clear();
		ticksInSegment = 0;
	}
}
