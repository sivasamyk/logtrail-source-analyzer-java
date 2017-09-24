package com.github.logtrail.tools.sourceanalyzer;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
* Created by skaliappan on 9/14/17.
*/
public class LogStatement {
    private String messageRegEx;
    private String context;
    private String level;
    private String method;
    private String messageId; // To be removed
    private List<String> args;

    public String getMessageRegEx() {
        return messageRegEx;
    }

    public void setMessageRegEx(String messageRegEx) {
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

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public String toString() {
        return this.context + "|" + this.messageRegEx;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
}
