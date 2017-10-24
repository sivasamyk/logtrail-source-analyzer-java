package com.github.logtrail.tools;

import io.searchbox.annotations.JestId;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.JestResult;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;
import io.searchbox.core.SearchScroll;
import io.searchbox.params.Parameters;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by skaliappan on 9/15/17.
 */
public class LogProcessor {
    private JestClient elasticClient;
    private String indexPattern;
    private Map<String, List<LogPattern>> contextToPatternsMap;
    private final String INDEX_NAME = ".logtrail";
    private final String TYPE_NAME = "pattern";
    private static final Logger LOGGER = LoggerFactory.getLogger(LogProcessor.class);

    public LogProcessor(String[] esHosts, String indexPattern) {
        JestClientFactory factory = new JestClientFactory();
        factory.setHttpClientConfig(new HttpClientConfig
                .Builder(esHosts[0])
                .multiThreaded(true).build());
        this.elasticClient = factory.getObject();
        this.indexPattern = indexPattern;
    }

    public void init() {
        List<LogPattern> logPatterns = fetchLogPatterns();
        LOGGER.info("Fetched {} logPatterns from elasticsearch server", logPatterns.size());

        //populate map
        contextToPatternsMap = new HashMap<>();
        for (LogPattern logPattern : logPatterns) {
            //pre-compile pattern
            if (logPattern.getMessageRegEx() != null) {
                logPattern.setPattern(Pattern.compile(logPattern.getMessageRegEx()));
            } else {
                LOGGER.debug("Null message for pattern :" + logPattern);
            }
            List<LogPattern> patternsForContext = contextToPatternsMap.get(logPattern.getContext());
            if (patternsForContext == null) {
                patternsForContext = new ArrayList<>();
                contextToPatternsMap.put(logPattern.getContext(), patternsForContext);
            }
            patternsForContext.add(logPattern);
        }
    }

    private List<LogPattern> fetchLogPatterns() {
        List<LogPattern> patterns = new ArrayList<LogPattern>();

        try {
            String matchQuery = "{\n" +
                    "    \"query\": {\n" +
                    "        \"match_all\": {}\n" +
                    "    }\n" +
                    "}";
            if (indexPattern != null) {
                matchQuery = "{\n" +
                        "    \"query\": {\n" +
                                        "\"term\" : {\n" +
                                        "\"indexPattern\" :\"" + indexPattern + "\"" +
                                        "}\n" +
                        "    }\n" +
                        "}";
            }
            Search search = new Search.Builder(matchQuery).addIndex(INDEX_NAME).addType(TYPE_NAME)
                    .setParameter(Parameters.SCROLL, "1m")
                    .setParameter(Parameters.SIZE, 500)
                    .build();
            SearchResult searchResult = elasticClient.execute(search);
            String scrollId = null;
            if (searchResult.isSucceeded()) {
                List<SearchResult.Hit<LogPattern, Void>> hits = searchResult.getHits(LogPattern.class);
                patterns.addAll(hits.stream().map(hit -> hit.source).collect(Collectors.toList()));
                if (searchResult.getJsonObject().has("_scroll_id")) {
                    scrollId = searchResult.getJsonObject().get("_scroll_id").getAsString();
                }
            } else {
                LOGGER.error("Error while fetching patterns : {}" , searchResult.getErrorMessage());
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
        try {
            List<LogPattern> patternsForContext = contextToPatternsMap.get(context);
            if (patternsForContext == null) {
                patternsForContext = contextToPatternsMap.get("default-context");
            }
            if (patternsForContext != null) {
                parsedInfo = match(message, patternsForContext);
                if (parsedInfo == null) {
                    //check in default context
                    patternsForContext = contextToPatternsMap.get("default-context");
                    if (patternsForContext != null) {
                        match(message, patternsForContext);
                    }
                    LOGGER.debug("Cannot find match for {} in context {}", message, context);
                }
            }
        } catch (Throwable e) {
            //log any error during processing and return empty parsedInfo
            LOGGER.error(MessageFormat.format("Exception while processing message {0} in context {1} ", message, context), e);
        }
        return parsedInfo;
    }

    private Map<String, Object> match(String message, List<LogPattern> patternsForContext) {
        Map<String, Object> parsedInfo = null;
        for (LogPattern pattern : patternsForContext) {
            Matcher matcher = pattern.getPattern().matcher(message);
            if (matcher.matches()) {
                parsedInfo = new LinkedHashMap<>();
                parsedInfo.put("patternId", pattern.getId());
                List<Integer> matchIndices = new ArrayList<>();
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    if (pattern.getFields().size() > (i-1)) {
                        String argName = pattern.getFields().get(i - 1);
                        String value = matcher.group(i);
                        if (NumberUtils.isNumber(value)) {
                            Number number = NumberUtils.createNumber(value);
                            parsedInfo.put(argName, number);
                        } else {
                            parsedInfo.put(argName, value);
                        }
                        matchIndices.add(matcher.start(i));
                        matchIndices.add(matcher.end(i));
                    } else {
                        LOGGER.warn("Cannot find fields for message {} ", message);
                    }
                }
                if (matcher.groupCount() > 0) {
                    parsedInfo.put("matchIndices", matchIndices);
                }
                break;
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
        private List<String> fields;
        private String context;
        @JestId
        private String id;
        private Pattern pattern;

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

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

        @Override
        public String toString() {
            return "LogPattern{" +
                    "messageRegEx='" + messageRegEx + '\'' +
                    ", args=" + args +
                    ", context='" + context + '\'' +
                    ", id='" + id + '\'' +
                    '}';
        }

        public List<String> getFields() {
            return fields;
        }

        public void setFields(List<String> fields) {
            this.fields = fields;
        }
    }

    public static void main(String args[]) {
        LogProcessor logProcessor = new LogProcessor(new String[]{"http://localhost:9200"},"logstash-*");
        logProcessor.init();
        System.out.println(logProcessor.process("Going to retain 2 images with txid >= 37567055", "org.apache.hadoop.hdfs.server.namenode.NNStorageRetentionManager"));
        System.out.println(logProcessor.process("Web server init done", "org.apache.hadoop.hdfs.server.namenode.SecondaryNameNode"));
        logProcessor.cleanup();
    }
}
