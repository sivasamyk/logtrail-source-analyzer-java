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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

    private List<String> srcRoots,excludes;
    private String outputFile;
    private static final Set<String> LOG_METHODS = new HashSet<String>();
    private int fileCount = 0, logCount = 0;
    private List<LogStatement> logStatements;
    private static final String REGEX_SPECIAL_CHARS = "[\\<\\(\\[\\\\\\^\\-\\=\\$\\!\\|\\]\\)‌​\\?\\*\\+\\.\\>]";
    private static Logger LOGGER = LoggerFactory.getLogger(JavaSrcAnalyzer.class);
    private static final Pattern LOG_FORMAT_ELEMENT_PATTERN = Pattern.compile("\\{}");
    private static final Pattern REGEX_SPECIAL_CHARS_PATTERN = Pattern.compile(REGEX_SPECIAL_CHARS);
    private LogContext context;

    static {
        LOG_METHODS.add("debug");
        LOG_METHODS.add("trace");
        LOG_METHODS.add("info");
        LOG_METHODS.add("warn");
        LOG_METHODS.add("error");
    }

    public JavaSrcAnalyzer(List<String> srcRoots, List<String> excludes, String outputFile, String context)
            throws IOException, ParseException {
        this.srcRoots = srcRoots;
        this.excludes = excludes;
        this.outputFile = outputFile;
        this.context = LogContext.valueOf(context);
    }

    public void analyze() throws IOException {

        for (String srcRoot : srcRoots) {
            Path path = Paths.get(srcRoot);
            if (Files.isDirectory(path)) {
                try {
                    logStatements = new ArrayList<>();
                    System.out.println("Walking src" + srcRoot);
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
                                    if (dir.toString().matches(exclude)) {
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
                    Expression firstArg = methodCallExpr.getArguments().get(0);
                    if (firstArg instanceof StringLiteralExpr) {
                        String logString = ((StringLiteralExpr) firstArg).asString();
                        JavaSrcAnalyzer.LogStatement logStatement = new JavaSrcAnalyzer.LogStatement();
                        try {
                            logStatement.args = getArgs(methodCallExpr);
                            logStatement.messageRegEx = convertToRegEx(logString);
                            String clazz = getLogDeclarationClass(methodCallExpr, classToFieldsMap, file);
                            if (context == LogContext.FQN && packageDec.isPresent()) {
                                clazz = packageDec.get().getNameAsString() + "." + clazz;
                            }
                            logStatement.context = clazz;
                            logStatement.level = methodName;
                            Optional<MethodDeclaration> method = methodCallExpr.getAncestorOfType(MethodDeclaration.class);
                            method.ifPresent(methodDeclaration -> logStatement.method = methodDeclaration.getNameAsString());
                            logStatements.add(logStatement);
                        } catch (PatternSyntaxException ex) {
                            LOGGER.warn("Exception while converting regex {} in file {}. Message {}", logString, file, ex.getMessage());
                        }
                        logCount++;
                    } else {
                        LOGGER.warn("Cannot resolve logger statement {} in file {}", methodCallExpr, file);
                    }
                } else {
                    LOGGER.debug("logger statement with no args: {} in file {}", methodCallExpr, file);
                }
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

    private Map<String, String> getArgs(MethodCallExpr methodCallExpr) {
        Map<String, String> args = null;
        NodeList<Expression> argumentList = methodCallExpr.getArguments();
        if (argumentList.size() > 1) {
            for (int i = 1; i < argumentList.size(); i++) {
                if (args == null) {
                    args = new LinkedHashMap<>();
                }
                args.put("arg" + i, argumentList.get(i).toString());
            }
        }
        return args;
    }

    //Creates regEx pattern from message with named groups
    private Pattern convertToRegEx(String message) {
        String cleanedUpMessage = REGEX_SPECIAL_CHARS_PATTERN.matcher(message).replaceAll("\\\\$0");
        int argCount = 1;
        while (cleanedUpMessage.contains("{}")) {
            Matcher matcher = LOG_FORMAT_ELEMENT_PATTERN.matcher(cleanedUpMessage);
            cleanedUpMessage = matcher.replaceFirst("(?<arg" + argCount + ">[\\\\S]+)");
            argCount++;
        }
        return Pattern.compile(cleanedUpMessage);
    }


    private String getLogDeclarationClass(MethodCallExpr methodCallExpr, SetMultimap<String, String> classToFieldsMap, File file) {
        Optional<Expression> scope = methodCallExpr.getScope();
        String logClass = "default-context";
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
        return logClass;
    }

    static class LogStatement {
        private Pattern messageRegEx;
        private String context;
        private String level;
        private String method;
        private Map<String, String> args;

        public Pattern getMessageRegEx() {
            return messageRegEx;
        }

        public void setMessageRegEx(Pattern messageRegEx) {
            this.messageRegEx = messageRegEx;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public Map<String, String> getArgs() {
            return args;
        }

        public void setArgs(Map<String, String> args) {
            this.args = args;
        }

        public String toString() {
            return this.context + "|" + this.messageRegEx.pattern();
        }

    }

    public static void main(String args[]) throws Exception {
        try {
            CommandLineParser parser = new DefaultParser();
            CommandLine commandLine = parser.parse(options(), args);

            String srcRootsStr = commandLine.getOptionValue('s');
            String excludes = commandLine.getOptionValue('e');
            String output = commandLine.getOptionValue('o');
            if (output == null) {
                output = "patterns.json";
            }
            String context = commandLine.getOptionValue('c');
            if (context == null) {
                context = "SIMPLE_NAME";
            }

            JavaSrcAnalyzer srcAnalyzer = new JavaSrcAnalyzer(Arrays.asList(srcRootsStr.split(":")),
                    excludes != null ? Arrays.asList(excludes.split(":")) : null, output, context);
            srcAnalyzer.analyze();
        } catch (org.apache.commons.cli.ParseException e) {
            System.err.println(e.getMessage());
        }
    }

    private static Options options() {
        Options options = new Options();
        options.addRequiredOption("s", "src", true, "Colon separated list of source dirs");
        options.addOption("o", "output", true, "Output file path to write patterns. Default : patterns.json");
        options.addOption("c", "context", true, "Log Context . SIMPLE_NAME | FQN. Default : SIMPLE_NAME");
        options.addOption("e","exclude",true,"Colon separated list of dirs to exclude from analysis");
        return options;
    }

    enum LogContext {
        SIMPLE_NAME, FQN
    }
}
