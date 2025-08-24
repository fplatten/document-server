package com.culture.mcp.document;

import com.culture.mcp.document.service.LuceneServiceImpl;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DocumentServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocumentServerApplication.class, args);
	}

	@Bean
	public ToolCallbackProvider tools(LuceneServiceImpl luceneService) {
		return MethodToolCallbackProvider.builder()
				.toolObjects(luceneService)
				.build();
	}

}
