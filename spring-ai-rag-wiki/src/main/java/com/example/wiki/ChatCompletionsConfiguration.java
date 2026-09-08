package com.example.wiki;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.List;

/** Connection for servers implementing the Chat Completions API contract. */
@Configuration
@Profile("chat-model")
public class ChatCompletionsConfiguration {
    @Bean
    ChatModel chatModel(Environment env, RestClient.Builder http) {
        String baseUrl = required(env, "wiki.llm.base-url");
        URI base = URI.create(baseUrl);
        boolean localHttp = base.getHost() != null && "http".equals(base.getScheme())
                && List.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !List.of("", "/").contains(base.getPath())
                || !("https".equals(base.getScheme()) || localHttp))
            throw new IllegalArgumentException("LLM_BASE_URL must be an HTTPS origin; loopback HTTP is allowed for local servers.");
        String path = required(env, "wiki.llm.completions-path");
        URI relative = URI.create(path);
        if (!path.startsWith("/") || path.startsWith("//") || relative.isAbsolute()
                || relative.getQuery() != null || relative.getFragment() != null
                || !path.equals(relative.normalize().getRawPath()))
            throw new IllegalArgumentException("LLM_COMPLETIONS_PATH must be an absolute API path without query or fragment.");
        String apiKey = required(env, "wiki.llm.api-key");
        String model = required(env, "wiki.llm.model");
        int maxTokens = env.getProperty("wiki.llm.max-tokens", Integer.class, 8192);
        if (maxTokens <= 0) throw new IllegalArgumentException("LLM_MAX_TOKENS must be positive.");
        var options = OpenAiChatOptions.builder().model(model).maxTokens(maxTokens);
        String temperature = env.getProperty("wiki.llm.temperature");
        if (temperature != null && !temperature.isBlank()) {
            double value = Double.parseDouble(temperature);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("LLM_TEMPERATURE must be finite.");
            options.temperature(value);
        }
        var api = OpenAiApi.builder().baseUrl(baseUrl.replaceAll("/$", ""))
                .completionsPath(path).apiKey(apiKey)
                .restClientBuilder(http.clone().requestInterceptor((request, body, execution) -> {
                    // Some gateways require a fixed Content-Length instead of chunked requests.
                    request.getHeaders().setContentLength(body.length);
                    return execution.execute(request, body);
                })).build();
        var delegate = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return new CompleteTextChatModel(delegate);
    }

    private static String required(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Required model connection property is missing: " + name);
        return value.trim();
    }
}
