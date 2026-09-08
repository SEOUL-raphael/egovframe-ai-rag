package com.example.wiki;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.Objects;

/** Synchronous text contract: do not store an empty or truncated generation as a Wiki page. */
public final class CompleteTextChatModel implements ChatModel {
    private final ChatModel delegate;

    public CompleteTextChatModel(ChatModel delegate) { this.delegate = Objects.requireNonNull(delegate); }

    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

    @Override public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        if (response == null || response.getResults() == null || response.getResults().isEmpty())
            throw new IllegalStateException("The model returned no text generation.");
        for (var generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null
                    || generation.getOutput().getText() == null || generation.getOutput().getText().isBlank())
                throw new IllegalStateException("The model returned an empty text generation.");
            if (generation.getMetadata() != null
                    && "length".equalsIgnoreCase(generation.getMetadata().getFinishReason()))
                throw new IllegalStateException("The model output was truncated; adjust the input or output token limit.");
        }
        // Return the original response so usage, finish reason, and other metadata remain available.
        return response;
    }
}
