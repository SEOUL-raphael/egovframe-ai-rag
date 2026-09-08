package com.example.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

/** Uses the real Boot configuration and Ollama HTTP adapter with a deterministic HTTP fixture.
 * This tests transport and file lifecycle, not LLM quality. */
class OllamaTransportTest {
    @TempDir Path directory;

    @Test void cliCompilesPublishesSearchesAndAnswersThroughRealOllamaAdapter() throws Exception {
        var json = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            boolean compilation = json.readTree(request).path("format").asText().equals("json");
            String content = compilation ? """
                    {"pages":[{"id":"eligibility","title":"신청 대상","markdown":"지원금을 전액 반환하면 재신청할 수 있다.","sourceIds":["guide.md"]}]}
                    """ : "지원금을 전액 반환하면 재신청할 수 있습니다. [guide.md]";
            byte[] response = json.writeValueAsBytes(Map.of("model", "transport-fixture", "created_at", "2026-09-07T00:00:00Z",
                    "message", Map.of("role", "assistant", "content", content), "done", true, "done_reason", "stop"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            Path sources = directory.resolve("sources"); Files.createDirectories(sources);
            Files.writeString(sources.resolve("guide.md"), "지원금을 전액 반환한 사람은 재신청할 수 있다.");
            Path workspace = directory.resolve("workspace");
            var base = List.of("--debug=false", "--spring.config.location=classpath:/application.yml",
                    "--spring.config.additional-location=", "--spring.config.import=", "--spring.profiles.include=",
                    "--spring.profiles.active=ollama", "--spring.ai.model.chat=ollama", "--spring.ai.model.embedding=none",
                    "--spring.ai.ollama.init.pull-model-strategy=never", "--spring.ai.ollama.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "--spring.ai.ollama.chat.options.model=transport-fixture", "--wiki.workspace=" + workspace,
                    "--wiki.sources=" + sources);
            run(base, "--wiki.action=compile");
            String draft;
            try (var drafts = Files.list(workspace.resolve("drafts"))) { draft = drafts.findFirst().orElseThrow().getFileName().toString(); }
            assertThat(Files.exists(workspace.resolve("current.txt"))).isFalse();
            run(base, "--wiki.action=publish", "--wiki.draft=" + draft);
            run(base, "--wiki.action=search", "--wiki.query=재신청");
            run(base, "--wiki.action=ask", "--wiki.query=재신청");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).contains("SOURCE_ID: guide.md", "sourceIds");
            assertThat(requests.get(1)).contains("원문 참조: guide.md", "전액 반환");
            assertThat(json.readTree(requests.get(1)).path("format").asText()).isNotEqualTo("json");
        } finally { server.stop(0); }
    }

    private void run(List<String> base, String... action) {
        var args = new ArrayList<>(base); args.addAll(List.of(action));
        // Environment profile includes are additive; isolate the fixture before Boot loads configuration.
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var application = new SpringApplication(WikiApplication.class);
        application.setEnvironment(environment);
        try (var context = application.run(args.toArray(String[]::new))) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("ollama");
            assertThat(context.getEnvironment().getProperty("spring.config.import")).isEmpty();
        }
    }
}
