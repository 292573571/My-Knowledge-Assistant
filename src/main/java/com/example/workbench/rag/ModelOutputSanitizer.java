package com.example.workbench.rag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;

/**
 * Removes chat-template markers that some OpenAI-compatible providers leak into
 * the assistant content stream. The state is per subscription because a single
 * client may serve multiple conversations concurrently.
 */
final class ModelOutputSanitizer {

    private static final Pattern COMPLETE_PROTOCOL_PREFIX = Pattern.compile(
            "(?is)^\\s*(?:(?:\\d{1,3}[ \\t]*\\R[ \\t]*)?assistant(?:[ \\t]*[:：][ \\t]*|[ \\t]*\\R)|<\\|assistant\\|>[ \\t]*|###[ \\t]*assistant[ \\t]*[:：]?[ \\t]*)");
    private static final Pattern POSSIBLE_PROTOCOL_PREFIX = Pattern.compile(
            "(?is)^\\s*(?:\\d{0,3}|\\d{1,3}\\s*\\R\\s*(?:a(?:s(?:s(?:i(?:s(?:t(?:a(?:n(?:t)?)?)?)?)?)?)?)?)?|assistant\\s*(?:[:：]?\\s*)?|<\\|[^>]{0,24}|###\\s*(?:assistan)?)$");

    private ModelOutputSanitizer() {
    }

    static Flux<String> stream(Flux<String> upstream) {
        return Flux.defer(() -> {
            State state = new State();
            return Flux.create(sink -> {
                reactor.core.Disposable subscription = upstream.subscribe(
                        token -> emit(sink, state.accept(token)),
                        sink::error,
                        () -> {
                            emit(sink, state.finish());
                            sink.complete();
                        }
                );
                sink.onCancel(subscription::dispose);
                sink.onDispose(subscription::dispose);
            });
        });
    }

    static String complete(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        State state = new State();
        return state.acceptComplete(text);
    }

    private static void emit(reactor.core.publisher.FluxSink<String> sink, String value) {
        if (value != null && !value.isEmpty() && !sink.isCancelled()) {
            sink.next(value);
        }
    }

    private static final class State {
        private final StringBuilder pending = new StringBuilder();
        private boolean prefixProcessed;
        private boolean wrappedInQuotes;

        String accept(String token) {
            if (token == null || token.isEmpty()) {
                return "";
            }
            pending.append(token);
            if (!prefixProcessed) {
                return processPrefix(false);
            }
            return drain(false);
        }

        String acceptComplete(String token) {
            if (token == null || token.isEmpty()) {
                return token;
            }
            pending.append(token);
            if (!prefixProcessed) {
                return processPrefix(true);
            }
            return finish();
        }

        String finish() {
            if (!prefixProcessed) {
                processPrefix(true);
            }
            String value = drain(true);
            if (wrappedInQuotes && value.stripTrailing().endsWith("\"")) {
                int end = value.length();
                while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
                    end--;
                }
                value = value.substring(0, end - 1) + value.substring(end);
            }
            return value;
        }

        private String processPrefix(boolean complete) {
            String value = pending.toString();
            Matcher protocol = COMPLETE_PROTOCOL_PREFIX.matcher(value);
            if (protocol.find()) {
                pending.delete(0, protocol.end());
                prefixProcessed = true;
                stripOpeningQuote();
                return drain(complete);
            }
            if (!complete && isPossibleProtocolPrefix(value)) {
                return "";
            }
            prefixProcessed = true;
            return drain(complete);
        }

        private boolean isPossibleProtocolPrefix(String value) {
            if (POSSIBLE_PROTOCOL_PREFIX.matcher(value).matches()) {
                return true;
            }
            String normalized = value.stripLeading().toLowerCase();
            return normalized.startsWith("1\n")
                    || normalized.startsWith("1\r\n")
                    || normalized.startsWith("assistant")
                    || normalized.startsWith("assistan")
                    || normalized.startsWith("<|assistant")
                    || normalized.startsWith("### assistan");
        }

        private void stripOpeningQuote() {
            int index = 0;
            while (index < pending.length() && Character.isWhitespace(pending.charAt(index))) {
                index++;
            }
            if (index < pending.length() && pending.charAt(index) == '"') {
                pending.deleteCharAt(index);
                wrappedInQuotes = true;
            }
        }

        private String drain(boolean complete) {
            if (pending.isEmpty()) {
                return "";
            }
            String value = pending.toString();
            pending.setLength(0);
            if (wrappedInQuotes) {
                int end = value.length();
                while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
                    end--;
                }
                if (end > 0 && value.charAt(end - 1) == '"') {
                    value = value.substring(0, end - 1) + value.substring(end);
                    wrappedInQuotes = false;
                }
            }
            return value;
        }
    }
}
