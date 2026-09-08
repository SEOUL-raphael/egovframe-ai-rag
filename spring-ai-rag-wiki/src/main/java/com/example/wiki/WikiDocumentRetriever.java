package com.example.wiki;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

/** Deliberately small lexical retriever. It does not follow wiki links. */
public final class WikiDocumentRetriever implements DocumentRetriever {
    private final WikiStore store;
    public WikiDocumentRetriever(WikiStore store) { this.store = store; }
    private record Hit(Document document, long score) { }
    @Override public List<Document> retrieve(Query query) {
        var terms = Arrays.stream(query.text().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(s -> s.length() >= 2).collect(Collectors.toSet());
        if (terms.isEmpty()) return List.of();
        try {
            var hits = store.documents().stream().map(d -> {
                String text = Objects.requireNonNull(d.getText()).toLowerCase(Locale.ROOT);
                String title = d.getMetadata().get("title").toString().toLowerCase(Locale.ROOT);
                long score = terms.stream().mapToLong(t -> (title.contains(t) ? 3 : 0) + (text.contains(t) ? 1 : 0)).sum();
                return new Hit(d, score);
            }).filter(h -> h.score() > 0)
                    .sorted(Comparator.comparingLong(Hit::score).reversed().thenComparing(h -> h.document().getId())).toList();
            var selected = new ArrayList<Document>();
            int remaining = 14000;
            for (Hit hit : hits) {
                int size = Objects.requireNonNull(hit.document().getText()).length();
                if (size <= remaining) { selected.add(hit.document()); remaining -= size; }
                if (selected.size() == 3) break;
            }
            return List.copyOf(selected);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
