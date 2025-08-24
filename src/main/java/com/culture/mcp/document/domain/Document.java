package com.culture.mcp.document.domain;

import java.util.List;

public record Document(
        String id,
        String docType,
        String title,
        String category,
        String content,
        String notes,
        List<String> tags,
        List<String> relatedTo
) {}