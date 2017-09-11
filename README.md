# Logtrail Source Analyzer Java
Source Analyzer for Java Language. Parse the source code to analyze and extract logger statements into JSON with configured context.  

## Usage

```
java -jar source-analyzer.jar -src project/src -context FQN -output patterns.json
```

The context can be SIMPLE_NAME (class name containing the logger statement) or FQN (includes package name) 

## TODO
The parser for now is hard coded for SLF4J API. Has following shortcomings:

* Makes assumption that the logger context is always Class(SIMPLE_NAME) / Package(FQN) containing the logger statement. Cannot handle cases where the logger context is hard-coded string (` LoggerFactor.getLogger("custom-context") `) or present in base class. \When the analyzer is not able to resolve the logger declaration to current class, it will map the log statement to default-context.
* The reg-ex patterns only matches variable values with non-whitespace chars.
e.g. in the below logger statement if `username` evaluates to `John Smith` only `John` will be matched.

```
LOG.info("User {} is logged in", username)
```

* Following logger patterns will **not** be matched (only parameterized logger statement with formatting anchor `{}` will be considered):

```
 LOG.info(log);
 LOG.info("User " + username + " logged in");
```