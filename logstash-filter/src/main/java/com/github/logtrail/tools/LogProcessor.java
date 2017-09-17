package com.github.logtrail.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
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
    private static final String PATTERN_SEARCH_ENDPOINT = ".logtrail_patterns/_search?type=patterns";
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
        LOGGER.info("Fetched {} from elasticsearch server", patterns.size());

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
                Map<String, Object> result = objectMapper.readValue(EntityUtils.toString(response.getEntity()),
                        Map.class);
                List<Map> hits = (List) ((Map) result.get("hits")).get("hits");
                for (Map<String, Object> hit : hits) {
                    LogPattern pattern = new LogPattern();
                    Map<String, Object> source = (Map) hit.get("_source");
                    pattern.setId(hit.get("_id").toString());
                    pattern.setMessageRegEx(source.get("messageRegEx").toString());
                    pattern.setContext(source.get("context").toString());
                    pattern.setArgs((Map<String, String>) source.get("args"));
                    patterns.add(pattern);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Exception while fetching patterns ", e);
        }
        return patterns;
    }

    public Map<String, String> process(String message, String context) {
        Map<String, String> parsedInfo = null;
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
                    StringBuilder matchIndices = new StringBuilder();
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        parsedInfo.put("a" + i, matcher.group(i));
                        matchIndices.append(matcher.start(i)).append(",")
                                .append(matcher.end(i)).append(":");
                    }
                    if (matcher.groupCount() > 0) {
                        matchIndices.deleteCharAt(matchIndices.length() - 1);
                        parsedInfo.put("matchIndices", matchIndices.toString());
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
        System.out.println(logProcessor.process("This is a sample log without arguments", "SampleLogger"));
        System.out.println(logProcessor.process("This is logger with string arguments Hello", "SampleLogger"));
        logProcessor.cleanup();
    }
}
