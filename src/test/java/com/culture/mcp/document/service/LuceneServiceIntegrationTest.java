package com.culture.mcp.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import com.culture.mcp.document.domain.Document;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the LuceneService and DocumentController.
 * This class uses MockMvc to simulate HTTP requests to the REST API.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class LuceneServiceIntegrationTest {

    private static final String INDEX_DIRECTORY_NAME = "lucene-index";
    private static final Path INDEX_DIRECTORY = Paths.get(INDEX_DIRECTORY_NAME);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LuceneService luceneService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Cleans up the Lucene index directory before each test to ensure a clean state.
     * @throws IOException if the directory cannot be deleted.
     */
    @BeforeEach
    public void setup() throws Exception {
        // Close IndexWriter if still open
        if (luceneService instanceof LuceneServiceImpl luceneImpl) {
            luceneImpl.destroy(); // Close current writer
        }

        // Delete index directory
        if (Files.exists(INDEX_DIRECTORY)) {
            Files.walk(INDEX_DIRECTORY)
                    .map(Path::toFile)
                    .forEach(File::delete);
            Files.deleteIfExists(INDEX_DIRECTORY);
        }

        // Reinitialize IndexWriter
        if (luceneService instanceof LuceneServiceImpl luceneImpl) {
            luceneImpl.init();
        }
    }

    /**
     * Tests the ability to add a single document via the REST endpoint.
     * @throws Exception if the MockMvc request fails.
     */
    @Test
    public void testAddSingleDocument() throws Exception {
        // 1. Create a sample document
        Document doc = new Document(
                UUID.randomUUID().toString(),
                "Doc Type",
                "Test Document for Upload",
                "Test Category",
                "Test Content",
                "Test Notes",
                Arrays.asList("tag1", "test"),
                Arrays.asList("related1", "related2")
        );

        // 2. Perform a POST request to the add endpoint with the document JSON
        mockMvc.perform(MockMvcRequestBuilders.post("/api/documents/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(doc)))
                .andExpect(status().isOk());

        // 3. Verify the document was indexed by searching for it
        List<Document> results = luceneService.search("title:Test Document for Upload");
        assertEquals(1, results.size(), "Expected one document in the search results.");
        assertEquals("Test Document for Upload", results.get(0).title());
    }

    /**
     * Tests the ability to add multiple documents in a single request.
     * @throws Exception if the MockMvc request fails.
     */
    @Test
    public void testAddBulkDocuments() throws Exception {
        // 1. Create a list of documents
        List<Document> documents = Arrays.asList(
                new Document(UUID.randomUUID().toString(), "Doc Type A", "test", "Tech", "Content A", "Note A", Arrays.asList("tech"), Arrays.asList("doc2")),
                new Document(UUID.randomUUID().toString(), "Doc Type B", "test", "Misc", "Content B", "Note B", Arrays.asList("misc"), Arrays.asList("doc1"))
        );

        // 2. Perform a POST request to the bulk add endpoint
        mockMvc.perform(MockMvcRequestBuilders.post("/api/documents/add-bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documents)))
                .andExpect(status().isOk());

        // 3. Verify both documents were indexed
        List<Document> results = luceneService.search("title:test");
        assertEquals(2, results.size(), "Expected two documents from search for 'test'.");
    }

    /**
     * Tests a full-text search using a Lucene query.
     * @throws Exception if the MockMvc request fails.
     */
    @Test
    public void testLuceneSearch() throws Exception {
        // 1. Add some documents with different properties
        luceneService.addDocument(new Document(UUID.randomUUID().toString(), "How To", "Spring Boot Guide", "Programming", "Learn Spring Boot", "...", Arrays.asList("spring", "boot"), Arrays.asList()));
        luceneService.addDocument(new Document(UUID.randomUUID().toString(), "How To", "Hibernate Basics", "Database", "Learn Hibernate", "...", Arrays.asList("hibernate", "java"), Arrays.asList()));
        luceneService.addDocument(new Document(UUID.randomUUID().toString(), "How To", "Java Concurrency", "Programming", "Learn concurrency", "...", Arrays.asList("java", "concurrency"), Arrays.asList()));

        // 2. Perform a GET request to the search endpoint with valid Lucene query
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/documents/search")
                        .param("q", "category:Programming AND tags:spring"))
                .andExpect(status().isOk())
                .andReturn();

        // 3. Assert the response contains the correct document
        String jsonResponse = result.getResponse().getContentAsString();
        List<Document> results = objectMapper.readValue(jsonResponse, objectMapper.getTypeFactory().constructCollectionType(List.class, Document.class));

        assertEquals(1, results.size());
        assertEquals("Spring Boot Guide", results.get(0).title());
        assertEquals("Programming", results.get(0).category());
        assertTrue(results.get(0).tags().contains("spring"));
    }


}
