package com.github.logtrail.tools;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;
import io.searchbox.core.SearchScroll;
import io.searchbox.params.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by skaliappan on 9/15/17.
 */
public class LogProcessor {
    private JestClient elasticClient;
    private Map<String, List<LogPattern>> contextToPatternsMap;
    private final String INDEX_NAME = ".logtrail-patterns";
    private final String TYPE_NAME = "patterns";
    private static final Logger LOGGER = LoggerFactory.getLogger(LogProcessor.class);

    public LogProcessor(String[] esHosts) {
        JestClientFactory factory = new JestClientFactory();
        factory.setHttpClientConfig(new HttpClientConfig
                .Builder(esHosts[0])
                .multiThreaded(true).build());
        elasticClient = factory.getObject();
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
                List<SearchResult.Hit<LogPattern, Void>> hits = searchResult.getHits(LogPattern.class);
                patterns.addAll(hits.stream().map(hit -> hit.source).collect(Collectors.toList()));
            }

            while (scrollId != null) {
                SearchScroll scroll = new SearchScroll.Builder(scrollId, "5m").build();
                JestResult scrollResult = elasticClient.execute(scroll);
                if (scrollResult.isSucceeded()) {
                    List<LogPattern> logStatements = scrollResult.getSourceAsObjectList(LogPattern.class);
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


    public Map<String, Object> process(String message, String context) {
        Map<String, Object> parsedInfo = null;
        List<LogPattern> patternsForContext = contextToPatternsMap.get(context);
        if (patternsForContext == null) {
            patternsForContext = contextToPatternsMap.get("default-context");
        }
        if (patternsForContext != null) {
            for (LogPattern pattern : patternsForContext) {
                Matcher matcher = pattern.getPattern().matcher(message);
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
        elasticClient.shutdownClient();
    }

    private static class LogPattern {
        private String messageRegEx;
        private List<String> args;
        private String context;
        private String id;
        private Pattern pattern;

        public String getMessageRegEx() {
            return messageRegEx;
        }

        public void setMessageRegEx(String messageRegEx) {
            this.messageRegEx = messageRegEx;
            pattern = Pattern.compile(messageRegEx);
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
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

        public Pattern getPattern() {
            return pattern;
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
