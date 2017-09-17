package com.github.logtrail.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by skaliappan on 9/15/17.
 */
public class LogProcessor {
    private RestClient elasticClient;
    private Map<String, List<LogPattern>> contextToPatternsMap;
    private static final String PATTERN_SEARCH_ENDPOINT = ".logtrail_patterns/_search?type=patterns&size=2000";
    private static final Logger LOGGER = LoggerFactory.getLogger(LogProcessor.class);

    public LogProcessor(String[] esHosts) {
        List<HttpHost> hosts = new ArrayList<>();
        for (String esHost : esHosts) {
            hosts.add(HttpHost.create(esHost));
        }
        elasticClient = RestClient.builder(
                hosts.toArray(new HttpHost[1])).build();
    }

    public void init() {
        List<LogPattern> patterns = fetchLogStatements();
        LOGGER.info("Fetched {} patterns from elasticsearch server", patterns.size());

        //populate map
        contextToPatternsMap = new HashMap<>();
        for (LogPattern pattern : patterns) {
            List<LogPattern> patternsForContext = contextToPatternsMap.get(pattern.getContext());
            if (patternsForContext == null) {
                patternsForContext = new ArrayList<>();
                contextToPatternsMap.put(pattern.getContext(), patternsForContext);
            }
            patternsForContext.add(pattern);
        }
    }

    private List<LogPattern> fetchLogStatements() {
        List<LogPattern> patterns = new ArrayList<>();
        try {
            Response response = elasticClient.performRequest("GET", PATTERN_SEARCH_ENDPOINT);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                ResponseHits responseHits = objectMapper.readValue(EntityUtils.toString(response.getEntity()),
                        ResponseHits.class);
                List<Hit> hits = responseHits.hits.hits;
                for (Hit hit : hits) {
                    LogPattern pattern = hit.source;
                    //set id to doc id
                    pattern.setId(hit.id);
                    patterns.add(pattern);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Exception while fetching patterns ", e);
        }
        return patterns;
    }

    public Map<String, Object> process(String message, String context) {
        Map<String, Object> parsedInfo = null;
        List<LogPattern> patternsForContext = contextToPatternsMap.get(context);
        if (patternsForContext == null) {
            patternsForContext = contextToPatternsMap.get("default-context");
        }
        if (patternsForContext != null) {
            for (LogPattern pattern : patternsForContext) {
                Matcher matcher = pattern.getMessageRegEx().matcher(message);
                if (matcher.matches()) {
                    parsedInfo = new LinkedHashMap<>();
                    parsedInfo.put("patternId", pattern.getId());
                    List<Integer> matchIndices = new ArrayList<>();
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        parsedInfo.put("a" + i, matcher.group(i));
                        matchIndices.add(matcher.start(i));
                        matchIndices.add(matcher.end(i));
                    }
                    if (matcher.groupCount() > 0) {
                        parsedInfo.put("matchIndices", matchIndices);
                    }
                }
            }
        }
        return parsedInfo;
    }

    public void cleanup() {
        try {
            elasticClient.close();
        } catch (IOException e) {
            LOGGER.error("Exception while closing ES", e);
        }
    }

    public static class ResponseHits {
        private Hits hits;

        public Hits getHits() {
            return hits;
        }

        public void setHits(Hits hits) {
            this.hits = hits;
        }
    }
    public static class Hits {
        private List<Hit> hits;

        public List<Hit> getHits() {
            return hits;
        }

        public void setHits(List<Hit> hits) {
            this.hits = hits;
        }
    }
    public static class Hit {
        @JsonProperty(value = "_index")
        private String index;

        @JsonProperty(value = "_type")
        private String type;

        @JsonProperty(value = "_id")
        private String id;

        @JsonProperty(value = "_score")
        private Double score;

        @JsonProperty(value = "_source")
        private LogPattern source;

        public String getIndex() {
            return index;
        }

        public void setIndex(String index) {
            this.index = index;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public LogPattern getSource() {
            return source;
        }

        public void setSource(LogPattern source) {
            this.source = source;
        }
    }

    @JsonIgnoreProperties
    private static class LogPattern {
        private Pattern messageRegEx;
        private Map<String, String> args;
        private String context;
        private String id;

        public Pattern getMessageRegEx() {
            return messageRegEx;
        }

        public void setMessageRegEx(String messageRegEx) {
            this.messageRegEx = Pattern.compile(messageRegEx);
        }

        public Map<String, String> getArgs() {
            return args;
        }

        public void setArgs(Map<String, String> args) {
            this.args = args;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

    }

    public static void main(String args[]) {
        LogProcessor logProcessor = new LogProcessor(new String[]{"http://localhost:9200"});
        logProcessor.init();
        System.out.println(logProcessor.process("Failed while trying to create a new session", "org.apache.zookeeper.server.jersey.resources.SessionsResource"));
        System.out.println(logProcessor.process("Closed socket connection for client /10.196.68.149:35705 which had sessionid 0x2384135ffe302b5", "org.apache.zookeeper.server.NIOServerCnxn"));
        logProcessor.cleanup();
    }
}
