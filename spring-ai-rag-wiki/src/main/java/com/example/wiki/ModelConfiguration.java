package com.example.wiki;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import java.net.URI;

/** Only this configuration and provider adapters change when deploying another model. */
@Configuration
public class ModelConfiguration {
    @Bean @Profile("minimax")
    ChatModel miniMaxChatModel(Environment env, RestClient.Builder http) {
        return new MiniMaxChatModel(http, URI.create(env.getRequiredProperty("wiki.minimax.api-url")),
                env.getRequiredProperty("wiki.minimax.api-key"), env.getRequiredProperty("wiki.minimax.model"),
                env.getProperty("wiki.minimax.max-tokens", Integer.class, 8192));
    }

    @Bean("compilerClient") @Profile("minimax")
    ChatClient miniMaxCompilerClient(ChatModel model) { return ChatClient.builder(model).build(); }

    @Bean("compilerClient") @Profile("!minimax")
    ChatClient ollamaCompilerClient(ChatModel model) {
        return ChatClient.builder(model).defaultOptions(OllamaOptions.builder().format("json").build()).build();
    }

    @Bean("answerClient")
    ChatClient answerClient(ChatModel model) { return ChatClient.builder(model).build(); }
}
