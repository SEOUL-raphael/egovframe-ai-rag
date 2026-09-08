package com.example.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real Spring AI transport against local fixtures; this does not measure live model quality. */
class ChatModelTransportTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"/v1/chat/completions, fixture-a, false", "/gateway/chat, fixture-b, true"})
    void cliPipelineChangesConnectionWithoutChangingWikiServices(String path, String model, boolean temperature) throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var authorization = new CopyOnWriteArrayList<String>();
        var lengths = new CopyOnWriteArrayList<Long>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            lengths.add(Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length")));
            String content = requests.size() == 1 ? """
                    {"pages":[{"id":"eligibility","title":"신청 대상","markdown":"지원금을 반환하면 재신청할 수 있다.","sourceIds":["guide.md"]}]}
                    """ : "지원금을 반환하면 재신청할 수 있습니다. [guide.md]";
            byte[] response = completion(content, "stop", model);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Path sources = directory.resolve("sources"); Files.createDirectories(sources);
            Files.writeString(sources.resolve("guide.md"), "지원금을 반환한 사람은 재신청할 수 있다.");
            Path workspace = directory.resolve("workspace");
            var base = new ArrayList<>(List.of("--debug=false", "--spring.config.location=classpath:/application.yml",
                    "--spring.config.additional-location=", "--spring.config.import=", "--spring.profiles.include=",
                    "--wiki.workspace=" + workspace, "--wiki.sources=" + sources));
            if (temperature) {
                // Exercise the public LLM_* mapping and the default chat-model profile.
                base.addAll(List.of("--LLM_API_KEY=test-only-key",
                        "--LLM_BASE_URL=http://127.0.0.1:" + server.getAddress().getPort(),
                        "--LLM_COMPLETIONS_PATH=" + path, "--LLM_MODEL=" + model,
                        "--LLM_MAX_TOKENS=1024", "--LLM_TEMPERATURE=0.2"));
            } else {
                base.addAll(List.of("--spring.profiles.active=chat-model", "--wiki.llm.api-key=test-only-key",
                        "--wiki.llm.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                        "--wiki.llm.completions-path=" + path, "--wiki.llm.model=" + model,
                        "--wiki.llm.max-tokens=1024"));
            }
            run(base, "--wiki.action=compile");
            String draft;
            try (var drafts = Files.list(workspace.resolve("drafts"))) {
                draft = drafts.findFirst().orElseThrow().getFileName().toString();
            }
            assertThat(Files.exists(workspace.resolve("current.txt"))).isFalse();
            run(base, "--wiki.action=publish", "--wiki.draft=" + draft);
            run(base, "--wiki.action=search", "--wiki.query=재신청");
            run(base, "--wiki.action=ask", "--wiki.query=재신청");
            assertThat(requests).hasSize(2);
            assertThat(authorization).containsOnly("Bearer test-only-key");
            for (int i = 0; i < requests.size(); i++) {
                assertThat(lengths.get(i)).isEqualTo((long) requests.get(i).getBytes(StandardCharsets.UTF_8).length);
                var body = json.readTree(requests.get(i));
                assertThat(body.path("model").asText()).isEqualTo(model);
                assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
                assertThat(body.has("response_format")).isFalse();
                assertThat(body.has("format")).isFalse();
                if (temperature) assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
                else assertThat(body.has("temperature")).isFalse();
            }
            assertThat(requests.get(0)).contains("SOURCE_ID: guide.md", "sourceIds");
            assertThat(requests.get(1)).contains("원문 참조: guide.md", "지원금을 반환");
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"empty,no text", "blank,empty text", "length,truncated"})
    void incompleteResponsesAreRejected(String kind, String expected) throws Exception {
        String body = switch (kind) {
            case "empty" -> "{\"id\":\"fixture\",\"choices\":[]}";
            case "blank" -> new String(completion("   ", "stop", "fixture"), StandardCharsets.UTF_8);
            default -> new String(completion("partial text", "length", "fixture"), StandardCharsets.UTF_8);
        };
        withResponse(200, body, (model, count) -> {
            assertThatThrownBy(() -> model.call(new Prompt("question")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(expected);
            assertThat(count.get()).isEqualTo(1);
        });
    }

    @Test void httpFailureDoesNotBecomeAnAnswerOrRetryRepeatedly() throws Exception {
        withResponse(429, "{\"error\":{\"message\":\"fixture failure\",\"type\":\"rate_limit\"}}", (model, count) -> {
            assertThatThrownBy(() -> model.call(new Prompt("question"))).isInstanceOf(RuntimeException.class);
            assertThat(count.get()).isEqualTo(1);
        });
    }

    @Test void successfulResponseRetainsUsageAndGenerationMetadata() throws Exception {
        withResponse(200, new String(completion("complete answer", "stop", "fixture"), StandardCharsets.UTF_8), (model, count) -> {
            var response = model.call(new Prompt("question"));
            assertThat(response.getResult().getOutput().getText()).isEqualTo("complete answer");
            assertThat(response.getResult().getMetadata().getFinishReason()).isEqualToIgnoringCase("stop");
            assertThat(response.getMetadata().getId()).isEqualTo("fixture-response");
            assertThat(response.getMetadata().getModel()).isEqualTo("fixture");
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(12);
        });
    }

    @ParameterizedTest
    @CsvSource({"wiki.llm.base-url,http://remote.example.com", "wiki.llm.base-url,https://gateway.example.com/v1",
            "wiki.llm.completions-path,//remote.example.com/chat", "wiki.llm.completions-path,/chat?key=value",
            "wiki.llm.model, ", "wiki.llm.api-key, ", "wiki.llm.max-tokens,0"})
    void invalidConnectionFailsBeforeAnyRequest(String property, String value) {
        var properties = connectionProperties("https://gateway.example.com", "/chat", "fixture");
        properties.put(property, value == null ? "" : value);
        assertThatThrownBy(() -> configuredModel(properties)).isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] completion(String content, String finishReason, String model) throws com.fasterxml.jackson.core.JsonProcessingException {
        return json.writeValueAsBytes(Map.of("id", "fixture-response", "object", "chat.completion", "created", 1,
                "model", model, "usage", Map.of("prompt_tokens", 7, "completion_tokens", 5, "total_tokens", 12),
                "choices", List.of(Map.of("index", 0, "finish_reason", finishReason,
                        "message", Map.of("role", "assistant", "content", content)))));
    }

    private void withResponse(int status, String body, ModelAssertion assertion) throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes(); count.incrementAndGet();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var properties = connectionProperties("http://127.0.0.1:" + server.getAddress().getPort(), "/chat", "fixture");
            assertion.check(configuredModel(properties), count);
        } finally { server.stop(0); }
    }

    private Map<String, Object> connectionProperties(String base, String path, String model) {
        return new HashMap<>(Map.of("wiki.llm.base-url", base, "wiki.llm.completions-path", path,
                "wiki.llm.api-key", "test-only-key", "wiki.llm.model", model));
    }

    private ChatModel configuredModel(Map<String, Object> properties) {
        var environment = isolatedEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", properties));
        return new ChatCompletionsConfiguration().chatModel(environment, RestClient.builder());
    }

    private StandardEnvironment isolatedEnvironment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }

    private void run(List<String> base, String... action) {
        var args = new ArrayList<>(base); args.addAll(List.of(action));
        var application = new SpringApplication(WikiApplication.class);
        application.setEnvironment(isolatedEnvironment());
        try (var context = application.run(args.toArray(String[]::new))) {
            if (base.contains("--spring.profiles.active=chat-model")) {
                assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("chat-model");
            } else {
                assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
                assertThat(context.getEnvironment().getDefaultProfiles()).containsExactly("chat-model");
            }
            assertThat(context.getEnvironment().getProperty("spring.config.import")).isEmpty();
        }
    }

    @FunctionalInterface private interface ModelAssertion {
        void check(ChatModel model, AtomicInteger count) throws Exception;
    }
}
