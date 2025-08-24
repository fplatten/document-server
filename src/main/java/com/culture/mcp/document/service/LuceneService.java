package com.culture.mcp.document.service;

import java.io.IOException;
import java.util.List;

// Import the Document record from the same package
import com.culture.mcp.document.domain.Document;

/**
 * Interface for a Lucene-based document service.
 * Defines methods for adding and searching documents.
 */
public interface LuceneService {

    void addDocument(Document document) throws IOException;

    void addBulkDocuments(List<Document> documents) throws IOException;

    List<Document> search(String jqlQuery) throws Exception;

    List<Document> smartSearch(String jqlQuery) throws Exception;

    java.util.Optional<Document> getById(String id) throws Exception;

    void updateFields(String id, java.util.function.UnaryOperator<Document> mutate) throws Exception;

    boolean deleteById(String id) throws IOException;

    public void upsertDocument(Document document) throws IOException;


}