package com.github.logtrail.tools.sourceanalyzer;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.*;
import io.searchbox.indices.CreateIndex;
import io.searchbox.indices.DeleteIndex;
import io.searchbox.indices.IndicesExists;
import io.searchbox.indices.mapping.PutMapping;
import io.searchbox.params.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by skaliappan on 9/14/17.
 */
public class ElasticOutput {
    private final String INDEX_NAME = ".logtrail";
    private final String TYPE_NAME = "pattern";
    private JestClient elasticClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticOutput.class);
    private Map<String, LogStatement> logStatementsMap;

    public ElasticOutput(String url) {
        JestClientFactory factory = new JestClientFactory();
        factory.setHttpClientConfig(new HttpClientConfig
                .Builder(url)
                .multiThreaded(true).build());
        elasticClient = factory.getObject();
    }

    public void init() throws Exception {
        if (!indexExists()) {
            LOGGER.info("Index does not exist. Creating...");
            if (!createIndex()) {
                LOGGER.error("Unable to create index {}. Exiting..", INDEX_NAME);
                throw new Exception("Cannot create index " + INDEX_NAME);
            }
        } else {
            List<LogStatement> statementList = fetchLogStatements();
            LOGGER.info("Fetched {} patterns from ES", statementList.size());
            logStatementsMap = new HashMap<>();
            for (LogStatement logStatement : statementList) {
                logStatementsMap.put(logStatement.getMessageId(), logStatement);
            }
        }
    }

    private void updateMapping() throws IOException {
        PutMapping putMapping = new PutMapping.Builder(
                INDEX_NAME,
                TYPE_NAME,
                "{ \"pattern\" : { \"properties\" : { \"indexPattern\" : {\"type\" : \"keyword\"} } } }"
        ).build();
        JestResult result = elasticClient.execute(putMapping);
        if (result.isSucceeded()) {
            LOGGER.info("Updated mapping");
        } else {
            LOGGER.info("Error while updating mapping {}",result.getErrorMessage());
        }
    }

    public void writeDocuments(List<LogStatement> logStatements) throws IOException {
        Bulk.Builder bulkRequest = new Bulk.Builder();
        for (LogStatement logStatement : logStatements) {
            if (logStatementsMap == null || !logStatementsMap.containsKey(logStatement.getMessageId())) {
                Index index = new Index.Builder(logStatement).index(INDEX_NAME).type(TYPE_NAME).build();
                bulkRequest.addAction(index);
            }
        }
        JestResult result = elasticClient.execute(bulkRequest.build());
        if (!result.isSucceeded()) {
            throw new IOException("Exception while writing patterns " + result.getErrorMessage());
        }
    }

    private List<LogStatement> fetchLogStatements() {
        List<LogStatement> patterns = new ArrayList<>();
        try {
            String matchAllQuery = "{\n" +
                    "    \"query\": {\n" +
                    "        \"match_all\": {}\n" +
                    "    }\n" +
                    "}";
            Search search = new Search.Builder(matchAllQuery).addIndex(INDEX_NAME).addType(TYPE_NAME)
                    .setParameter(Parameters.SCROLL, "1m")
                    .setParameter(Parameters.SIZE, 1000)
                    .build();
            SearchResult searchResult = elasticClient.execute(search);
            String scrollId = searchResult.getJsonObject().get("_scroll_id").getAsString();
            if (searchResult.isSucceeded()) {
                List<SearchResult.Hit<LogStatement, Void>> hits = searchResult.getHits(LogStatement.class);
                patterns.addAll(hits.stream().map(hit -> hit.source).collect(Collectors.toList()));
            }

            while (scrollId != null) {
                SearchScroll scroll = new SearchScroll.Builder(scrollId, "1m").build();
                JestResult scrollResult = elasticClient.execute(scroll);
                if (scrollResult.isSucceeded()) {
                    List<LogStatement> logStatements = scrollResult.getSourceAsObjectList(LogStatement.class);
                    if (logStatements.size() == 0) {
                        break;
                    }
                    patterns.addAll(logStatements);
                }
                scrollId = scrollResult.getJsonObject().get("_scroll_id").getAsString();
            }
        } catch (IOException e) {
            LOGGER.error("Exception while fetching patterns ", e);
        }
        return patterns;
    }

    private boolean createIndex() throws IOException {
        JestResult jestResult = elasticClient.execute(new CreateIndex.Builder(INDEX_NAME).build());
        if (jestResult.isSucceeded()) {
            updateMapping();
        }
        return jestResult.isSucceeded();
    }

    private boolean indexExists() throws IOException {
        JestResult jestResult = elasticClient.execute(new IndicesExists.Builder(INDEX_NAME).build());
        return jestResult.isSucceeded();
    }

    public boolean deletePatternsIndex() throws IOException {
        JestResult jestResult = elasticClient.execute(new DeleteIndex.Builder(INDEX_NAME).build());
        return jestResult.isSucceeded();
    }

    public void cleanup() {
        if (elasticClient != null) {
            elasticClient.shutdownClient();
        }
    }
}
