package io.github.thiong.hymacro;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Building a route by saying what happens, rather than by being watched doing it.
 *
 * <p>Declaring beats recording for this: a recording captures the hesitations
 * too, and a single leg cannot be changed without walking the whole thing again.
 * Position still has to be captured by standing somewhere, because there is no
 * other way to name a spot, but the work done along the way is stated outright.
 *
 * <p>Chat commands rather than a screen of buttons. A Minecraft GUI is the part
 * of the API that shifts most between versions, and this one publishes no
 * mappings to check against; commands give the same authoring with far less of
 * it exposed, and tab completion for free.
 */
public final class Commands {
	private Commands() {
	}

	public interface Host {
		Route route();

		void replaceRoute(Route route);

		void play();

		void stop();
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(
			String name, ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	public static void register(Host host) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
			dispatcher.register(literal("hymacro")
				.then(literal("point")
					.executes(context -> addPoint(context, host)))
				.then(literal("hold")
					.then(argument("key", StringArgumentType.word())
						.executes(context -> addAction(context, host, Route.HOLD, 1))))
				.then(literal("spam")
					.then(argument("key", StringArgumentType.word())
						.executes(context -> addAction(context, host, Route.SPAM, 4))
						.then(argument("everyTicks", IntegerArgumentType.integer(1, 100))
							.executes(context -> addAction(context, host, Route.SPAM,
								IntegerArgumentType.getInteger(context, "everyTicks"))))))
				.then(literal("list")
					.executes(context -> list(context, host)))
				.then(literal("undo")
					.executes(context -> undo(context, host)))
				.then(literal("clear")
					.executes(context -> clear(context, host)))
				.then(literal("radius")
					.then(argument("blocks", IntegerArgumentType.integer(1, 10))
						.executes(context -> setRadius(context, host))))
				.then(literal("warp")
					.then(argument("command", StringArgumentType.greedyString())
						.executes(context -> setWarp(context, host))))
				.then(literal("play")
					.executes(context -> {
						host.play();
						return 1;
					}))
				.then(literal("stop")
					.executes(context -> {
						host.stop();
						return 1;
					}))));
	}

	private static void say(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendFeedback(Component.literal("[HyMacro] " + message));
	}

	private static int addPoint(CommandContext<FabricClientCommandSource> context, Host host) {
		FabricClientCommandSource source = context.getSource();
		Route route = host.route();
		route.waypoints.add(new Route.Waypoint(
			source.getPlayer().getX(),
			source.getPlayer().getY(),
			source.getPlayer().getZ(),
			source.getPlayer().getYRot(),
			source.getPlayer().getXRot(),
			new ArrayList<>()));
		route.save();
		say(context, "Point " + route.waypoints.size()
			+ " set. Say what happens on the way to it with /hymacro hold or /hymacro spam.");
		return 1;
	}

	/** Attaches work to the leg that ends at the last point set. */
	private static int addAction(
			CommandContext<FabricClientCommandSource> context, Host host, String mode, int interval) {
		Route route = host.route();
		if (route.isEmpty()) {
			say(context, "Set a point first with /hymacro point.");
			return 0;
		}

		String key = StringArgumentType.getString(context, "key").toLowerCase();
		if (!Keys.isKnown(key)) {
			say(context, "Unknown key " + key + ". Try one of " + String.join(", ", Keys.RECORDABLE));
			return 0;
		}

		Route.Waypoint last = route.waypoints.get(route.waypoints.size() - 1);
		List<Route.Action> actions = new ArrayList<>(last.actions);
		actions.removeIf(action -> action.key.equals(key));
		actions.add(new Route.Action(key, mode, interval));

		route.waypoints.set(route.waypoints.size() - 1, new Route.Waypoint(
			last.x, last.y, last.z, last.yaw, last.pitch, actions));
		route.save();

		say(context, Route.SPAM.equals(mode)
			? "Leg " + route.waypoints.size() + ": spam " + key + " every " + interval + " ticks."
			: "Leg " + route.waypoints.size() + ": hold " + key + ".");
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = host.route();
		if (route.isEmpty()) {
			say(context, "No points yet. Stand somewhere and use /hymacro point.");
			return 0;
		}

		say(context, route.waypoints.size() + " points, radius " + route.arrivalRadius
			+ (route.warpCommand.isBlank() ? ", no warp" : ", warp /" + route.warpCommand));
		for (int i = 0; i < route.waypoints.size(); i++) {
			Route.Waypoint point = route.waypoints.get(i);
			StringBuilder work = new StringBuilder();
			for (Route.Action action : point.actions) {
				if (work.length() > 0) {
					work.append(", ");
				}
				work.append(action.isSpam()
					? "spam " + action.key + "/" + action.intervalTicks + "t"
					: "hold " + action.key);
			}
			say(context, "  " + (i + 1) + ": "
				+ Math.round(point.x) + " " + Math.round(point.y) + " " + Math.round(point.z)
				+ "  facing " + Math.round(point.yaw) + "/" + Math.round(point.pitch)
				+ "  " + (work.length() == 0 ? "nothing set" : work));
		}
		return 1;
	}

	private static int undo(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = host.route();
		if (route.isEmpty()) {
			say(context, "Nothing to undo.");
			return 0;
		}
		route.waypoints.remove(route.waypoints.size() - 1);
		route.save();
		say(context, "Removed the last point, " + route.waypoints.size() + " left.");
		return 1;
	}

	private static int clear(CommandContext<FabricClientCommandSource> context, Host host) {
		host.stop();
		Route route = new Route();
		route.warpCommand = host.route().warpCommand;
		route.arrivalRadius = host.route().arrivalRadius;
		route.save();
		host.replaceRoute(route);
		say(context, "Route cleared.");
		return 1;
	}

	private static int setRadius(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = host.route();
		route.arrivalRadius = IntegerArgumentType.getInteger(context, "blocks");
		route.save();
		say(context, "A leg now ends within " + route.arrivalRadius + " blocks of its point.");
		return 1;
	}

	private static int setWarp(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = host.route();
		route.warpCommand = StringArgumentType.getString(context, "command");
		route.save();
		say(context, "Warp set to /" + route.warpCommand + " after each lap.");
		return 1;
	}
}
