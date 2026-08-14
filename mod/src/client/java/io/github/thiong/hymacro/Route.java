package io.github.thiong.hymacro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

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
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final String HOLD = "hold";
	public static final String SPAM = "spam";

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
	}

	/** Somewhere to reach, facing a particular way, doing particular things. */
	public static final class Waypoint {
		public final double x;
		public final double y;
		public final double z;
		public final float yaw;
		public final float pitch;
		public final List<Action> actions;

		public Waypoint(double x, double y, double z, float yaw, float pitch, List<Action> actions) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.actions = actions;
		}
	}

	public final List<Waypoint> waypoints = new ArrayList<>();
	public String warpCommand = "";
	public int lapsPerWarp = 1;
	public double arrivalRadius = 1.0;
	public double segmentTimeoutSeconds = 90.0;
	public boolean showMarkers = true;

	public boolean isEmpty() {
		return waypoints.isEmpty();
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("hymacro-route.json");
	}

	public static Route load() {
		Route route = new Route();
		Path file = path();
		if (!Files.exists(file)) {
			return route;
		}

		try {
			JsonObject root = GSON.fromJson(
				Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null) {
				return route;
			}
			if (root.has("warpCommand")) {
				route.warpCommand = root.get("warpCommand").getAsString();
			}
			if (root.has("lapsPerWarp")) {
				route.lapsPerWarp = Math.max(1, root.get("lapsPerWarp").getAsInt());
			}
			if (root.has("arrivalRadius")) {
				route.arrivalRadius = Math.max(0.2, root.get("arrivalRadius").getAsDouble());
			}
			if (root.has("segmentTimeoutSeconds")) {
				route.segmentTimeoutSeconds =
					Math.max(1.0, root.get("segmentTimeoutSeconds").getAsDouble());
			}
			if (root.has("showMarkers")) {
				route.showMarkers = root.get("showMarkers").getAsBoolean();
			}
			if (root.has("waypoints")) {
				for (JsonElement element : root.getAsJsonArray("waypoints")) {
					route.waypoints.add(readWaypoint(element.getAsJsonObject()));
				}
			}
		} catch (IOException | RuntimeException exception) {
			HyMacroClient.LOGGER.warn("Could not read {}", file, exception);
		}
		return route;
	}

	private static Waypoint readWaypoint(JsonObject point) {
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
			actions);
	}

	public void save() {
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
			point.add("actions", actions);
			points.add(point);
		}

		JsonObject root = new JsonObject();
		root.addProperty("warpCommand", warpCommand);
		root.addProperty("lapsPerWarp", lapsPerWarp);
		root.addProperty("arrivalRadius", arrivalRadius);
		root.addProperty("segmentTimeoutSeconds", segmentTimeoutSeconds);
		root.addProperty("showMarkers", showMarkers);
		root.add("waypoints", points);

		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
		} catch (IOException exception) {
			HyMacroClient.LOGGER.warn("Could not write {}", path(), exception);
		}
	}
}
