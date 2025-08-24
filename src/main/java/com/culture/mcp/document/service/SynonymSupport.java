package com.culture.mcp.document.service;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.CharArraySet;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class SynonymSupport {
    public static SynonymMap loadSynonyms(String classpathLocation) throws Exception {
        var res = new ClassPathResource(classpathLocation);
        try (var r = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
            // expand=true (bi-directional for comma lists), lenient=true (ignore bad lines)
            var parser = new SolrSynonymParser(true, true, new StandardAnalyzer());
            parser.parse(r);
            return parser.build();
        }
    }

    /** Analyzer that applies: tokenize → lowercase → synonyms → (optional) stopwords */
    public static Analyzer synonymTextAnalyzer(SynonymMap synMap, CharArraySet stopwords) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String field) {
                var src = new StandardTokenizer();
                TokenStream ts = new LowerCaseFilter(src);                // normalize case first
                ts = new SynonymGraphFilter(ts, synMap, true);            // apply synonyms
                if (stopwords != null && !stopwords.isEmpty()) {
                    ts = new StopFilter(ts, stopwords);                    // optional
                }
                return new TokenStreamComponents(src, ts);
            }
        };
    }
}
