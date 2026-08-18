package com.example.chatbot.rag;

import com.example.chatbot.exception.ClaudeApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Thin client for the Voyage AI embeddings API (https://api.voyageai.com/v1/embeddings).
 * Anthropic does not offer its own embedding model and officially recommends Voyage AI
 * as the pairing for Claude-based RAG. See:
 * https://platform.claude.com/docs/en/build-with-claude/embeddings
 *
 * Kept isolated in the `rag` package, same pattern as ClaudeService in `claude` -
 * swap providers (OpenAI, Cohere, etc.) by rewriting only this class.
 */
@Component
@EnableConfigurationProperties(RagProperties.class)
public class VoyageEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingClient.class);
    private static final String VOYAGE_URL = "https://api.voyageai.com/v1/embeddings";

    private final RestClient restClient;
    private final RagProperties properties;

    public VoyageEmbeddingClient(RagProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Embeds a batch of texts in one API call (Voyage supports batching, which is
     * far cheaper/faster than one call per chunk). `inputType` should be "document"
     * when embedding content to store, or "query" when embedding a user's question -
     * Voyage's models are asymmetric and tuned for each.
     */
    @SuppressWarnings("unchecked")
    public List<float[]> embed(List<String> texts, String inputType) {
        if (properties.voyageApiKey() == null || properties.voyageApiKey().isBlank()) {
            throw new IllegalStateException(
                    "VOYAGE_API_KEY is not set. Get one at https://dashboard.voyageai.com and export it.");
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri(VOYAGE_URL)
                    .header("Authorization", "Bearer " + properties.voyageApiKey())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "input", texts,
                            "model", properties.embeddingModel(),
                            "input_type", inputType,
                            "output_dimension", properties.embeddingDimension()
                    ))
                    .retrieve()
                    .body(Map.class);

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            return data.stream()
                    .map(d -> {
                        List<Double> vec = (List<Double>) d.get("embedding");
                        float[] arr = new float[vec.size()];
                        for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
                        return arr;
                    })
                    .toList();

        } catch (RestClientException e) {
            log.error("Voyage embeddings call failed: {}", e.getMessage());
            throw new ClaudeApiException("Embedding service is unavailable right now.", 502, e);
        }
    }

    public float[] embedQuery(String text) {
        return embed(List.of(text), "query").get(0);
    }
}
