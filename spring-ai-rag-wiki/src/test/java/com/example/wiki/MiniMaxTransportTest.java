package com.example.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class MiniMaxTransportTest {
    @TempDir Path directory;

    @Test void minimaxProfileUsesNativeEndpointAndCommonWikiPipeline() throws Exception {
        var json = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<String>();
        var authorization = new CopyOnWriteArrayList<String>();
        var lengths = new CopyOnWriteArrayList<Long>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/text/chatcompletion_v2", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            lengths.add(Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length")));
            String content = requests.size() == 1 ? """
                    {"pages":[{"id":"eligibility","title":"신청 대상","markdown":"반환한 경우 재신청할 수 있다.","sourceIds":["guide.md"]}]}
                    """ : "반환한 경우 재신청할 수 있습니다. [guide.md]";
            byte[] response = json.writeValueAsBytes(Map.of("base_resp", Map.of("status_code", 0),
                    "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", content,
                            "reasoning_content", "not part of the public output")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            Path sources = directory.resolve("sources"); Files.createDirectories(sources);
            Files.writeString(sources.resolve("guide.md"), "반환한 경우 재신청할 수 있다.");
            Path workspace = directory.resolve("workspace");
            var base = List.of("--debug=false", "--spring.config.location=classpath:/application.yml",
                    "--spring.config.additional-location=", "--spring.config.import=", "--spring.profiles.include=",
                    "--spring.profiles.active=minimax", "--spring.ai.model.chat=none", "--spring.ai.model.embedding=none",
                    "--spring.ai.ollama.init.pull-model-strategy=never", "--wiki.minimax.api-key=test-only-key",
                    "--wiki.minimax.api-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1/text/chatcompletion_v2",
                    "--wiki.minimax.model=test-model", "--wiki.workspace=" + workspace, "--wiki.sources=" + sources);
            run(base, "--wiki.action=compile");
            String draft;
            try (var drafts = Files.list(workspace.resolve("drafts"))) { draft = drafts.findFirst().orElseThrow().getFileName().toString(); }
            run(base, "--wiki.action=publish", "--wiki.draft=" + draft);
            run(base, "--wiki.action=search", "--wiki.query=재신청");
            run(base, "--wiki.action=ask", "--wiki.query=재신청");
            assertThat(requests).hasSize(2);
            assertThat(authorization).containsOnly("Bearer test-only-key");
            assertThat(lengths).allMatch(length -> length > 0);
            assertThat(json.readTree(requests.get(0)).path("model").asText()).isEqualTo("test-model");
            assertThat(requests.get(0)).contains("sourceIds");
            assertThat(requests.get(1)).contains("원문 참조: guide.md").doesNotContain("not part of the public output");
        } finally { server.stop(0); }
    }

    @Test void nativeHttp200ErrorIsNotTreatedAsSuccessfulGeneration() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"base_resp\":{\"status_code\":1008,\"status_msg\":\"example failure\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var model = new MiniMaxChatModel(RestClient.builder(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"), "test-only", "test", 100);
            assertThatThrownBy(() -> model.call(new Prompt("질문"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("1008");
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
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("minimax");
            assertThat(context.getEnvironment().getProperty("spring.config.import")).isEmpty();
        }
    }
}
