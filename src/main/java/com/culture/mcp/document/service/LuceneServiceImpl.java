package com.culture.mcp.document.service;

import com.culture.mcp.document.domain.Document;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of the LuceneService.
 * This class manages the Lucene index and provides methods for adding and searching documents.
 */
@Service
public class LuceneServiceImpl implements LuceneService {

    private static final String INDEX_DIRECTORY_NAME = "lucene-index";
    private static final Path INDEX_DIRECTORY = Paths.get(INDEX_DIRECTORY_NAME);
    private final Directory indexDirectory;
    private final StandardAnalyzer analyzer;
    private IndexWriter indexWriter;
    private final ReentrantLock lock = new ReentrantLock();

    public LuceneServiceImpl() throws IOException {
        // Create the directory if it doesn't exist
        Files.createDirectories(INDEX_DIRECTORY);
        this.indexDirectory = FSDirectory.open(INDEX_DIRECTORY);
        this.analyzer = new StandardAnalyzer();
    }

    /**
     * Initializes the Lucene IndexWriter after the bean is constructed.
     * This ensures the writer is ready for use and a lock is established.
     * @throws IOException if there's an error creating the index writer.
     */
    @PostConstruct
    public void init() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        this.indexWriter = new IndexWriter(indexDirectory, config);
    }

    /**
     * Closes the Lucene IndexWriter before the bean is destroyed.
     * This commits pending changes and releases the lock.
     * @throws IOException if there's an error closing the index writer.
     */
    @PreDestroy
    public void destroy() throws IOException {
        if (indexWriter != null) {
            indexWriter.close();
        }
    }

    /**
     * Adds a single document to the Lucene index.
     * This method is synchronized to ensure thread safety.
     * @param document The document to be added.
     * @throws IOException if there's an error adding the document.
     */
    @Override
    @Tool(
            name = "addDocument",
            description = """
        Adds a single document to the document library index.
        Each document contains a title, category, steps to perform, tags, related topics, and notes. 
        This tool is useful for saving a new task, tutorial, checklist, or reference into the library 
        so it can be retrieved later using a search.
    """
    )
    public void addDocument(Document document) throws IOException {
        lock.lock();
        try {
            org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();

            luceneDoc.add(new StringField("id", document.id(), Store.YES));
            luceneDoc.add(new StringField("docType", document.docType(), Store.YES));
            luceneDoc.add(new TextField("docType_text", document.docType(), Store.YES));
            luceneDoc.add(new TextField("title", document.title(), Store.YES));
            luceneDoc.add(new TextField("category", document.category(), Store.YES));
            luceneDoc.add(new StringField("category_exact", document.category(), Store.YES));
            luceneDoc.add(new TextField("content", document.content(), Store.YES));
            luceneDoc.add(new TextField("notes", document.notes(), Store.YES));

            StringBuilder catchAll = new StringBuilder();
            catchAll.append(document.title()).append(" ");
            catchAll.append(document.docType()).append(" ");
            catchAll.append(document.content()).append(" ");
            catchAll.append(document.notes()).append(" ");

            document.tags().forEach(tag -> {
                luceneDoc.add(new TextField("tags", tag, Store.YES));
                luceneDoc.add(new TextField("tags_exact", tag, Store.YES));
                catchAll.append(tag).append(" ");
            });

            document.relatedTo().forEach(related ->
                    luceneDoc.add(new StringField("relatedTo", related, Store.YES))
            );

            luceneDoc.add(new TextField("catchAll", catchAll.toString(), Store.NO));

            indexWriter.addDocument(luceneDoc);
            indexWriter.commit();
        } finally {
            lock.unlock();
        }
    }


    /**
     * Adds a list of documents to the Lucene index in bulk.
     * @param documents The list of documents to be added.
     * @throws IOException if there's an error adding the documents.
     */
    @Override
    @Tool(
            name = "addBulkDocuments",
            description = """
        Adds multiple documents to the document library in a single operation. 
        Use this when importing a collection of task guides, references, or process steps. 
        Each document should contain fields such as title, category, steps, tags, and related items.
    """
    )
    public void addBulkDocuments(List<Document> documents) throws IOException {
        for (Document doc : documents) {
            addDocument(doc);
        }
    }

    /**
     * Searches the Lucene index using Lucene query.
     * @param luceneQuery The query string.
     * @return A list of matching documents.
     * @throws Exception if there's an error during the search.
     */
    @Override
//    @Tool(
//            name = "search",
//            description = """
//        Searches the document library using Lucene query syntax.
//        You can search by field (e.g., title, category, tags, steps) and use logical operators
//        such as AND, OR, and grouping with parentheses. Supports quoted phrases and wildcards.
//
//        Example queries:
//        - title:"spring boot" AND category:Programming
//        - tags:java OR tags:security
//        - steps:configure*
//
//        Returns documents that match the query across one or more fields.
//    """
//    )
    public List<Document> search(String luceneQuery) throws Exception {
        List<Document> results = new ArrayList<>();
        System.out.println(luceneQuery);

        if (!DirectoryReader.indexExists(indexDirectory)) {
            return results; // empty result if index doesn't exist
        }

        try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            Analyzer analyzer = new PerFieldAnalyzerWrapper(
                    new StandardAnalyzer(),
                    Map.of(
                            "id",         new KeywordAnalyzer(),
                            "relatedTo",  new KeywordAnalyzer(),
                            "docType",    new KeywordAnalyzer()
                            // other fields use StandardAnalyzer
                    )
            );

            // Field boosts
            Map<String,Float> boosts = Map.of(
                    "title",     3f,
                    "category",  2f,
                    "tags",      1.25f,
                    "content",   1f,
                    "notes",     0.75f,
                    "relatedTo", 0.5f,
                    "catchAll",  0.25f
            );

            // Build parser & parse
//            SimpleQueryParser qp = new SimpleQueryParser(analyzer, boosts);
//            qp.setDefaultOperator(BooleanClause.Occur.SHOULD); // tighter matching
//            Query query = qp.parse(luceneQuery);


            QueryParser parser = new MultiFieldQueryParser(
                    new String[] { "docType", "title^3", "category^2", "content", "notes", "tags", "relatedTo", "catchAll^0.5" },
                    analyzer
            );
            Query query = parser.parse(luceneQuery);

            ScoreDoc[] hits = searcher.search(query, 10).scoreDocs;
            System.out.println("Lucene hits: " + hits.length);

            for (ScoreDoc hit : hits) {
                org.apache.lucene.document.Document doc = searcher.doc(hit.doc);
                results.add(mapToDomain(doc));
            }
        }

        return results;
    }

    /**
     * Searches the Lucene index using Lucene query.
     * @param userText The query string.
     * @return A list of matching documents.
     * @throws Exception if there's an error during the search.
     */
    @Override
    @Tool(
            name = "smartSearch",
            description = """
        Search the internal Document Library using FREE-FORM user text (errors or how-to requests).
        Do NOT write Lucene queries yourself—pass raw text; the service builds an intent-aware query.

        Inputs:
        - text: string
        - limit: integer (optional, default 10)
        - offset: integer (optional, default 0)

        Behavior & guidance:
        - Searches fields: title, docType, category, content, notes, tags, relatedTo, catchAll.
        - Returns ranked matches; use `id` from results to fetch and display the FULL document.
        - If no matches, reply EXACTLY:
          "Sorry, I could find no documents. Maybe you could recommend a search I could use."
        - Include concrete tokens (exception names, HTTP codes, equipment, verbs) in `text` when available.
        """
    )
    public List<Document> smartSearch(String userText) throws Exception {

        List<Document> results = new ArrayList<>();
        System.out.println("userText: " + userText);

        SmartQueryBuilder qb = SmartQueryBuilder.withSynonyms();

        SmartQueryBuilder.Built built = qb.build(userText);
        Query query = built.query();
        System.out.println("INTENT : " + built.intent());
        System.out.println("QUERY  : " + query);

        System.out.println("INTENT  : " + built.intent());
        System.out.println("QUERY   : " + query.toString());

        if (!DirectoryReader.indexExists(indexDirectory)) {
            return results; // empty result if index doesn't exist
        }

        try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            ScoreDoc[] hits = searcher.search(query, 10).scoreDocs;
            System.out.println("Lucene hits: " + hits.length);

            for (ScoreDoc hit : hits) {
                org.apache.lucene.document.Document doc = searcher.doc(hit.doc);
                results.add(mapToDomain(doc));
            }
        }

        return results;


    }



    @Tool(
            name = "upsertDocument",
            description = """
        Create or replace a document at the given docId (atomic delete+add).
        Inputs: full document payload including docId and all fields to keep.
        Behavior: Replaces the entire document. Fields omitted from the payload will no longer exist.
        Guidance for caller: Use when changing core content (title/procedure/notes/tags). Ensure the payload is complete.
        """
    )
    public void upsertDocument(Document document) throws IOException {
        lock.lock();
        try {
            org.apache.lucene.document.Document luceneDoc = buildLuceneDoc(document); // same as add, but includes docId
            // delete old (if exists) and add new in one operation
            indexWriter.updateDocument(new org.apache.lucene.index.Term("id", document.id()), luceneDoc);
            indexWriter.commit();
            // if using SearcherManager/NRT: searcherManager.maybeRefresh();
        } finally {
            lock.unlock();
        }
    }

    @Tool(
            name = "deleteById",
            description = """
        Delete a document by its unique docId (idempotent).
        Inputs: docId:string.
        Behavior: Returns whether a document was deleted. Safe to call multiple times.
        Guidance for caller: If deletion returns false, inform the user the docId was not found. Do not fabricate results.
        """
    )
    public boolean deleteById(String id) throws IOException {
        lock.lock();
        try {
            long before = indexWriter.getDocStats().numDocs;
            indexWriter.deleteDocuments(new org.apache.lucene.index.Term("id", id));
            indexWriter.commit();
            long after = indexWriter.getDocStats().numDocs;
            // cannot easily know how many matched; you can track via a reader if needed
            return before != after;
            // with NRT: searcherManager.maybeRefresh();
        } finally {
            lock.unlock();
        }
    }

    @Tool(
            name = "updateFields",
            description = """
        Read-modify-write update for a document identified by docId.
        Inputs: docId:string and a set of field changes (partial payload).
        Behavior: Loads the current document, applies the field changes, then fully replaces the document.
        Guidance for caller: Use for small edits (e.g., update notes/tags). If the doc doesn’t exist, report not found.
        Note: Lucene does not support in-place text edits; this performs a full replace after applying the patch.
        """
    )
    public void updateFields(String id, java.util.function.UnaryOperator<Document> mutate) throws Exception {
        // 1) Fetch existing (your own method that searches by id and returns domain Document)
        Document current = getById(id)
                .orElseThrow(() -> new IllegalArgumentException("No document with id " + id));

        // 2) Apply changes
        Document updated = mutate.apply(current);

        // 3) Replace whole Lucene doc
        upsertDocument(updated);
    }

    @Tool(
            name = "getById",
            description = """
        Fetch a single document by its unique docId (exact, case-sensitive match).
        Inputs: docId:string.
        Behavior: Return the stored document if present; otherwise indicate not found.
        Guidance for caller: If not found, tell the user no document exists for that docId. Do not invent content.
        Typical fields: title, category, procedure, notes, tags, relatedTo, etc.
        """
    )
    public java.util.Optional<Document> getById(String id) throws Exception {
        if (!DirectoryReader.indexExists(indexDirectory)) return java.util.Optional.empty();
        try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query q = new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("id", id));
            org.apache.lucene.search.TopDocs td = searcher.search(q, 1);
            if (td.totalHits.value == 0) return java.util.Optional.empty();
            org.apache.lucene.document.Document d = searcher.doc(td.scoreDocs[0].doc);
            return java.util.Optional.of(domainFromLucene(d)); // your mapping back to domain Document
        }
    }

    private Document domainFromLucene(org.apache.lucene.document.Document doc) {
        String id     = nvl(doc.get("id"));
        String docType     = nvl(doc.get("docType"));
        String title     = nvl(doc.get("title"));
        String category  = nvl(doc.get("category"));
        String procedure = nvl(doc.get("content")); // merged "whenToDo" + "steps"
        String notes     = nvl(doc.get("notes"));
        List<String> tags = Arrays.asList(doc.getValues("tags"));
        List<String> relatedTo = Arrays.asList(doc.getValues("relatedTo"));

        // Example record/POJO constructor signature would change accordingly
        return new Document( id, title, docType, category, procedure, notes, tags, relatedTo);
    }

    private org.apache.lucene.document.Document buildLuceneDoc(Document document) {

        org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();

        luceneDoc.add(new StringField("id", document.id(), Store.YES));
        luceneDoc.add(new StringField("docType", document.id(), Store.YES));
        luceneDoc.add(new TextField("docType_exact", document.id(), Store.YES));
        luceneDoc.add(new TextField("title", document.title(), Store.YES));
        luceneDoc.add(new TextField("category", document.category(), Store.YES));
        luceneDoc.add(new StringField("category_exact", document.category(), Store.YES));
        luceneDoc.add(new TextField("content", document.content(), Store.YES));
        luceneDoc.add(new TextField("notes", document.notes(), Store.YES));

        StringBuilder catchAll = new StringBuilder();
        catchAll.append(document.title()).append(" ");
        catchAll.append(document.docType()).append(" ");
        catchAll.append(document.content()).append(" ");
        catchAll.append(document.notes()).append(" ");

        document.tags().forEach(tag -> {
            luceneDoc.add(new TextField("tags", tag, Store.YES));
            luceneDoc.add(new TextField("tags_exact", tag, Store.YES));
            catchAll.append(tag).append(" ");
        });

        document.relatedTo().forEach(related ->
                luceneDoc.add(new StringField("relatedTo", related, Store.YES))
        );

        luceneDoc.add(new TextField("catchAll", catchAll.toString(), Store.NO));

        return luceneDoc;

    }

    private Document mapToDomain(org.apache.lucene.document.Document d) {
        return new Document(
                d.get("id"),
                d.get("docType"),
                d.get("title"),
                d.get("category"),
                d.get("content"),
                d.get("notes"),
                java.util.Arrays.asList(d.getValues("tags")),
                java.util.Arrays.asList(d.getValues("relatedTo"))
        );
    }

    private static String nvl(String s) { return s == null ? "" : s; }

}