package com.github.logtrail.tools.sourceanalyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Created by siva on 7/1/17.
 * Poor man's implementation to analyze log statements in Java Project
 * Currently parses only direct logger statements and make lots of assumptions
 * Will be enhanced in future to handle multiple log libraries ( currently supports SLF4J API) and
 * handle multiple logging statement contexts. May be a maven plugin
 */
public class JavaSrcAnalyzer {

    private String srcRoot;
    private List<String> excludes;
    private String outputFile;
    private static final Set<String> LOG_METHODS = new HashSet<String>();
    private int fileCount = 0, logCount = 0, defaultContextCount = 0;
    private List<LogStatement> logStatements = new ArrayList<>();
    private static final String REGEX_SPECIAL_CHARS = "[\\<\\(\\[\\\\\\^\\-\\=\\$\\!\\|\\]\\)‌​\\?\\*\\+\\.\\>]";
    private static Logger LOGGER = LoggerFactory.getLogger(JavaSrcAnalyzer.class);
    private static final String FORMAT_ANCHOR = "{}";
    private static final Pattern LOG_FORMAT_ANCHOR_PATTERN = Pattern.compile("\\{}");
    private static final Pattern REGEX_SPECIAL_CHARS_PATTERN = Pattern.compile(REGEX_SPECIAL_CHARS);
    private static final String DEFAULT_CONTEXT_NAME = "default-context";
    private LogContext context;
    private String elasticsearchUrl, indexPattern;
    private boolean appendRegExBoundaries, coldWrite;

    static {
        LOG_METHODS.add("debug");
        LOG_METHODS.add("trace");
        LOG_METHODS.add("info");
        LOG_METHODS.add("warn");
        LOG_METHODS.add("error");
    }

    public JavaSrcAnalyzer(String srcRoot, List<String> excludes,
                           String outputFile, String context)
            throws IOException, ParseException {
        this.srcRoot = srcRoot;
        this.excludes = excludes;
        this.outputFile = outputFile;
        this.context = LogContext.valueOf(context);
    }

    public JavaSrcAnalyzer(Properties properties) {
        this.srcRoot = properties.getProperty("src.root");
        if (!new File(srcRoot).exists()) {
            throw new IllegalArgumentException("Cannot find src dir :" + srcRoot);
        }
        String excludes = properties.getProperty("src.excludes");
        if (excludes != null && excludes.trim().length() > 0) {
            this.excludes = Arrays.asList(excludes.split(":"));
        }
        this.outputFile = properties.getProperty("patterns.out.file");
        this.context = LogContext.valueOf(properties.getProperty("context"));
        this.elasticsearchUrl = properties.getProperty("elasticsearch.url");
        this.appendRegExBoundaries = Boolean.parseBoolean(properties.getProperty("append.regex.boundaries","true"));
        this.indexPattern = properties.getProperty("logtrail.index.pattern","logstash-*");
        this.coldWrite = Boolean.parseBoolean(properties.getProperty("cold.write","false"));
    }

    public void analyze() throws IOException {

        Path path = Paths.get(srcRoot);
        if (Files.isDirectory(path)) {
            try {
                System.out.println("Walking src : " + srcRoot);
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".java")) {
                            try {
                                analyzeFile(file.toFile());
                                fileCount++;
                            } catch (ParseProblemException e) {
                                LOGGER.warn("Exception while analyzing file {}", file, e);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (excludes != null) {
                            for (String exclude : excludes) {
                                if (dir.toString().contains(exclude)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                System.out.println(MessageFormat.format("Analyzed {0} logs in {1} files and {2} mapped to default-context", logCount, fileCount, defaultContextCount));
            } finally {
                if (outputFile != null && logStatements != null) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    gson.toJson(logStatements, new FileWriter(outputFile, false));
                }
            }
        } else {
            LOGGER.error("Specify a valid src directory: {}", srcRoot);
        }
    }

    private void analyzeFile(File file) throws IOException {
        CompilationUnit cu = JavaParser.parse(file);
        List<MethodCallExpr> methodCallExprList = cu.getChildNodesByType(MethodCallExpr.class);

        Optional<PackageDeclaration> packageDec = cu.getPackageDeclaration();

        //Get all field declarations in this file
        HashMultimap<String, String> classToFieldsMap = null;
        if (context != LogContext.FILE) {
            classToFieldsMap = getFieldsInClass(cu);
        }

        //loop through all method calls in this file
        for (MethodCallExpr methodCallExpr : methodCallExprList) {
            String methodName = methodCallExpr.getName().getIdentifier();
            if (LOG_METHODS.contains(methodName)) {

                int argCount = methodCallExpr.getArguments().size();
                if (argCount > 0) {
                    String message = null;
                    List<String> args = null;
                    Expression firstArg = methodCallExpr.getArguments().get(0);
                    if (firstArg instanceof StringLiteralExpr) {
                        message = ((StringLiteralExpr) firstArg).asString();
                        args = getArgs(methodCallExpr);
                    } else if (firstArg instanceof BinaryExpr) {
                        StringBuilder messageBuilder = new StringBuilder();
                        args = new LinkedList<>();
                        processBinaryArgs((BinaryExpr) firstArg, messageBuilder, args);
                        message = messageBuilder.toString();
                    } else {
                        LOGGER.warn("Cannot resolve logger statement {} in file {}", methodCallExpr, file);
                    }

                    if (message != null) {

                        LogStatement logStatement = new LogStatement();
                        logStatement.setIndexPattern(indexPattern);
                        String logContext;
                        if (context != LogContext.FILE) {
                            logContext = getLogDeclarationClass(methodCallExpr, classToFieldsMap, file);
                            if (logContext != null && !DEFAULT_CONTEXT_NAME.equals(logContext)) {
                                if (context == LogContext.FQN && packageDec.isPresent()) {
                                    logContext = packageDec.get().getNameAsString() + "." + logContext;
                                }
                            }
                        } else {
                            logContext = file.getName();
                        }

                        logStatement.setContext(logContext != null ? logContext : DEFAULT_CONTEXT_NAME);
                        logStatement.setLevel(methodName);
                        try {
                            logStatement.setMessageRegEx(convertToRegEx(message));
                            if (args != null) {
                                logStatement.setArgs(args);
                                List<String> fields = new ArrayList<>();
                                for (String arg : args) {
                                    fields.add(getFieldName(arg, extractClassName(logStatement.getContext())));
                                }
                                logStatement.setFields(fields);
                            }

                        } catch (PatternSyntaxException ex) {
                            LOGGER.warn("Exception while converting regex {} in file {}. Message {}", message, file, ex.getMessage());
                        }
                        logCount++;
                        if (DEFAULT_CONTEXT_NAME.equals(logStatement.getContext())) {
                            defaultContextCount++;
                        }

                        String messageId = String.valueOf((logStatement.getContext() + "-" + logStatement.getMessageRegEx()).hashCode());
                        logStatement.setMessageId(messageId);
                        Optional<MethodDeclaration> method = methodCallExpr.getAncestorOfType(MethodDeclaration.class);
                        method.ifPresent(methodDeclaration -> logStatement.setMethod(methodDeclaration.getNameAsString()));
                        logStatements.add(logStatement);
                    }

                } else {
                    LOGGER.debug("logger statement with no args: {} in file {}", methodCallExpr, file);
                }
            }
        }
    }

    private String extractClassName(String logContext) {

        String clazz = logContext;
        if (context == LogContext.FQN) {
            int dotIndex = logContext.lastIndexOf('.');
            if (dotIndex != -1) {
                clazz = logContext.substring(dotIndex + 1);
            }
        } else if (context == LogContext.FILE) {
            clazz = logContext.substring(0,logContext.lastIndexOf("."));
        }
        return clazz;
    }

    private String getFieldName(String argName, String context) {
        String fieldName = context + "_" + argName.replaceAll("\\(\\)", "");
        StringBuilder bldr = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isLetterOrDigit(c) ||
                    c == '_') {
                bldr.append(c);
            } else if (bldr.charAt(bldr.length() - 1) != '_') {
                bldr.append('_');
            }
        }
        return bldr.toString();
    }

    private void processBinaryArgs(BinaryExpr expr, StringBuilder message, List<String> args) {
        Expression left = expr.getLeft();
        Expression right = expr.getRight();
        if (expr.getOperator() == BinaryExpr.Operator.PLUS) {
            if (left instanceof StringLiteralExpr) {
                message.append(((StringLiteralExpr) left).asString());
            } else if (left instanceof BinaryExpr) {
                processBinaryArgs((BinaryExpr) left, message, args);
            } else {
                args.add(left.toString());
                message.append(FORMAT_ANCHOR);
            }

            if (right instanceof StringLiteralExpr) {
                message.append(((StringLiteralExpr) right).asString());
            } else if (right instanceof BinaryExpr) {
                processBinaryArgs((BinaryExpr) right, message, args);
            } else {
                args.add(right.toString());
                message.append(FORMAT_ANCHOR);
            }
        }
    }

    private HashMultimap<String, String> getFieldsInClass(CompilationUnit cu) {
        HashMultimap<String, String> classToFieldsMap = HashMultimap.create();
        List<ClassOrInterfaceDeclaration> classes = cu.getChildNodesByType(ClassOrInterfaceDeclaration.class);
        for (ClassOrInterfaceDeclaration clazz : classes) {
            List<FieldDeclaration> declarations = clazz.getFields();
            for (FieldDeclaration declaration : declarations) {
                for (VariableDeclarator d : declaration.getVariables()) {
                    classToFieldsMap.put(clazz.getNameAsString(), d.getNameAsString());
                }
            }
        }
        return classToFieldsMap;
    }

    private List<String> getArgs(MethodCallExpr methodCallExpr) {
        NodeList<Expression> argumentList = methodCallExpr.getArguments();
        List<String> args = new LinkedList<>();
        if (argumentList.size() > 1) {
            for (int i = 1; i < argumentList.size(); i++) {
                args.add(argumentList.get(i).toString());
            }
        }
        return args;
    }

    //Creates regEx pattern from message with named groups
    private String convertToRegEx(String message) {
        //Escape any regex special characters in the log message
        String cleanedUpMessage = REGEX_SPECIAL_CHARS_PATTERN.matcher(message).replaceAll("\\\\$0");
        int argCount = 1;
        while (cleanedUpMessage.contains(FORMAT_ANCHOR)) {
            Matcher matcher = LOG_FORMAT_ANCHOR_PATTERN.matcher(cleanedUpMessage);
            cleanedUpMessage = matcher.replaceFirst("(?<arg" + argCount + ">[\\\\S]+)");
            argCount++;
        }
        if (appendRegExBoundaries) {
            cleanedUpMessage = "^" + cleanedUpMessage + "$";
        }
        //Compile to make sure we have a valid pattern.
        return Pattern.compile(cleanedUpMessage).pattern();
    }


    private String getLogDeclarationClass(MethodCallExpr methodCallExpr, SetMultimap<String, String> classToFieldsMap, File file) {
        Optional<Expression> scope = methodCallExpr.getScope();
        String logClass = DEFAULT_CONTEXT_NAME;
        NameExpr nameExpr;
        if (scope.isPresent() && scope.get() instanceof NameExpr) {
            nameExpr = (NameExpr) scope.get();
        } else { // for other cases like AnotherClass.LOGGER.sever() - maps to default-context
            LOGGER.warn("Cannot resolve parent class for {} in file {}", methodCallExpr, file);
            return logClass;
        }

        String varName = nameExpr.getNameAsString();
        Optional<ClassOrInterfaceDeclaration> clazz =
                methodCallExpr.getAncestorOfType(ClassOrInterfaceDeclaration.class);
        if (clazz.isPresent()) {
            while (true) {
                Set<String> fields = classToFieldsMap.get(clazz.get().getNameAsString());
                if (fields.contains(varName)) {
                    logClass = clazz.get().getNameAsString();
                    break;
                } else {
                    clazz = clazz.get().getAncestorOfType(ClassOrInterfaceDeclaration.class);
                    if (!clazz.isPresent()) {
                        break;
                    }
                }
            }
        }
        return logClass;
    }

    public static void main(String args[]) throws Exception {
        try {
            String home = System.getProperty("analyzer.home");
            CommandLineParser parser = new DefaultParser();
            CommandLine commandLine = parser.parse(options(), args);
            String configPath = home + "/conf/config.properties";

            if (commandLine.hasOption("f")) {
                configPath = commandLine.getOptionValue('f');
            }

            Properties config = new Properties();
            config.load(new FileInputStream(configPath));
            JavaSrcAnalyzer srcAnalyzer = new JavaSrcAnalyzer(config);
            srcAnalyzer.analyze();
            srcAnalyzer.writeToElasticsearch();

        } catch (Exception e) {
            LOGGER.error("exception while analyzing ", e);
            System.err.println(e.getMessage());
        }
    }

    private void writeToElasticsearch() throws Exception {
        if (elasticsearchUrl != null && !elasticsearchUrl.isEmpty()) {
            if (this.logStatements != null) {
                int patternCount = this.logStatements.size();
                LOGGER.info("Writing {} patterns to ES", patternCount);
                System.out.println("Writing " + patternCount + " patterns to elasticsearch @" + elasticsearchUrl);
                ElasticOutput elasticOutput = new ElasticOutput(elasticsearchUrl);
                if (coldWrite) {
                    elasticOutput.deletePatternsIndex();
                }
                elasticOutput.init();
                elasticOutput.writeDocuments(this.logStatements);
                elasticOutput.cleanup();
                System.out.println("Done...");
            }
        }
    }

    private static Options options() {
        Options options = new Options();
        options.addOption("f", "config", true, "Path to configuration properties file");
        return options;
    }

    enum LogContext {
        SIMPLE_NAME, FQN, FILE
    }
}
