package com.javampire.openscad.editor;

import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.NotNull;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Sends preview source payloads from Java to the JCEF JavaScript preview runtime.
 */
public final class OpenSCADPreviewWasmBridge {

    private static final int SCRIPT_CHUNK_SIZE = 256 * 1024;

    private OpenSCADPreviewWasmBridge() {
    }

    public static void render(@NotNull final CefBrowser browser, @NotNull final OpenSCADPreviewSourceCollector.PreviewSources sources) {
        final String json = toJson(sources);
        final String frameUrl = browser.getURL();
        final String transferId = UUID.randomUUID().toString();
        final int chunkCount = (json.length() + SCRIPT_CHUNK_SIZE - 1) / SCRIPT_CHUNK_SIZE;
        browser.executeJavaScript(
                "window.beginPreviewPayloadTransfer("
                        + quote(transferId) + "," + chunkCount + "," + json.length() + ");",
                frameUrl,
                0
        );
        for (int index = 0; index < chunkCount; index++) {
            final int start = index * SCRIPT_CHUNK_SIZE;
            final String chunk = json.substring(start, Math.min(start + SCRIPT_CHUNK_SIZE, json.length()));
            browser.executeJavaScript(
                    "window.appendPreviewPayloadChunk("
                            + quote(transferId) + "," + index + "," + chunk.length() + "," + quote(chunk) + ");",
                    frameUrl,
                    0
            );
        }
        browser.executeJavaScript(
                "window.finishPreviewPayloadTransfer(" + quote(transferId) + ");",
                frameUrl,
                0
        );
    }

    @NotNull
    private static String toJson(@NotNull final OpenSCADPreviewSourceCollector.PreviewSources sources) {
        final StringBuilder json = new StringBuilder();
        json.append("{\"mainPath\":").append(quote(sources.mainPath()))
                .append(",\"loadPreviewFonts\":").append(sources.loadPreviewFonts())
                .append(",\"enableTextMetrics\":").append(sources.enableTextMetrics())
                .append(",\"assets\":{");
        appendBase64Map(json, sources.assets());
        json.append("},\"userFonts\":{");
        appendBase64Map(json, sources.userFonts());
        json.append("},\"files\":{");
        boolean first = true;
        for (final Map.Entry<String, String> entry : sources.files().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        json.append("}}");
        return json.toString();
    }

    private static void appendBase64Map(@NotNull final StringBuilder json, @NotNull final Map<String, byte[]> values) {
        boolean first = true;
        for (final Map.Entry<String, byte[]> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':').append(quote(Base64.getEncoder().encodeToString(entry.getValue())));
        }
    }

    @NotNull
    private static String quote(@NotNull final String value) {
        final StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"', '\\' -> quoted.append('\\').append(c);
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\u2028' -> quoted.append("\\u2028");
                case '\u2029' -> quoted.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int)c));
                    }
                    else {
                        quoted.append(c);
                    }
                }
            }
        }
        quoted.append('"');
        return quoted.toString();
    }
}
