package io.github.thiong.hymacro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded path: where to go, where to look, and what to hold on the way.
 *
 * <p>Legs end on arrival rather than after a fixed time, so nothing has to be
 * calibrated and a route cannot drift. Falling short simply means holding the
 * keys a moment longer, which makes speed, buffs, lag and being shoved by a mob
 * all stop mattering.
 *
 * <p>The JSON is built by hand rather than through reflection, to keep the
 * serialiser out of a compile loop that costs a round trip through CI.
 */
public final class Route {
	public static final String HOLD = "hold";
	public static final String SPAM = "spam";
	public static final String ONCE = "once";

	/** One key and how it is worked: held down, or clicked repeatedly. */
	public static final class Action {
		public final String key;
		public final String mode;
		public final int intervalTicks;

		public Action(String key, String mode, int intervalTicks) {
			this.key = key;
			this.mode = mode;
			this.intervalTicks = Math.max(1, intervalTicks);
		}

		public boolean isSpam() {
			return SPAM.equals(mode);
		}

		public boolean isOnce() {
			return ONCE.equals(mode);
		}

		/** True when the tick loop works the key rather than a plain hold. */
		public boolean isTimed() {
			return isSpam() || isOnce();
		}
	}

	/** Somewhere to reach, facing a particular way, doing particular things. */
	public static final class Waypoint {
		public final double x;
		public final double y;
		public final double z;
		public final float yaw;
		public final float pitch;
		public final List<Action> actions;

		/** Sent on arrival: a command with its slash, or a line of chat. */
		public final String send;

		/** Steer towards this point rather than holding a fixed direction. */
		public final boolean walk;

		public Waypoint(double x, double y, double z, float yaw, float pitch,
				List<Action> actions, String send, boolean walk) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.actions = actions;
			this.send = send == null ? "" : send;
			this.walk = walk;
		}

		public Waypoint(double x, double y, double z, float yaw, float pitch, List<Action> actions) {
			this(x, y, z, yaw, pitch, actions, "", false);
		}

		public boolean sends() {
			return !send.isBlank();
		}

		public Waypoint withActions(List<Action> replacement) {
			return new Waypoint(x, y, z, yaw, pitch, replacement, send, walk);
		}

		public Waypoint withLook(float newYaw, float newPitch) {
			return new Waypoint(x, y, z, newYaw, newPitch, actions, send, walk);
		}

		public Waypoint withSend(String text) {
			return new Waypoint(x, y, z, yaw, pitch, actions, text, walk);
		}

		public Waypoint withWalk(boolean steering) {
			return new Waypoint(x, y, z, yaw, pitch, actions, send, steering);
		}
	}

	public final List<Waypoint> waypoints = new ArrayList<>();
	public double arrivalRadius = 1.0;
	public double segmentTimeoutSeconds = 90.0;
	public boolean visible = true;

	public boolean isEmpty() {
		return waypoints.isEmpty();
	}

	public static Route fromJson(JsonObject root) {
		Route route = new Route();
		if (root.has("arrivalRadius")) {
			route.arrivalRadius = Math.max(0.2, root.get("arrivalRadius").getAsDouble());
		}
		if (root.has("segmentTimeoutSeconds")) {
			route.segmentTimeoutSeconds =
				Math.max(1.0, root.get("segmentTimeoutSeconds").getAsDouble());
		}
		if (root.has("visible")) {
			route.visible = root.get("visible").getAsBoolean();
		} else if (root.has("showMarkers")) {
			route.visible = root.get("showMarkers").getAsBoolean();
		}
		if (root.has("waypoints")) {
			for (JsonElement element : root.getAsJsonArray("waypoints")) {
				route.waypoints.add(readWaypoint(element.getAsJsonObject()));
			}
		}
		if (root.has("warpCommand")) {
			route.carryOverWarp(root.get("warpCommand").getAsString());
		}
		return route;
	}

	/**
	 * A warp used to be a property of the whole route, fired at the end of a lap.
	 * It is now just something sent on arriving somewhere, so an old one becomes
	 * a send on the last point, which is where it used to happen.
	 */
	private void carryOverWarp(String command) {
		if (command.isBlank() || waypoints.isEmpty()) {
			return;
		}
		int last = waypoints.size() - 1;
		Waypoint point = waypoints.get(last);
		waypoints.set(last, point.withSend(command.startsWith("/") ? command : "/" + command));
	}

	public JsonObject toJson() {
		JsonArray points = new JsonArray();
		for (Waypoint waypoint : waypoints) {
			JsonArray actions = new JsonArray();
			for (Action action : waypoint.actions) {
				JsonObject entry = new JsonObject();
				entry.addProperty("key", action.key);
				entry.addProperty("mode", action.mode);
				if (action.isSpam()) {
					entry.addProperty("intervalTicks", action.intervalTicks);
				}
				actions.add(entry);
			}

			JsonObject point = new JsonObject();
			point.addProperty("x", waypoint.x);
			point.addProperty("y", waypoint.y);
			point.addProperty("z", waypoint.z);
			point.addProperty("yaw", waypoint.yaw);
			point.addProperty("pitch", waypoint.pitch);
			if (waypoint.sends()) {
				point.addProperty("send", waypoint.send);
			}
			if (waypoint.walk) {
				point.addProperty("walk", true);
			}
			point.add("actions", actions);
			points.add(point);
		}

		JsonObject root = new JsonObject();
		root.addProperty("arrivalRadius", arrivalRadius);
		root.addProperty("segmentTimeoutSeconds", segmentTimeoutSeconds);
		root.addProperty("visible", visible);
		root.add("waypoints", points);
		return root;
	}



	static Waypoint readWaypoint(JsonObject point) {
		List<Action> actions = new ArrayList<>();
		if (point.has("actions")) {
			for (JsonElement element : point.getAsJsonArray("actions")) {
				JsonObject action = element.getAsJsonObject();
				actions.add(new Action(
					action.get("key").getAsString(),
					action.has("mode") ? action.get("mode").getAsString() : HOLD,
					action.has("intervalTicks") ? action.get("intervalTicks").getAsInt() : 1));
			}
		}
		return new Waypoint(
			point.get("x").getAsDouble(),
			point.get("y").getAsDouble(),
			point.get("z").getAsDouble(),
			point.has("yaw") ? point.get("yaw").getAsFloat() : 0.0f,
			point.has("pitch") ? point.get("pitch").getAsFloat() : 0.0f,
			actions,
			point.has("send") ? point.get("send").getAsString() : "",
			point.has("walk") && point.get("walk").getAsBoolean());
	}

}
