package com.example.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;
import java.nio.file.*;
import java.util.List;
import static com.example.wiki.WikiTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WikiFlowTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private WikiStore store() { return new WikiStore(directory.resolve("workspace"), json); }
    private List<Source> sources() { return List.of(new Source("guide.md", "반환한 사람은 재신청 가능", "test-sha")); }
    private Draft draft(String text) {
        return new Draft(List.of(new Page("eligibility", "신청 대상", text, List.of("guide.md"))));
    }
    private ChatModel model(String response) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
        return model;
    }

    @Test void compileProducesEditableLinkedPagesWithoutPublishing() throws Exception {
        Path originals = directory.resolve("originals"); Files.createDirectories(originals);
        Files.writeString(originals.resolve("guide.md"), "지원금을 반환한 경우 재신청이 가능하다.");
        var result = new Draft(List.of(
                new Page("eligibility", "신청 대상", "재신청은 [[exceptions|예외 조건]]을 참고한다.", List.of("guide.md")),
                new Page("exceptions", "예외 조건", "반환한 경우 재신청이 가능하다.", List.of("guide.md"))));
        ChatModel model = model(json.writeValueAsString(result));
        String id = new WikiCompiler(ChatClient.builder(model).build(), store()).compile(originals);
        assertThat(store().documents()).isEmpty();
        Path draftPath = directory.resolve("workspace/drafts").resolve(id);
        assertThat(Files.readString(draftPath.resolve("eligibility.md"))).contains("[[exceptions|예외 조건]]");
        Manifest manifest = json.readValue(draftPath.resolve("manifest.json").toFile(), Manifest.class);
        assertThat(manifest.sources().get(0).sha256()).hasSize(64);
        assertThat(manifest.sources().get(0).text()).contains("반환");
        var captor = ArgumentCaptor.forClass(Prompt.class); verify(model).call(captor.capture());
        assertThat(captor.getValue().getContents()).contains("SOURCE_ID: guide.md", "sourceIds", "JSON");
    }

    @Test void publicationPreservesOldReleaseAndIncludesHumanEdits() throws Exception {
        WikiStore store = store();
        String draft = store.saveDraft(draft("원래 설명"), sources());
        String first = store.publish(draft);
        Files.writeString(directory.resolve("workspace/drafts").resolve(draft).resolve("eligibility.md"), "# 신청 대상\n사람이 수정한 설명");
        assertThat(store.documents().get(0).getText()).contains("원래 설명").doesNotContain("사람이 수정");
        String second = store.publish(draft);
        assertThat(second).isNotEqualTo(first);
        assertThat(store.documents().get(0).getText()).contains("사람이 수정한 설명", "guide.md", "test-sha");
        assertThat(Files.readString(directory.resolve("workspace/releases").resolve(first).resolve("eligibility.md"))).contains("원래 설명");
    }

    @Test void failedPublicationKeepsCurrentRelease() throws Exception {
        WikiStore store = store();
        String draft = store.saveDraft(draft("현재 설명"), sources());
        String release = store.publish(draft);
        Files.delete(directory.resolve("workspace/drafts").resolve(draft).resolve("eligibility.md"));
        assertThatThrownBy(() -> store.publish(draft)).isInstanceOf(NoSuchFileException.class);
        assertThat(Files.readString(directory.resolve("workspace/current.txt"))).isEqualTo(release);
    }

    @Test void invalidEditedPagesCannotReplaceCurrentRelease() throws Exception {
        WikiStore store = store();
        String draft = store.saveDraft(draft("현재 설명"), sources());
        String release = store.publish(draft);
        Path page = directory.resolve("workspace/drafts").resolve(draft).resolve("eligibility.md");
        for (String invalidText : List.of("", " \n\t", "가".repeat(14000), "가".repeat(16001))) {
            Files.writeString(page, invalidText);
            assertThatThrownBy(() -> store.publish(draft)).isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(directory.resolve("workspace/current.txt"))).isEqualTo(release);
            assertThat(store.documents().get(0).getText()).contains("현재 설명");
        }
    }

    @Test void pageAtContextLimitRemainsSearchable() throws Exception {
        WikiStore store = store();
        String draft = store.saveDraft(draft("현재 설명"), sources());
        String citations = "\n\n원문 참조: guide.md (sha256=test-sha)";
        String text = "재신청 " + "가".repeat(14000 - citations.length() - "재신청 ".length());
        Files.writeString(directory.resolve("workspace/drafts").resolve(draft).resolve("eligibility.md"), text);
        store.publish(draft);
        var documents = new WikiDocumentRetriever(store).retrieve(new Query("재신청"));
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).hasSize(14000);
    }

    @Test void invalidManifestCannotReplaceCurrentRelease() throws Exception {
        WikiStore store = store();
        String draft = store.saveDraft(draft("현재 설명"), sources());
        String release = store.publish(draft);
        Path manifestFile = directory.resolve("workspace/drafts").resolve(draft).resolve("manifest.json");
        Manifest original = json.readValue(manifestFile.toFile(), Manifest.class);
        var invalidPages = List.of(
                List.of(new Entry("eligibility", "신청 대상", List.of("unknown.md"))),
                List.of(original.pages().get(0), original.pages().get(0)),
                List.<Entry>of());
        for (List<Entry> pages : invalidPages) {
            json.writeValue(manifestFile.toFile(), new Manifest(original.id(), original.createdAt(), original.sources(), pages));
            assertThatThrownBy(() -> store.publish(draft)).isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.readString(directory.resolve("workspace/current.txt"))).isEqualTo(release);
            assertThat(store.documents().get(0).getText()).contains("현재 설명");
        }
    }

    @Test void generatedPathsAndSourceIdsMustMatchTheFileContract() {
        assertThatThrownBy(() -> store().saveDraft(new Draft(List.of(new Page("../escape", "제목", "본문", List.of("guide.md")))), sources()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().saveDraft(new Draft(List.of(new Page("valid", "제목", "본문", List.of("unknown.md")))), sources()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().saveDraft(new Draft(List.of(new Page("con", "제목", "본문", List.of("guide.md")))), sources()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void koreanWikiIdsSurviveSavingPublicationAndRetrieval() throws Exception {
        WikiStore store = store();
        String id = store.saveDraft(new Draft(List.of(new Page("신청-대상", "신청 대상", "재신청 예외를 참고한다.", List.of("guide.md")))), sources());
        store.publish(id);
        var documents = new WikiDocumentRetriever(store).retrieve(new Query("재신청"));
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getId()).isEqualTo("신청-대상");
    }

    @Test void searchUsesPublishedContentAndReturnsNoUnrelatedPages() throws Exception {
        WikiStore store = store();
        store.publish(store.saveDraft(draft("지원금을 반환하면 재신청할 수 있다."), sources()));
        var retriever = new WikiDocumentRetriever(store);
        assertThat(retriever.retrieve(new Query("재신청 반환"))).hasSize(1);
        assertThat(retriever.retrieve(new Query("천문학"))).isEmpty();
        assertThat(retriever.retrieve(new Query("!"))).isEmpty();
    }

    @Test void actualSpringAdvisorPassesSourcesIntoModelContext() throws Exception {
        WikiStore store = store();
        store.publish(store.saveDraft(draft("지원금을 반환하면 재신청할 수 있다."), sources()));
        ChatModel model = model("반환한 경우 재신청할 수 있습니다. [guide.md]");
        String answer = new WikiAnswerer(ChatClient.builder(model).build(), new WikiDocumentRetriever(store)).answer("재신청 반환");
        assertThat(answer).contains("[guide.md]");
        var captor = ArgumentCaptor.forClass(Prompt.class); verify(model).call(captor.capture());
        assertThat(captor.getValue().getContents()).contains("지원금을 반환하면", "원문 참조: guide.md", "test-sha");
    }

    @Test void actualSpringAdvisorUsesEmptyContextInstruction() {
        ChatModel model = model("검색된 위키 근거가 없어 답변할 수 없습니다.");
        new WikiAnswerer(ChatClient.builder(model).build(), new WikiDocumentRetriever(store())).answer("천문학");
        var captor = ArgumentCaptor.forClass(Prompt.class); verify(model).call(captor.capture());
        assertThat(captor.getValue().getContents()).contains("검색된 위키 근거가 없어");
    }

    @Test void oversizedSourceFailsBeforeAnyModelCall() throws Exception {
        Path originals = directory.resolve("originals"); Files.createDirectories(originals);
        Files.writeString(originals.resolve("guide.md"), "가".repeat(16001));
        ChatModel model = mock(ChatModel.class);
        assertThatThrownBy(() -> new WikiCompiler(ChatClient.builder(model).build(), store()).compile(originals))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("16000");
        verify(model, never()).call(any(Prompt.class));
    }
}
