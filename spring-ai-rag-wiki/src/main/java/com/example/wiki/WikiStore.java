package com.example.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import static com.example.wiki.WikiTypes.*;

/** Drafts and published snapshots are separate; publication never rewrites an old release. */
public final class WikiStore {
    static final int MAX_DOCUMENT_CHARS = 14000;
    private final Path root;
    private final ObjectMapper json;
    public WikiStore(Path root, ObjectMapper json) { this.root = root; this.json = json; }

    public static List<Source> readSources(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            var sources = new ArrayList<Source>();
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                var bytes = Files.readAllBytes(path);
                sources.add(new Source(path.getFileName().toString(), new String(bytes, StandardCharsets.UTF_8), sha256(bytes)));
            }
            if (sources.isEmpty()) throw new IllegalArgumentException("원문 Markdown 파일이 없습니다: " + directory);
            return List.copyOf(sources);
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void requireId(String id) {
        if (id == null || !id.matches("[a-z0-9가-힣][a-z0-9가-힣-]{0,79}")
                || id.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))
            throw new IllegalArgumentException("ID는 한글·소문자 영문·숫자·하이픈 1~80자이며 운영체제 예약 이름을 제외합니다: " + id);
    }

    public String saveDraft(Draft draft, List<Source> sources) throws IOException {
        if (draft == null || draft.pages() == null || draft.pages().isEmpty() || draft.pages().size() > 12)
            throw new IllegalArgumentException("컴파일 결과에는 1~12개 페이지가 필요합니다.");
        var known = new HashSet<>(sources.stream().map(Source::id).toList());
        var ids = new HashSet<String>();
        for (Page page : draft.pages()) {
            requireId(page.id());
            if (!ids.add(page.id()) || page.title() == null || page.title().isBlank()
                    || page.markdown() == null || page.markdown().isBlank() || page.markdown().length() > 12000
                    || page.sourceIds() == null || page.sourceIds().isEmpty() || !known.containsAll(page.sourceIds()))
                throw new IllegalArgumentException("페이지 형식 또는 원문 ID를 확인하세요: " + page.id());
        }
        String id = UUID.randomUUID().toString();
        Path directory = root.resolve("drafts").resolve(id);
        Files.createDirectories(directory);
        for (Page page : draft.pages()) {
            Files.writeString(directory.resolve(page.id() + ".md"), "# " + page.title() + "\n\n" + page.markdown() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        var entries = draft.pages().stream().map(p -> new Entry(p.id(), p.title(), List.copyOf(p.sourceIds()))).toList();
        var manifest = new Manifest(id, Instant.now().toString(), sources, entries);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("manifest.json").toFile(), manifest);
        return id;
    }

    public String publish(String draftId) throws IOException {
        requireId(draftId);
        Path draft = root.resolve("drafts").resolve(draftId);
        // Check the retrieval file contract before replacing the current release.
        Snapshot snapshot = readSnapshot(draft);
        String release = UUID.randomUUID().toString();
        Path target = root.resolve("releases").resolve(release);
        Files.createDirectories(target);
        for (var item : snapshot.contents().entrySet()) Files.writeString(target.resolve(item.getKey() + ".md"), item.getValue());
        json.writerWithDefaultPrettyPrinter().writeValue(target.resolve("manifest.json").toFile(), snapshot.manifest());
        Path pointer = Files.createTempFile(root, "current-", ".tmp");
        Files.writeString(pointer, release);
        try { Files.move(pointer, root.resolve("current.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(pointer, root.resolve("current.txt"), StandardCopyOption.REPLACE_EXISTING); }
        return release;
    }

    private Manifest readManifest(Path directory) throws IOException {
        return json.readValue(directory.resolve("manifest.json").toFile(), Manifest.class);
    }

    private record Snapshot(Manifest manifest, Map<String, String> contents, Map<String, String> documentTexts) { }

    private Snapshot readSnapshot(Path directory) throws IOException {
        Manifest manifest = readManifest(directory);
        if (manifest == null || manifest.sources() == null || manifest.sources().isEmpty()
                || manifest.pages() == null || manifest.pages().isEmpty() || manifest.pages().size() > 12)
            throw new IllegalArgumentException("원문과 1~12개 페이지를 포함한 manifest가 필요합니다.");
        var sources = new HashMap<String, Source>();
        for (Source source : manifest.sources()) {
            if (source == null || source.id() == null || source.id().isBlank() || source.text() == null
                    || source.sha256() == null || source.sha256().isBlank() || sources.putIfAbsent(source.id(), source) != null)
                throw new IllegalArgumentException("manifest의 원문 필드 또는 중복 ID를 확인하세요.");
        }
        var contents = new LinkedHashMap<String, String>();
        var documentTexts = new LinkedHashMap<String, String>();
        for (Entry page : manifest.pages()) {
            if (page == null) throw new IllegalArgumentException("manifest의 페이지 항목이 비어 있습니다.");
            requireId(page.id());
            if (contents.containsKey(page.id()) || page.title() == null || page.title().isBlank()
                    || page.sourceIds() == null || page.sourceIds().isEmpty() || !sources.keySet().containsAll(page.sourceIds()))
                throw new IllegalArgumentException("페이지 형식 또는 원문 ID를 확인하세요: " + page.id());
            Path file = directory.resolve(page.id() + ".md");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new NoSuchFileException(file.toString(), null, "일반 Markdown 파일이 필요합니다.");
            String text = Files.readString(file);
            if (text.isBlank()) throw new IllegalArgumentException("페이지 본문이 비어 있습니다: " + page.id());
            var citations = page.sourceIds().stream().map(id -> id + " (sha256=" + sources.get(id).sha256() + ")").toList();
            String documentText = text + "\n\n원문 참조: " + String.join("; ", citations);
            if (documentText.length() > MAX_DOCUMENT_CHARS)
                throw new IllegalArgumentException("본문과 출처를 포함한 페이지 크기 제한 " + MAX_DOCUMENT_CHARS + "자 초과: " + page.id());
            contents.put(page.id(), text);
            documentTexts.put(page.id(), documentText);
        }
        return new Snapshot(manifest, contents, documentTexts);
    }

    public List<Document> documents() throws IOException {
        Path pointer = root.resolve("current.txt");
        if (!Files.exists(pointer)) return List.of();
        String release = Files.readString(pointer).strip();
        requireId(release);
        Path directory = root.resolve("releases").resolve(release);
        Snapshot snapshot = readSnapshot(directory);
        var documents = new ArrayList<Document>();
        for (Entry page : snapshot.manifest().pages()) {
            // The default Spring AI formatter sends text, not metadata. Put source labels in both.
            documents.add(Document.builder().id(page.id())
                    .text(snapshot.documentTexts().get(page.id()))
                    .metadata(Map.of("title", page.title(), "sources", page.sourceIds(), "release", release)).build());
        }
        return List.copyOf(documents);
    }
}
