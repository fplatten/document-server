package com.culture.mcp.document.service;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.analysis.TokenStream;

import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Intent-aware query builder that:
 *  - Loads & applies synonyms for text fields
 *  - Preprocesses large stacktraces to extract signals
 *  - Builds tolerant Lucene queries (Term/Prefix/Fuzzy + boosts)
 *
 * Usage:
 *   SmartQueryBuilder qb = SmartQueryBuilder.withSynonyms();    // loads synonyms/synonyms.txt
 *   SmartQueryBuilder.Built built = qb.build(userText);
 *   Query q = built.query();
 *   // run with IndexSearcher / SearcherManager as usual
 */
public final class SmartQueryBuilder {

    private static final java.util.regex.Pattern EXC_OR_ERR_P =
            java.util.regex.Pattern.compile("\\b([A-Z][a-zA-Z]+(?:Exception|Error))\\b");

    private static final java.util.Set<String> OOME_TOKENS =
            java.util.Set.of("outofmemoryerror","oome");

    private static final java.util.List<java.util.List<String>> OOME_PHRASES =
            java.util.List.of(
                    java.util.List.of("java","heap","space"),
                    java.util.List.of("gc","overhead","limit","exceeded")
            );

    private static final String[] DOMAIN_STOP_WORDS = {
            "error","errors","exception","exceptions","failed","failure","fail",
            "stack","trace","stacktrace","issue","problem","during","when","app","application"
    };

    // ----------------------- Public API -----------------------

    public enum Intent { TROUBLESHOOT, HOWTO, UNKNOWN }

    public record Built(Query query, Intent intent) {}

    /** Factory: create a builder with a per-field analyzer that includes synonyms for text fields. */
    public static SmartQueryBuilder withSynonyms() throws Exception {
        var synMap     = SynonymSupport.loadSynonyms("synonyms/synonyms.txt");

        // Build stopwords = English + domain stopwords
        CharArraySet stops = new CharArraySet(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, true);
        for (String w : DOMAIN_STOP_WORDS) stops.add(w);

        var textWithSyns = SynonymSupport.synonymTextAnalyzer(synMap, stops);
        var defaultAnalyzer = new StandardAnalyzer();

        // Only text fields get synonyms; exact-id-ish fields stay Keyword
        Map<String, Analyzer> perField = new HashMap<>();
        perField.put("title",     textWithSyns);
        perField.put("category",  textWithSyns);
        perField.put("content",   textWithSyns);
        perField.put("notes",     textWithSyns);
        perField.put("tags",      textWithSyns);
        perField.put("catchAll",  textWithSyns);

        perField.put("id",        new KeywordAnalyzer());
        perField.put("relatedTo", new KeywordAnalyzer());
        perField.put("docType",   new KeywordAnalyzer()); // exact values like "Troubleshooting", "Installation"

        Analyzer analyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, perField);
        return new SmartQueryBuilder(analyzer);
    }

    public SmartQueryBuilder(Analyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    /** Build an intent-aware Lucene Query from free-form user text (errors or how-to). */
    public Built build(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new Built(new BooleanQuery.Builder().build(), Intent.UNKNOWN);
        }

        String pre  = preprocessForSearch(rawInput);
        String norm = pre.toLowerCase(java.util.Locale.ROOT);
        Intent intent = detectIntent(norm);

        // 1) STRONG SIGNALS (Exception OR Error, + deepest cause)
        // Make sure you use the (Exception|Error) pattern:
        Set<String> excOrErr = extract(EXC_OR_ERR_P, rawInput)
                .stream().collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        deepestCause(rawInput).ifPresent(dc -> excOrErr.add(dc.replaceFirst(":.*$", "")));

        // 2) OTHER SIGNALS
        Set<String> httpCodes = extract(HTTP_P, rawInput);
        Set<String> packages  = extract(PKG_P,  rawInput);
        Set<String> phases    = java.util.Arrays.stream(norm.split("\\W+"))
                .filter(PHASE_TERMS::contains)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        // 3) TOKENIZE with synonyms (caps total)
        java.util.LinkedHashSet<String> tokens = analyzeToTokens("content", pre, MAX_GENERAL_TERMS + 50);
        if (tokens.size() > MAX_GENERAL_TERMS) {
            tokens = tokens.stream().limit(MAX_GENERAL_TERMS)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        // include leaf package names
        for (String pkg : packages) {
            String[] parts = pkg.split("\\.");
            if (parts.length > 0) tokens.add(parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT));
            if (parts.length > 1) tokens.add(parts[parts.length - 2].toLowerCase(java.util.Locale.ROOT));
        }

        // OOME detection
        boolean mentionsOome = norm.contains("outofmemoryerror") || norm.contains("oome")
                || norm.contains("java heap space") || norm.contains("gc overhead limit exceeded");

        // 4) BUILD QUERY
        org.apache.lucene.search.BooleanQuery.Builder root = new org.apache.lucene.search.BooleanQuery.Builder();
        boolean addedMust = false;

        // 4a) docType bias as a SHOULD (but don't let it satisfy minShouldMatch alone)
        boolean docTypeBiasAdded = false;
        if (intent == Intent.TROUBLESHOOT) {
            root.add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("docType", "Troubleshooting")),
                    org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            docTypeBiasAdded = true;
        } else if (intent == Intent.HOWTO) {
            root.add(termsAny("docType",
                            java.util.Arrays.asList("HowTo","Installation","Calibration","Maintenance","Playbook","Replacement")),
                    org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            docTypeBiasAdded = true;
        }

        // 4b) MUST: exception/error names if present
        if (!excOrErr.isEmpty()) {
            java.util.Collection<String> excTerms = expandExceptions(excOrErr);
            org.apache.lucene.search.Query exQ =
                    dismaxOnFields(excTerms, java.util.Arrays.asList("title","content","tags","notes"), 0.1f);
            root.add(exQ, org.apache.lucene.search.BooleanClause.Occur.MUST);
            addedMust = true;
        }

        // 4c) MUST: OOME signals if present
        if (mentionsOome || tokens.contains("outofmemoryerror") || tokens.contains("oome")) {
            org.apache.lucene.search.Query oomeQ =
                    oomeMustQuery(java.util.Arrays.asList("title","content","notes","tags"));
            root.add(oomeQ, org.apache.lucene.search.BooleanClause.Occur.MUST);
            addedMust = true;
        }

        // 4d) SHOULD groups (non-bias)
        int nonBiasShoulds = 0;
        if (!httpCodes.isEmpty()) {
            root.add(dismaxOnFields(httpCodes, java.util.Arrays.asList("content","notes","title","tags"), 0f),
                    org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            nonBiasShoulds++;
        }
        if (!phases.isEmpty()) {
            root.add(dismaxOnFields(phases, java.util.Arrays.asList("content","notes","tags","title"), 0f),
                    org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            nonBiasShoulds++;
        }
        if (!tokens.isEmpty()) {
            root.add(weightedFieldsQuery(tokens, intent),
                    org.apache.lucene.search.BooleanClause.Occur.SHOULD);
            nonBiasShoulds++;
        }

        // 4e) minShouldMatch: if there's NO MUST, make sure docType bias alone can't match
        if (!addedMust) {
            if (docTypeBiasAdded && nonBiasShoulds > 0) {
                root.setMinimumNumberShouldMatch(2); // require docType + at least one real signal
            } else if (nonBiasShoulds > 0) {
                root.setMinimumNumberShouldMatch(1); // at least one real signal
            } else {
                // Only docType bias present and no other signals → match nothing (or set to 1 to allow list-by-type)
                root.setMinimumNumberShouldMatch(2); // impossible → 0 results
            }
        } else {
            // MUSTs exist; ensure at least one SHOULD helps ranking if present
            if (docTypeBiasAdded || nonBiasShoulds > 0) {
                root.setMinimumNumberShouldMatch(1);
            }
        }

        return new Built(root.build(), intent);
    }


    // ----------------------- Implementation details -----------------------

    private final Analyzer analyzer;

    // Tunables
    private static final int MAX_CHARS = 60_000;
    private static final int MAX_FRAMES = 24;
    private static final int MAX_GENERAL_TERMS = 40;

    private static final Set<String> PHASE_TERMS = Set.of("startup", "start", "boot", "initialize", "initialization", "init", "shutdown");
    private static final Set<String> HOWTO_TRIGGERS = Set.of(
            "how", "howto", "how-to", "install", "installation", "configure", "configuration",
            "setup", "set", "set up", "calibrate", "calibration", "replace", "upgrade", "update",
            "clean", "reset", "connect", "deploy", "enable", "disable"
    );

    private static final Pattern EXCEPTION_P = Pattern.compile("\\b([A-Z][a-zA-Z]+Exception)\\b");
    private static final Pattern HTTP_P      = Pattern.compile("\\b(4\\d\\d|5\\d\\d)\\b");
    private static final Pattern PKG_P       = Pattern.compile("\\b([a-z]+\\.[\\w\\.]+)\\b");
    private static final Pattern FRAME_P     = Pattern.compile("^\\s*at\\s+([\\w$.]+)\\.[\\w$<>]+\\(.*\\)$");
    private static final Pattern CAUSED_BY_P = Pattern.compile("^Caused by:\\s+(.+)$");

    private Intent detectIntent(String norm) {
        boolean hasHow = HOWTO_TRIGGERS.stream().anyMatch(norm::contains);
        boolean hasErr = norm.contains("error") || norm.contains("exception") || norm.contains("fail") || norm.contains("stack");
        if (hasHow && !hasErr) return Intent.HOWTO;
        if (hasErr) return Intent.TROUBLESHOOT;
        return Intent.UNKNOWN;
    }

    private static Set<String> extract(Pattern p, String text) {
        if (text == null) return Collections.emptySet();
        var m = p.matcher(text);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Preprocess large logs/stacktraces: cap size, keep key lines, top frames, deepest cause. */
    private String preprocessForSearch(String input) {
        String s = input;

        // cap (keep head+tail so we likely keep deepest 'Caused by')
        if (s.length() > MAX_CHARS) {
            String head = s.substring(0, MAX_CHARS / 2);
            String tail = s.substring(s.length() - MAX_CHARS / 2);
            s = head + "\n...\n" + tail;
        }

        String[] lines = s.split("\\R");
        List<String> kept = new ArrayList<>(Math.min(lines.length, 256));
        int frames = 0;
        String lastCause = null;

        for (String ln : lines) {
            if (ln.startsWith("Caused by:")) {
                kept.add(ln);
                lastCause = ln;
                continue;
            }
            if (ln.contains("Exception:") || ln.contains("ERROR") || ln.contains("FATAL")) {
                kept.add(ln);
                continue;
            }
            if (frames < MAX_FRAMES && FRAME_P.matcher(ln).find()) {
                kept.add(ln);
                frames++;
                continue;
            }
            if (ln.startsWith("Suppressed:")) {
                kept.add(ln);
            }
        }

        if (lastCause != null && kept.stream().noneMatch(lastCause::equals)) kept.add(lastCause);
        kept.removeIf(ln -> ln.contains("... ") && ln.contains(" more"));

        return kept.isEmpty() ? s : String.join("\n", kept);
    }

    private Optional<String> deepestCause(String input) {
        String cause = null;
        for (String ln : input.split("\\R")) {
            var m = CAUSED_BY_P.matcher(ln);
            if (m.find()) cause = m.group(1);
        }
        return Optional.ofNullable(cause);
    }

    /** Analyze text into tokens using the builder's analyzer (applies synonyms). */
    private LinkedHashSet<String> analyzeToTokens(String field, String text, int maxTokens) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            TokenStream ts = analyzer.tokenStream(field, new StringReader(text));
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
                if (out.size() >= maxTokens) break;
            }
            ts.end();
            ts.close();
        } catch (Exception ignore) {
            // best-effort; fall back to naive split
            Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\W+"))
                    .filter(t -> t.length() >= 3)
                    .limit(maxTokens)
                    .forEach(out::add);
        }
        return out;
    }

    /** Expand exception names with common variants that may not be in synonyms file. */
    private Collection<String> expandExceptions(Collection<String> excs) {
        Set<String> out = new LinkedHashSet<>();
        for (String e : excs) {
            out.add(e.toLowerCase(Locale.ROOT));
            if ("NullPointerException".equalsIgnoreCase(e)) {
                out.addAll(Arrays.asList("npe", "nullpointerexception", "null", "pointer"));
            } else if ("SSLHandshakeException".equalsIgnoreCase(e)) {
                out.addAll(Arrays.asList("ssl", "pkix", "certificate", "truststore", "handshake", "tls"));
            } else if ("MismatchedInputException".equalsIgnoreCase(e)) {
                out.addAll(Arrays.asList("jackson", "json", "deserialize", "deserialization"));
            }
        }
        // limit just in case
        if (out.size() > 24) {
            return out.stream().limit(24).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return out;
    }

    // ----------------------- Query builders -----------------------

    /** Dismax across fields: for each field, OR the variants; across fields, DisjunctionMax with tie. */
    private Query dismaxOnFields(Collection<String> terms, Collection<String> fields, float tie) {
        List<Query> perField = new ArrayList<>(fields.size());
        for (String f : fields) {
            List<Query> variants = new ArrayList<>();
            for (String t : terms) {
                String tok = t.toLowerCase(Locale.ROOT);
                variants.add(new TermQuery(new Term(f, tok)));
                if (tok.length() >= 4) variants.add(new PrefixQuery(new Term(f, tok)));
                if (tok.length() >= 5) variants.add(new FuzzyQuery(new Term(f, tok), 1));
            }
            perField.add(new DisjunctionMaxQuery(variants, 0.0f));
        }
        return new DisjunctionMaxQuery(perField, tie);
    }

    /** Weighted query over known text fields; boosts differ by intent. */
    private Query weightedFieldsQuery(Collection<String> terms, Intent intent) {
        Map<String, Float> boosts = (intent == Intent.HOWTO)
                ? Map.of("title", 3f, "category", 2f, "tags", 1.5f, "content", 1f, "notes", 0.75f, "catchAll", 0.5f)
                : Map.of("title", 3f, "content", 1.5f, "tags", 1.25f, "category", 1.25f, "notes", 0.75f, "catchAll", 0.5f);

        List<Query> perField = new ArrayList<>(boosts.size());
        for (Map.Entry<String, Float> e : boosts.entrySet()) {
            String f = e.getKey();
            float boost = e.getValue();
            List<Query> variants = new ArrayList<>();

            for (String t : terms) {
                String tok = t.toLowerCase(Locale.ROOT);
                variants.add(new BoostQuery(new TermQuery(new Term(f, tok)), boost));
                if (tok.length() >= 4) variants.add(new BoostQuery(new PrefixQuery(new Term(f, tok)), boost * 0.8f));
                if (tok.length() >= 5) variants.add(new BoostQuery(new FuzzyQuery(new Term(f, tok), 1), boost * 0.7f));
            }
            perField.add(new DisjunctionMaxQuery(variants, 0.0f));
        }
        return new DisjunctionMaxQuery(perField, 0.1f);
    }

    /** OR any of the exact values for one field (for docType bias). */
    private Query termsAny(String field, Collection<String> exactValues) {
        List<Query> qs = new ArrayList<>(exactValues.size());
        for (String v : exactValues) {
            qs.add(new TermQuery(new Term(field, v)));
        }
        return new DisjunctionMaxQuery(qs, 0f);
    }

    private Query oomeMustQuery(java.util.Collection<String> fields) {
        java.util.List<Query> fieldQs = new java.util.ArrayList<>();
        for (String f : fields) {
            java.util.List<Query> parts = new java.util.ArrayList<>();
            // exact tokens
            for (String t : OOME_TOKENS) {
                parts.add(new TermQuery(new Term(f, t)));
                parts.add(new PrefixQuery(new Term(f, t)));   // outofmemoryerror* covers variations
            }
            // phrases
            for (java.util.List<String> phrase : OOME_PHRASES) {
                PhraseQuery.Builder pb = new PhraseQuery.Builder();
                for (String p : phrase) pb.add(new Term(f, p));
                pb.setSlop(1);
                parts.add(pb.build());
            }
            fieldQs.add(new DisjunctionMaxQuery(parts, 0f));
        }
        return new DisjunctionMaxQuery(fieldQs, 0.1f);
    }

}
