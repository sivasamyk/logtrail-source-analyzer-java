package com.github.logtrail.tools.sourceanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created by skaliappan on 9/14/17.
 */
public class ElasticOutput {
    private final String INDEX_NAME = ".logtrail_patterns";
    private final String PATTERNS_ENDPOINT = "/" + INDEX_NAME + "/" + "patterns";
    private RestClient elasticClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticOutput.class);

    public ElasticOutput(String url) {
        elasticClient = RestClient.builder(
                HttpHost.create(url)).build();
    }

    public void init() throws Exception {
        if(!indexExists()) {
            LOGGER.info("Index does not exist. Creating...");
            if (!createIndex()) {
                LOGGER.error("Unable to create index {}. Exiting..", INDEX_NAME);
                throw new Exception("Cannot create index " + INDEX_NAME);
            }
        }
    }

    public void writeDocuments(List<LogStatement> logStatements) throws IOException {
        Map<String, String> params = Collections.emptyMap();
        ObjectMapper mapper = new ObjectMapper();
        for (LogStatement logStatement : logStatements) {
            String jsonStr = mapper.writeValueAsString(logStatement);
            HttpEntity entity = new NStringEntity(jsonStr, ContentType.APPLICATION_JSON);
            elasticClient.performRequest("POST", PATTERNS_ENDPOINT, params, entity);
        }
    }

    private boolean createIndex() throws IOException {
        Response response = elasticClient.performRequest("PUT",INDEX_NAME);
        return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
    }

    private boolean indexExists() throws IOException {
        Response response = elasticClient.performRequest("HEAD", INDEX_NAME);
        return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
    }

    public boolean deletePatternsIndex() throws IOException {
        Response response = elasticClient.performRequest("DELETE", INDEX_NAME);
        return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
    }

    public void cleanup() {
        if (elasticClient != null) {
            try {
                elasticClient.close();
            } catch (IOException e) {
                LOGGER.error("Exception while closing client ",e);
            }
        }
    }
}
