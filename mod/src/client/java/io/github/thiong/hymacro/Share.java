package io.github.thiong.hymacro;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turning a macro into something you can send to someone.
 *
 * <p>The route's own JSON is far too long to paste: chat takes 256 characters
 * and a route of ten points is thousands. So it is compressed and encoded into
 * a single line, which fits in a message anywhere.
 *
 * <p>The code carries a version marker. A code from a later format should say so
 * rather than fail as corrupt, because "this came from a newer HyMacro" is a
 * useful thing to be told and "invalid code" is not.
 */
public final class Share {
	private static final String MARKER = "HYMACRO1:";
	private static final Gson GSON = new Gson();

	/**
	 * The shape of what is inside. The marker says it is a HyMacro code at all;
	 * this says whether this build understands the one it is holding.
	 */
	private static final int FORMAT = 1;

	private Share() {
	}

	public static String encode(Route route) throws IOException {
		JsonObject payload = route.toJson();
		payload.addProperty("format", FORMAT);
		byte[] json = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream packed = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
			gzip.write(json);
		}
		return MARKER + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray());
	}

	public static Route decode(String code) throws IOException {
		String trimmed = code == null ? "" : code.trim();
		if (!trimmed.startsWith(MARKER)) {
			throw new IOException(trimmed.startsWith("HYMACRO")
				? "that code comes from a newer version of HyMacro"
				: "that is not a HyMacro code");
		}

		byte[] packed;
		try {
			packed = Base64.getUrlDecoder().decode(trimmed.substring(MARKER.length()));
		} catch (IllegalArgumentException malformed) {
			throw new IOException("the code is incomplete, copy the whole line");
		}

		byte[] json;
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
			json = gzip.readAllBytes();
		} catch (IOException truncated) {
			throw new IOException("the code is damaged, copy the whole line again");
		}

		// Everything past here is parsing what a stranger wrote, so a fault in it
		// is a message about their code, never an exception out of a command.
		try {
			JsonObject root =
				GSON.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null) {
				throw new IOException("the code holds nothing readable");
			}
			if (root.has("format") && root.get("format").getAsInt() > FORMAT) {
				throw new IOException("it was made by a newer HyMacro, update yours");
			}

			Route route = Route.fromJson(root);
			if (route.isEmpty()) {
				throw new IOException("the macro in it has no points");
			}
			return route;
		} catch (IOException known) {
			throw known;
		} catch (RuntimeException broken) {
			String reason = broken.getMessage();
			throw new IOException(reason == null || reason.isBlank()
				? "the macro inside it is malformed"
				: reason);
		}
	}
}
