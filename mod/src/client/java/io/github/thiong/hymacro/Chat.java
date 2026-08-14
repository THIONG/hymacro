package io.github.thiong.hymacro;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chat output, in one place so every message reads the same way.
 *
 * <p>Colour carries the kind of message rather than decorating it: gold is the
 * mod speaking, green is something that worked, red is something that did not,
 * aqua is a command you can type, and grey is explanation. A wall of white text
 * makes the reader find the important part; this does it for them.
 */
public final class Chat {
	private static final String PREFIX = "HyMacro";
	private static final int HUE_PER_LETTER = 26;
	private static final int HUE_PER_PRINT = 37;

	/** Where the sweep starts, moved on each time so no two printings match. */
	private static int phase;

	private Chat() {
	}

	/**
	 * The name, swept through the spectrum a letter at a time.
	 *
	 * <p>Carried over from the console tool, whose banner rolled a wave across
	 * itself. Chat cannot animate, since a line is fixed once it is printed, so
	 * the sweep starts a little further round on each printing instead.
	 */
	public static MutableComponent wordmark() {
		MutableComponent text = Component.empty();
		for (int i = 0; i < PREFIX.length(); i++) {
			int rgb = spectrum(phase + i * HUE_PER_LETTER);
			text.append(Component.literal(String.valueOf(PREFIX.charAt(i)))
				.withStyle(style -> style.withColor(rgb).withBold(true)));
		}
		phase = (phase + HUE_PER_PRINT) % 360;
		return text;
	}

	/** A hue, fully saturated, as packed RGB. */
	private static int spectrum(int degrees) {
		double scaled = Math.floorMod(degrees, 360) / 60.0;
		int sector = (int) Math.floor(scaled);
		int rise = (int) Math.round(255 * (scaled - sector));
		int fall = 255 - rise;
		return switch (sector) {
			case 0 -> pack(255, rise, 0);
			case 1 -> pack(fall, 255, 0);
			case 2 -> pack(0, 255, rise);
			case 3 -> pack(0, fall, 255);
			case 4 -> pack(rise, 0, 255);
			default -> pack(255, 0, fall);
		};
	}

	private static int pack(int red, int green, int blue) {
		return (red << 16) | (green << 8) | blue;
	}

	public static void ok(FabricClientCommandSource source, String message) {
		source.sendFeedback(tagged(message, ChatFormatting.GREEN));
	}

	public static void info(FabricClientCommandSource source, String message) {
		source.sendFeedback(tagged(message, ChatFormatting.WHITE));
	}

	public static void error(FabricClientCommandSource source, String message) {
		source.sendFeedback(tagged(message, ChatFormatting.RED));
	}

	/** The name in colour, ruled off, to open a block of help. */
	public static void banner(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("")
			.append(Component.literal("── ").withStyle(ChatFormatting.DARK_GRAY))
			.append(wordmark())
			.append(Component.literal(" " + "─".repeat(21)).withStyle(ChatFormatting.DARK_GRAY)));
	}

	/** A section title, ruled off so blocks of help stay apart. */
	public static void heading(FabricClientCommandSource source, String title) {
		source.sendFeedback(Component.literal("")
			.append(Component.literal("── ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
			.append(Component.literal(" " + "─".repeat(Math.max(2, 28 - title.length())))
				.withStyle(ChatFormatting.DARK_GRAY)));
	}

	/** A command and what it does, the command coloured so it stands out. */
	public static void entry(FabricClientCommandSource source, String command, String meaning) {
		source.sendFeedback(Component.literal(" ")
			.append(Component.literal(command).withStyle(ChatFormatting.AQUA))
			.append(Component.literal("  " + meaning).withStyle(ChatFormatting.GRAY)));
	}

	/** A line of plain explanation, dimmer than a result. */
	public static void note(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(" " + message).withStyle(ChatFormatting.DARK_GRAY));
	}

	/** An item in a list, marked when it is the one in hand. */
	public static void bullet(FabricClientCommandSource source, String text, boolean current) {
		source.sendFeedback(Component.literal(current ? " > " : "   ")
			.withStyle(current ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY)
			.append(Component.literal(text)
				.withStyle(current ? ChatFormatting.WHITE : ChatFormatting.GRAY)));
	}

	/**
	 * Straight into the chat window, for things a key press caused rather than a
	 * command. A command has a source to reply to; F9 has nowhere to answer.
	 */
	public static void client(String message, boolean bad) {
		Minecraft.getInstance().gui.getChat()
			.addClientSystemMessage(tagged(message, bad ? ChatFormatting.RED : ChatFormatting.GREEN));
	}

	public static void clientNote(String message) {
		Minecraft.getInstance().gui.getChat()
			.addClientSystemMessage(Component.literal(" " + message).withStyle(ChatFormatting.GRAY));
	}

	private static MutableComponent tagged(String message, ChatFormatting colour) {
		return Component.literal("")
			.append(Component.literal("[" + PREFIX + "] ").withStyle(ChatFormatting.GOLD))
			.append(Component.literal(message).withStyle(colour));
	}
}
