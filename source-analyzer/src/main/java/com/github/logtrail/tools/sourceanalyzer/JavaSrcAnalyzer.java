package com.github.logtrail.tools.sourceanalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
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

    private String srcRoot, outputFile;
    private static final Set<String> LOG_METHODS = new HashSet<String>();
    private int fileCount = 0, logCount = 0;
    private List<LogStatement> logStatements;
    private static final String REGEX_SPECIAL_CHARS = "[\\<\\(\\[\\\\\\^\\-\\=\\$\\!\\|\\]\\)‌​\\?\\*\\+\\.\\>]";
    private static Logger LOGGER = LoggerFactory.getLogger(JavaSrcAnalyzer.class);
    private static final Pattern LOG_FORMAT_ELEMENT_PATTERN = Pattern.compile("\\{}");
    private static final Pattern REGEX_SPECIAL_CHARS_PATTERN = Pattern.compile(REGEX_SPECIAL_CHARS);
    private LogContext context;
    private Properties configProperties;

    static {
        LOG_METHODS.add("debug");
        LOG_METHODS.add("trace");
        LOG_METHODS.add("info");
        LOG_METHODS.add("warn");
        LOG_METHODS.add("error");
    }

    public JavaSrcAnalyzer(String configFile) throws IOException {
        this.parseConfigFile(configFile);
    }

    private void parseConfigFile(String configFile) throws IOException {
        configProperties = new Properties();
        configProperties.load(new FileInputStream(configFile));

        srcRoot = configProperties.getProperty("src.root","src");
        outputFile = configProperties.getProperty("output.file","patterns.json");
        context = LogContext.valueOf(configProperties.getProperty("context","CLASS"));
    }

    public void analyze() throws IOException {

        Path path = Paths.get(srcRoot);
        if (Files.isDirectory(path)) {
            try {
                logStatements = new ArrayList<>();
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".java")) {
                            try {
                                analyzeFile(file.toFile());
                                fileCount++;
                            } catch (ParseProblemException e) {
                                LOGGER.warn("Exception while analyzing file {}", file,e);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                System.out.println(MessageFormat.format("Analyzed {0} logs in {1} files", logCount, fileCount));
            } finally {
                ObjectMapper mapper = new ObjectMapper();
                mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile),logStatements);
            }
        } else {
            System.err.println("Specify a valid src directory");
        }
    }

    private void analyzeFile(File file) throws IOException {
        CompilationUnit cu = JavaParser.parse(file);
        List<MethodCallExpr> methodCallExprList = cu.getChildNodesByType(MethodCallExpr.class);

        Optional<PackageDeclaration> packageDec = cu.getPackageDeclaration();

        //Get all field declarations in this file
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

        //loop through all method calls in this file
        for (MethodCallExpr methodCallExpr : methodCallExprList) {
            String methodName = methodCallExpr.getName().getIdentifier();
            if (LOG_METHODS.contains(methodName)) {
//                SymbolReference<MethodDeclaration> solved = parserFacade.solve(methodCallExpr);
//                if(solved.isSolved()) {
//                    System.out.println(solved.getCorrespondingDeclaration().getPackageName());
//                }
                int argCount = methodCallExpr.getArguments().size();
                if (argCount > 0) {
                    Expression firstArg = methodCallExpr.getArguments().get(0);
                    if (firstArg instanceof StringLiteralExpr) {
                        String logString = ((StringLiteralExpr) firstArg).asString();
                        JavaSrcAnalyzer.LogStatement logStatement = new JavaSrcAnalyzer.LogStatement();
                        try {
                            logStatement.args = getArgs(methodCallExpr);
                            logStatement.messageRegEx = convertToRegEx(logString);
                            String clazz = getLogDeclarationClass(methodCallExpr, classToFieldsMap,file);
                            if(context == LogContext.WITH_PACKAGE && packageDec.isPresent()) {
                                clazz = packageDec.get().getNameAsString() + "." + clazz;
                            }
                            logStatement.clazz = clazz;
                            logStatement.level = methodName;
                            Optional<MethodDeclaration> method = methodCallExpr.getAncestorOfType(MethodDeclaration.class);
                            if (method.isPresent()) {
                                logStatement.method = method.get().getNameAsString();
                            }
                            logStatements.add(logStatement);
                        } catch (PatternSyntaxException ex) {
                            LOGGER.warn("Exception while converting regex {} in file {}. Message {}" , logString, file,ex.getMessage());
                        }
                        logCount++;
                    }
                } else {
                    LOGGER.warn("Cannot resolve logger statement {} in file {}" , methodCallExpr, file);
                }
            }
        }
    }

    private Map<String,String> getArgs(MethodCallExpr methodCallExpr) {
        Map<String,String> args = null;
        NodeList<Expression> argumentList = methodCallExpr.getArguments();
        if (argumentList.size() > 1) {
            for(int i=1;i<argumentList.size();i++) {
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
        int argCount =1;
        while (cleanedUpMessage.contains("{}")) {
            Matcher matcher = LOG_FORMAT_ELEMENT_PATTERN.matcher(cleanedUpMessage);
            cleanedUpMessage = matcher.replaceFirst("(?<arg" + argCount + ">[\\\\S]+)");
            argCount++;
        }
        return Pattern.compile(cleanedUpMessage);
    }


    private String getLogDeclarationClass(MethodCallExpr methodCallExpr, SetMultimap<String, String> classToFieldsMap,File file) {
        Optional<Expression> scope = methodCallExpr.getScope();
        String logClass = "Default-Class";
        NameExpr nameExpr = null;
        if (scope.isPresent() && scope.get() instanceof NameExpr) {
            nameExpr = (NameExpr) scope.get();
        } else {
            LOGGER.warn("Cannot resolve parent class for {} in file {}" , methodCallExpr, file);
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
        private String clazz;
        private String level;
        private String method;
        private Map<String,String> args;

        public Pattern getMessageRegEx() {
            return messageRegEx;
        }

        public void setMessageRegEx(Pattern messageRegEx) {
            this.messageRegEx = messageRegEx;
        }

        public String getClazz() {
            return clazz;
        }

        public void setClazz(String clazz) {
            this.clazz = clazz;
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
            return this.clazz + "|" + this.messageRegEx.pattern();
        }

    }

    public static void main(String args[]) throws Exception {
        if (args.length == 1) {
            if (new File(args[0]).exists()) {
                JavaSrcAnalyzer analyzer = new JavaSrcAnalyzer(args[0]);
                analyzer.analyze();
            } else {
                System.err.println("Cannot find config file : " + args[0]);
            }
        } else {
            System.out.println("Usage: JavaSrcAnalyzer config-file");
        }
    }

    enum LogContext {
        CLASS, WITH_PACKAGE
    }
}
