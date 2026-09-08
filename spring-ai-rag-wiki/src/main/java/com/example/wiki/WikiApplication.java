package com.example.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.rag.Query;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.nio.file.Path;

@SpringBootApplication
public class WikiApplication {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(WikiApplication.class, args)) { /* CLI closes resources */ }
    }

    @Bean CommandLineRunner commands(Environment env, ObjectMapper json,
            @Qualifier("compilerClient") ChatClient compilerClient, @Qualifier("answerClient") ChatClient answerClient) {
        return args -> {
            var store = new WikiStore(Path.of(env.getProperty("wiki.workspace", "workspace")), json);
            switch (env.getProperty("wiki.action", "help")) {
                case "compile" -> {
                    String id = new WikiCompiler(compilerClient, store).compile(Path.of(env.getProperty("wiki.sources", "examples/sources")));
                    System.out.println("DRAFT=" + id + "\n초안 확인 후 --wiki.action=publish --wiki.draft=" + id + " 명령으로 발행하세요.");
                }
                case "publish" -> System.out.println("RELEASE=" + store.publish(env.getRequiredProperty("wiki.draft")));
                case "search" -> {
                    String query = env.getRequiredProperty("wiki.query");
                    var docs = new WikiDocumentRetriever(store).retrieve(new Query(query));
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(docs.stream()
                            .map(d -> java.util.Map.of("id", d.getId(), "text", d.getText(), "metadata", d.getMetadata())).toList()));
                }
                case "ask" -> {
                    System.out.println(new WikiAnswerer(answerClient, new WikiDocumentRetriever(store)).answer(env.getRequiredProperty("wiki.query")));
                }
                default -> System.out.println("""
                        --wiki.action=compile
                        --wiki.action=publish --wiki.draft=<id>
                        --wiki.action=search --wiki.query="재신청 예외"
                        --wiki.action=ask --wiki.query="재신청 예외 조건은 무엇인가요?"
                        선택: --wiki.sources=examples/sources --wiki.workspace=workspace
                        모델 설정: LLM_BASE_URL, LLM_COMPLETIONS_PATH, LLM_API_KEY, LLM_MODEL
                        기본 프로필: chat-model (Chat Completions 호환 API)
                        """);
            }
        };
    }
}
