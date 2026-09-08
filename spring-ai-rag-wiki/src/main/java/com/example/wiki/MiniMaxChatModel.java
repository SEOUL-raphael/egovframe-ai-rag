package com.example.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;

/** Minimal synchronous text adapter for MiniMax's native chatcompletion_v2 endpoint.
 * Provider-specific HTTP details stay behind Spring AI's ChatModel contract. */
public final class MiniMaxChatModel implements ChatModel {
    private final RestClient http;
    private final URI endpoint;
    private final String model;
    private final int maxTokens;

    public MiniMaxChatModel(RestClient.Builder builder, URI endpoint, String apiKey, String model, int maxTokens) {
        if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank())
            throw new IllegalArgumentException("MiniMax API 키와 모델 이름을 설정하세요.");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !("https".equals(endpoint.getScheme())
                || ("http".equals(endpoint.getScheme()) && List.of("127.0.0.1", "localhost").contains(endpoint.getHost()))))
            throw new IllegalArgumentException("MiniMax endpoint는 HTTPS URL이어야 합니다. 로컬 테스트만 HTTP를 허용합니다.");
        this.endpoint = endpoint; this.model = model; this.maxTokens = maxTokens;
        this.http = builder.clone().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .requestInterceptor((request, body, execution) -> {
                    // Buffering here provides a fixed Content-Length to gateways that reject chunked requests.
                    request.getHeaders().setContentLength(body.length);
                    return execution.execute(request, body);
                }).build();
    }

    @Override public ChatResponse call(Prompt prompt) {
        var messages = prompt.getInstructions().stream().map(message -> {
            String role = message.getMessageType().getValue();
            if (!List.of("system", "user", "assistant").contains(role))
                throw new IllegalArgumentException("최소 어댑터는 텍스트 system/user/assistant 메시지만 지원합니다.");
            return Map.of("role", role, "content", Objects.requireNonNullElse(message.getText(), ""));
        }).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model); body.put("messages", messages); body.put("stream", false);
        var options = prompt.getOptions();
        body.put("max_tokens", options != null && options.getMaxTokens() != null ? options.getMaxTokens() : maxTokens);
        if (options != null && options.getTemperature() != null) body.put("temperature", options.getTemperature());
        if (options != null && options.getTopP() != null) body.put("top_p", options.getTopP());
        // Omit provider-specific structured-output flags. ChatClient's entity converter adds a JSON schema instruction.
        JsonNode response = http.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("MiniMax 응답이 비어 있습니다.");
        int status = response.path("base_resp").path("status_code").asInt(0);
        if (status != 0) throw new IllegalStateException("MiniMax 응답 오류 코드: " + status);
        JsonNode choice = response.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asText()))
            throw new IllegalStateException("MiniMax 출력 한도에 도달했습니다. 원문 크기 또는 최대 출력 토큰을 조정하세요.");
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) throw new IllegalStateException("MiniMax 텍스트 응답이 없습니다.");
        // reasoning_content is intentionally not part of the answer or compiler input.
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
