package com.example.wiki;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiki services use Spring AI contracts independently of the model connection. */
@Configuration
public class ModelConfiguration {
    @Bean("compilerClient")
    ChatClient compilerClient(ChatModel model) { return ChatClient.builder(model).build(); }

    @Bean("answerClient")
    ChatClient answerClient(ChatModel model) { return ChatClient.builder(model).build(); }
}
