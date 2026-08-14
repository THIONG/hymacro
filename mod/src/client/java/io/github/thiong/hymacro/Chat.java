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

	private Chat() {
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
