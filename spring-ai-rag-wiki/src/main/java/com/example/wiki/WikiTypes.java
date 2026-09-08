package com.example.wiki;

import java.util.List;

/** File contracts. Source IDs refer to original Markdown filenames. */
public final class WikiTypes {
    private WikiTypes() { }
    public record Source(String id, String text, String sha256) { }
    public record Page(String id, String title, String markdown, List<String> sourceIds) { }
    public record Draft(List<Page> pages) { }
    public record Entry(String id, String title, List<String> sourceIds) { }
    public record Manifest(String id, String createdAt, List<Source> sources, List<Entry> pages) { }
}
