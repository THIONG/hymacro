package io.github.thiong.hymacro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Records a route by watching the player walk it.
 *
 * <p>Rather than being told what to do, it samples which keys are held while
 * travelling between the points that get marked. A key counts for a segment if
 * it was down for most of it, so a stray tap does not end up in the route.
 */
public final class Recorder {
	private static final double MOSTLY = 0.5;

	private final Route route = new Route();
	private final Map<String, Integer> ticksHeld = new HashMap<>();
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
			if (Keys.isHeld(name)) {
				ticksHeld.merge(name, 1, Integer::sum);
			}
		}
	}

	/** Marks the point the player is standing on as the end of the current leg. */
	public void mark(Minecraft client) {
		if (!started || client.player == null) {
			return;
		}

		List<String> keys = new ArrayList<>();
		for (String name : Keys.RECORDABLE) {
			int held = ticksHeld.getOrDefault(name, 0);
			if (ticksInSegment > 0 && held >= ticksInSegment * MOSTLY) {
				keys.add(name);
			}
		}

		route.waypoints.add(new Route.Waypoint(
			client.player.getX(), client.player.getY(), client.player.getZ(), keys));
		resetSegment();
	}

	/** Ends the recording and returns the route, or null when nothing usable was captured. */
	public Route finish(Route previous) {
		started = false;
		if (route.waypoints.size() < 2) {
			return null;
		}
		route.warpCommand = previous.warpCommand;
		route.lapsPerWarp = previous.lapsPerWarp;
		route.arrivalRadius = previous.arrivalRadius;
		route.segmentTimeoutSeconds = previous.segmentTimeoutSeconds;
		route.save();
		return route;
	}

	public void cancel() {
		started = false;
		route.waypoints.clear();
		resetSegment();
	}

	private void resetSegment() {
		ticksHeld.clear();
		ticksInSegment = 0;
	}
}
