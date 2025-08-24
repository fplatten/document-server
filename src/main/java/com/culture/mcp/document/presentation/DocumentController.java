package com.culture.mcp.document.presentation;

import com.culture.mcp.document.domain.Document;
import com.culture.mcp.document.service.LuceneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final LuceneService luceneService;

    @Autowired
    public DocumentController(LuceneService luceneService) {
        this.luceneService = luceneService;
    }

    @PostMapping("/add")
    public ResponseEntity<String> addDocument(@RequestBody Document document) {
        try {
            luceneService.addDocument(document);
            return ResponseEntity.ok("Document added successfully!");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error adding document: " + e.getMessage());
        }
    }

    @PostMapping("/add-bulk")
    public ResponseEntity<String> addDocuments(@RequestBody List<Document> documents) {
        try {
            luceneService.addBulkDocuments(documents);
            return ResponseEntity.ok("Documents added successfully!");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error adding documents: " + e.getMessage());
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<Document>> searchDocuments(@RequestParam("q") String query) {
        try {
            List<Document> results = luceneService.search(query);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    /** Get a single document by its unique docId. Returns 404 if not found. */
    @GetMapping("/{docId}")
    public ResponseEntity<Document> getById(@PathVariable String docId) {
        try {
            return luceneService.getById(docId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Upsert (create or replace) a document at the given docId.
     * If the document does not exist, it is created; otherwise it is fully replaced.
     */
    @PutMapping("/{docId}")
    public ResponseEntity<String> upsertDocument(@PathVariable String docId, @RequestBody Document document) {
        try {
            luceneService.upsertDocument(document);
            return ResponseEntity.ok("Upsert successful for docId=" + docId);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error upserting document: " + e.getMessage());
        }
    }

    /** Delete a document by docId. Returns 204 if deleted, 404 if not found. */
    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> deleteById(@PathVariable String docId) {
        try {
            boolean deleted = luceneService.deleteById(docId);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Partially update a document’s fields by docId (read-modify-write).
     * Accepts a JSON object of field -> newValue pairs. Returns 404 if doc doesn’t exist.
     * Example body: {"title":"New title","notes":"Append this note","tags":["sensor","calibration"]}
     */
    @PatchMapping("/{docId}")
    public ResponseEntity<Document> updateFields(@PathVariable String docId,
                                                 @RequestBody Map<String, Object> changes) {
        try {
            // Build a mutator that applies the incoming partial changes
            java.util.function.UnaryOperator<Document> mutator = current -> applyPatch(current, changes);

            // Perform read-modify-write in the service
            luceneService.updateFields(docId, mutator);

            // Fetch the updated doc to return it
            return luceneService.getById(docId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());

        } catch (NoSuchElementException | IllegalArgumentException notFound) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /** Apply a permissive JSON patch onto the current Document. */
    private static Document applyPatch(Document cur, Map<String, Object> changes) {
        String id     = pickString(changes, "id",     cur.id());
        String docType     = pickString(changes, "docType",     cur.docType());
        String title     = pickString(changes, "title",     cur.title());
        String category  = pickString(changes, "category",  cur.category());
        String content  = pickString(changes, "content",  cur.category());
        String notes     = pickString(changes, "notes",     cur.notes());
        java.util.List<String> tags =
                pickStringList(changes.get("tags"), cur.tags());
        java.util.List<String> relatedTo =
                pickStringList(changes.get("relatedTo"), cur.relatedTo());

        return new Document(id, docType, title, category, content, notes, tags, relatedTo);
    }

    /** Use new value if present and non-null; otherwise fallback. */
    private static String pickString(Map<String, Object> map, String key, String fallback) {
        if (!map.containsKey(key)) return fallback;
        Object v = map.get(key);
        return (v == null) ? fallback : String.valueOf(v);
    }

    /** Accepts List<String>, List<any>, comma-separated String, or null; falls back if invalid. */
    private static java.util.List<String> pickStringList(Object v, java.util.List<String> fallback) {
        if (v == null) return fallback;
        if (v instanceof java.util.List<?>) {
            java.util.List<?> raw = (java.util.List<?>) v;
            java.util.List<String> out = new java.util.ArrayList<>(raw.size());
            for (Object o : raw) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        if (v instanceof String s) {
            if (s.isBlank()) return java.util.List.of();
            String[] parts = s.split(",");
            java.util.List<String> out = new java.util.ArrayList<>(parts.length);
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        return fallback;
    }

}