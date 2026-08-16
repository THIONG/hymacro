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

	/**
	 * Something to watch for while running, and what to do when it happens.
	 *
	 * <p>Deliberately not "stop for pests". What is watched, the number that
	 * sets it off and what happens next are three separate things, so a second
	 * thing worth watching later is a name in a list rather than a rewrite. Pests
	 * are simply the first one worth watching.
	 *
	 * <p>It fires on the way past the number, not while above it. A macro that
	 * fired every tick that four pests were in sight would stop, hunt, resume and
	 * stop again before it took a step.
	 */
	public static final class When {
		public static final String PESTS = "pests";
		public static final String AWAY = "away";
		public static final String HUNTED = "hunted";
		public static final String HUNT = "hunt";
		public static final String SEND = "send";
		public static final String STOP = "stop";

		public final String watch;
		public final int atLeast;
		public final String then;
		public final String text;

		/** Where the macro belongs, for a rule about no longer being there. */
		public final String place;

		public When(String watch, int atLeast, String then, String text, String place) {
			this.watch = watch;
			this.atLeast = Math.max(1, atLeast);
			this.then = then;
			this.text = text == null ? "" : text;
			this.place = place == null ? "" : place;
		}

		public boolean watchesPlace() {
			return AWAY.equals(watch);
		}

		public boolean watchesHunt() {
			return HUNTED.equals(watch);
		}

		public String describe() {
			if (watchesHunt()) {
				return SEND.equals(then) ? "after a hunt, send " + text : "after a hunt, stop";
			}
			String what = watchesPlace() ? "leaving " + place : atLeast + " " + watch;
			return switch (then) {
				case HUNT -> "on " + what + ", hunt them and come back";
				case SEND -> "on " + what + ", send " + text + " and carry on when back";
				default -> "on " + what + ", stop";
			};
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

		/**
		 * Run rather than walk.
		 *
		 * <p>Its own flag rather than holding the sprint key by name. The key can
		 * be rebound and a macro that assumed control was sprint would quietly do
		 * nothing for whoever had moved it.
		 */
		public final boolean sprint;

		/** How close counts as arrived here, or 0 to use the macro's own. */
		public final double radius;

		public Waypoint(double x, double y, double z, float yaw, float pitch,
				List<Action> actions, String send, boolean walk, double radius, boolean sprint) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.actions = actions;
			this.send = send == null ? "" : send;
			this.walk = walk;
			this.radius = radius;
			this.sprint = sprint;
		}

		public Waypoint(double x, double y, double z, float yaw, float pitch,
				List<Action> actions, String send, boolean walk) {
			this(x, y, z, yaw, pitch, actions, send, walk, 0.0, false);
		}

		public Waypoint(double x, double y, double z, float yaw, float pitch, List<Action> actions) {
			this(x, y, z, yaw, pitch, actions, "", false, 0.0, false);
		}

		/** How close counts as arrived here, falling back to the macro's own. */
		public double radiusOr(double fallback) {
			return radius > 0.0 ? radius : fallback;
		}

		public boolean sends() {
			return !send.isBlank();
		}

		public Waypoint withPosition(double newX, double newY, double newZ) {
			return new Waypoint(newX, newY, newZ, yaw, pitch, actions, send, walk, radius, sprint);
		}

		public Waypoint movedBy(double byX, double byY, double byZ) {
			return withPosition(x + byX, y + byY, z + byZ);
		}

		public Waypoint withActions(List<Action> replacement) {
			return new Waypoint(x, y, z, yaw, pitch, replacement, send, walk, radius, sprint);
		}

		public Waypoint withLook(float newYaw, float newPitch) {
			return new Waypoint(x, y, z, newYaw, newPitch, actions, send, walk, radius, sprint);
		}

		public Waypoint withSend(String text) {
			return new Waypoint(x, y, z, yaw, pitch, actions, text, walk, radius, sprint);
		}

		public Waypoint withSprint(boolean running) {
			return new Waypoint(x, y, z, yaw, pitch, actions, send, walk, radius, running);
		}

		public Waypoint withRadius(double newRadius) {
			return new Waypoint(x, y, z, yaw, pitch, actions, send, walk, newRadius, sprint);
		}

		public Waypoint withWalk(boolean steering) {
			return new Waypoint(x, y, z, yaw, pitch, actions, send, steering, radius, sprint);
		}
	}

	public final List<Waypoint> waypoints = new ArrayList<>();
	public double arrivalRadius = 1.0;

	/**
	 * Everything that interrupts this macro, one for each thing watched.
	 *
	 * <p>A list rather than one, because the things worth watching are not
	 * alternatives: pests eat the plot while you farm it and a restart moves you
	 * off the island, and wanting to handle both is the ordinary case rather than
	 * a clever one. Setting a rule replaces the rule about the same thing and
	 * leaves the rest alone.
	 */
	public final List<When> rules = new ArrayList<>();

	/** The rule about one thing, or null. */
	public When rule(String watch) {
		for (When rule : rules) {
			if (rule.watch.equals(watch)) {
				return rule;
			}
		}
		return null;
	}

	public void setRule(When rule) {
		rules.removeIf(existing -> existing.watch.equals(rule.watch));
		rules.add(rule);
	}
	/**
	 * Seconds of getting no closer before a leg is given up on.
	 *
	 * <p>This replaced a fixed budget for the whole leg, which could not tell a
	 * long row from a stuck player and cut short every leg that honestly took
	 * more than a minute and a half.
	 */
	public double stallSeconds = 20.0;
	public boolean visible = true;

	public boolean isEmpty() {
		return waypoints.isEmpty();
	}

	/** More points than any plot could want, and a sign of a bad code. */
	private static final int MAX_WAYPOINTS = 512;

	public static Route fromJson(JsonObject root) {
		Route route = new Route();
		if (root.has("arrivalRadius")) {
			route.arrivalRadius = Math.max(0.15, root.get("arrivalRadius").getAsDouble());
		}
		if (root.has("stallSeconds")) {
			route.stallSeconds = Math.max(2.0, root.get("stallSeconds").getAsDouble());
		}
		if (root.has("visible")) {
			route.visible = root.get("visible").getAsBoolean();
		} else if (root.has("showMarkers")) {
			route.visible = root.get("showMarkers").getAsBoolean();
		}
		if (root.has("waypoints")) {
			JsonElement list = root.get("waypoints");
			if (!list.isJsonArray()) {
				throw new IllegalArgumentException("the points are not a list");
			}
			if (list.getAsJsonArray().size() > MAX_WAYPOINTS) {
				throw new IllegalArgumentException("it claims more than " + MAX_WAYPOINTS + " points");
			}
			for (JsonElement element : list.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("a point is not an object");
				}
				route.waypoints.add(readWaypoint(element.getAsJsonObject()));
			}
		}
		if (root.has("warpCommand")) {
			route.carryOverWarp(root.get("warpCommand").getAsString());
		}
		if (root.has("when")) {
			JsonElement when = root.get("when");
			if (when.isJsonArray()) {
				for (JsonElement one : when.getAsJsonArray()) {
					readRule(route, one);
				}
			} else {
				// One rule was all there used to be, written as a lone object.
				readRule(route, when);
			}
		}
		return route;
	}

	private static void readRule(Route route, JsonElement element) {
		if (!element.isJsonObject()) {
			return;
		}
		JsonObject when = element.getAsJsonObject();
		if (!when.has("watch") || !when.has("then")) {
			return;
		}
		route.setRule(new When(
			when.get("watch").getAsString(),
			when.has("atLeast") ? when.get("atLeast").getAsInt() : 1,
			when.get("then").getAsString(),
			when.has("text") ? when.get("text").getAsString() : "",
			when.has("place") ? when.get("place").getAsString() : ""));
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
			if (waypoint.radius > 0.0) {
				point.addProperty("radius", waypoint.radius);
			}
			if (waypoint.sprint) {
				point.addProperty("sprint", true);
			}
			point.add("actions", actions);
			points.add(point);
		}

		JsonObject root = new JsonObject();
		root.addProperty("arrivalRadius", arrivalRadius);
		if (!rules.isEmpty()) {
			JsonArray written = new JsonArray();
			for (When when : rules) {
				JsonObject rule = new JsonObject();
				rule.addProperty("watch", when.watch);
				rule.addProperty("atLeast", when.atLeast);
				rule.addProperty("then", when.then);
				if (!when.text.isBlank()) {
					rule.addProperty("text", when.text);
				}
				if (!when.place.isBlank()) {
					rule.addProperty("place", when.place);
				}
				written.add(rule);
			}
			root.add("when", written);
		}
		root.addProperty("stallSeconds", stallSeconds);
		root.addProperty("visible", visible);
		root.add("waypoints", points);
		return root;
	}



	/**
	 * Reads a point, refusing anything it cannot make sense of.
	 *
	 * <p>This parses whatever a stranger pasted in, so nothing here may assume a
	 * field is present or is the type it should be. A missing coordinate used to
	 * be a null dereference rather than a message.
	 */
	static Waypoint readWaypoint(JsonObject point) {
		List<Action> actions = new ArrayList<>();
		if (point.has("actions")) {
			JsonElement list = point.get("actions");
			if (!list.isJsonArray()) {
				throw new IllegalArgumentException("the actions of a point are not a list");
			}
			for (JsonElement element : list.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					throw new IllegalArgumentException("an action is not an object");
				}
				JsonObject action = element.getAsJsonObject();
				if (!action.has("key")) {
					throw new IllegalArgumentException("an action has no key");
				}
				actions.add(new Action(
					action.get("key").getAsString(),
					action.has("mode") ? action.get("mode").getAsString() : HOLD,
					action.has("intervalTicks") ? action.get("intervalTicks").getAsInt() : 1));
			}
		}
		return new Waypoint(
			coordinate(point, "x"),
			coordinate(point, "y"),
			coordinate(point, "z"),
			point.has("yaw") ? point.get("yaw").getAsFloat() : 0.0f,
			point.has("pitch") ? point.get("pitch").getAsFloat() : 0.0f,
			actions,
			point.has("send") ? point.get("send").getAsString() : "",
			point.has("walk") && point.get("walk").getAsBoolean(),
			point.has("radius") ? Math.max(0.0, point.get("radius").getAsDouble()) : 0.0,
			point.has("sprint") && point.get("sprint").getAsBoolean());
	}

	private static double coordinate(JsonObject point, String name) {
		if (!point.has(name)) {
			throw new IllegalArgumentException("a point has no " + name);
		}
		double value = point.get(name).getAsDouble();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("a point has an impossible " + name);
		}
		return value;
	}

	/** Every line this macro would type into chat if it ran. */
	public List<String> chatLines() {
		List<String> lines = new ArrayList<>();
		for (Waypoint point : waypoints) {
			if (point.sends()) {
				lines.add(point.send);
			}
		}
		return lines;
	}

}
