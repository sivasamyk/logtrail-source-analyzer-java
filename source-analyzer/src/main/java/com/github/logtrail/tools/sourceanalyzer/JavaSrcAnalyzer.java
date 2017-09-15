package com.github.logtrail.tools.sourceanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
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

    private List<String> srcRoots, excludes;
    private String outputFile;
    private static final Set<String> LOG_METHODS = new HashSet<String>();
    private int fileCount = 0, logCount = 0;
    private List<LogStatement> logStatements;
    private static final String REGEX_SPECIAL_CHARS = "[\\<\\(\\[\\\\\\^\\-\\=\\$\\!\\|\\]\\)‌​\\?\\*\\+\\.\\>]";
    private static Logger LOGGER = LoggerFactory.getLogger(JavaSrcAnalyzer.class);
    private static final String FORMAT_ANCHOR = "{}";
    private static final Pattern LOG_FORMAT_ANCHOR_PATTERN = Pattern.compile("\\{}");
    private static final Pattern REGEX_SPECIAL_CHARS_PATTERN = Pattern.compile(REGEX_SPECIAL_CHARS);
    private static final String DEFAULT_CONTEXT_NAME = "default-context";
    private LogContext context;

    static {
        LOG_METHODS.add("debug");
        LOG_METHODS.add("trace");
        LOG_METHODS.add("info");
        LOG_METHODS.add("warn");
        LOG_METHODS.add("error");
    }

    public JavaSrcAnalyzer(List<String> srcRoots, List<String> excludes,
                           String outputFile, String context)
            throws IOException, ParseException {
        this.srcRoots = srcRoots;
        this.excludes = excludes;
        this.outputFile = outputFile;
        this.context = LogContext.valueOf(context);
    }

    public JavaSrcAnalyzer(Properties properties) {
        this.srcRoots = Arrays.asList(properties.getProperty("src.roots").split(":"));
        String excludes = properties.getProperty("src.excludes");
        if (excludes != null && excludes.trim().length() > 0) {
            this.excludes = Arrays.asList(excludes.split(":"));
        }
        this.outputFile = properties.getProperty("patterns.out.file");
        this.context = LogContext.valueOf(properties.getProperty("context"));
    }

    public void analyze() throws IOException {

        for (String srcRoot : srcRoots) {
            Path path = Paths.get(srcRoot);
            if (Files.isDirectory(path)) {
                try {
                    logStatements = new ArrayList<>();
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
                    System.out.println(MessageFormat.format("Analyzed {0} logs in {1} files", logCount, fileCount));
                } finally {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile), logStatements);
                }
            } else {
                LOGGER.error("Specify a valid src directory: {}", srcRoot);
            }
        }
    }

    private void analyzeFile(File file) throws IOException {
        CompilationUnit cu = JavaParser.parse(file);
        List<MethodCallExpr> methodCallExprList = cu.getChildNodesByType(MethodCallExpr.class);

        Optional<PackageDeclaration> packageDec = cu.getPackageDeclaration();

        //Get all field declarations in this file
        HashMultimap<String, String> classToFieldsMap = getFieldsInClass(cu);

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
                        try {
                            logStatement.setMessageRegEx(convertToRegEx(message));
                            if (args != null) {
                                logStatement.setArgs(convertArgsToMap(args));
                            }

                        } catch (PatternSyntaxException ex) {
                            LOGGER.warn("Exception while converting regex {} in file {}. Message {}", message, file, ex.getMessage());
                        }
                        logCount++;
                        String clazz = getLogDeclarationClass(methodCallExpr, classToFieldsMap, file);
                        if (clazz != null) {
                            if (context == LogContext.FQN && packageDec.isPresent()) {
                                clazz = packageDec.get().getNameAsString() + "." + clazz;
                            }
                        }

                        logStatement.setContext(clazz != null ? clazz : DEFAULT_CONTEXT_NAME);
                        logStatement.setLevel(methodName);
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

    private Map<String, String> convertArgsToMap(List<String> args) {
        Map<String, String> argsMap = new LinkedHashMap<>();
        int i = 1;
        for (String arg : args) {
            argsMap.put("arg" + i++, arg);
        }
        return argsMap;
    }

    //Creates regEx pattern from message with named groups
    private Pattern convertToRegEx(String message) {
        String cleanedUpMessage = REGEX_SPECIAL_CHARS_PATTERN.matcher(message).replaceAll("\\\\$0");
        int argCount = 1;
        while (cleanedUpMessage.contains(FORMAT_ANCHOR)) {
            Matcher matcher = LOG_FORMAT_ANCHOR_PATTERN.matcher(cleanedUpMessage);
            cleanedUpMessage = matcher.replaceFirst("(?<arg" + argCount + ">[\\\\S]+)");
            argCount++;
        }
        return Pattern.compile(cleanedUpMessage);
    }


    private String getLogDeclarationClass(MethodCallExpr methodCallExpr, SetMultimap<String, String> classToFieldsMap, File file) {
        Optional<Expression> scope = methodCallExpr.getScope();
        String logClass = DEFAULT_CONTEXT_NAME;
        NameExpr nameExpr = null;
        if (scope.isPresent() && scope.get() instanceof NameExpr) {
            nameExpr = (NameExpr) scope.get();
        } else {
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
            CommandLineParser parser = new DefaultParser();
            CommandLine commandLine = parser.parse(options(), args);

            if (commandLine.hasOption("f")) {
                String configPath = commandLine.getOptionValue('f');
                Properties config = new Properties();
                config.load(new FileInputStream(configPath));
                JavaSrcAnalyzer srcAnalyzer = new JavaSrcAnalyzer(config);
                srcAnalyzer.analyze();
                String elasticsearchUrl = config.getProperty("elasticsearch.url");
                if (elasticsearchUrl != null && !elasticsearchUrl.isEmpty()) {
                    ElasticOutput elasticOutput = new ElasticOutput(elasticsearchUrl);
                    elasticOutput.init();
                    elasticOutput.deletePatternsType(); //delete existing patterns on every run..
                    elasticOutput.writeDocuments(srcAnalyzer.logStatements);
                    elasticOutput.cleanup();
                }
            }

//            String srcRootsStr = commandLine.getOptionValue('s');
//            String excludes = commandLine.getOptionValue('e');
//            String output = commandLine.getOptionValue('o');
//            if (output == null) {
//                output = "patterns.json";
//            }
//            String context = commandLine.getOptionValue('c');
//            if (context == null) {
//                context = "SIMPLE_NAME";
//            }
//
//            JavaSrcAnalyzer srcAnalyzer = new JavaSrcAnalyzer(Arrays.asList(srcRootsStr.split(":")),
//                    excludes != null ? Arrays.asList(excludes.split(":")) : null, output, context,
//                    commandLine.getOptionValue('h'));
//            srcAnalyzer.analyze();
//            String elasticUrl = commandLine.getOptionValue('h');
//            if (elasticUrl != null) {
//                ElasticOutput elasticOutput = new ElasticOutput(elasticUrl);
//                elasticOutput.init();
//                elasticOutput.writeDocuments(srcAnalyzer.logStatements);
//                elasticOutput.cleanup();
//            }
        } catch (org.apache.commons.cli.ParseException e) {
            System.err.println(e.getMessage());
        }
    }

    private static Options options() {
        Options options = new Options();
        options.addRequiredOption("f","config",true,"Path to configuration properties file");
//
//        options.addRequiredOption("s", "src", true, "Colon separated list of source dirs");
//        options.addOption("o", "output", true, "Output file path to write patterns. Default : patterns.json");
//        options.addOption("c", "context", true, "Log Context . SIMPLE_NAME | FQN. Default : SIMPLE_NAME");
//        options.addOption("e", "exclude", true, "Colon separated list of dirs to exclude from analysis");
//        options.addOption("h", "elasticsearch", true, "Elasticsearch connection URL");
        return options;
    }

    enum LogContext {
        SIMPLE_NAME, FQN
    }
}
