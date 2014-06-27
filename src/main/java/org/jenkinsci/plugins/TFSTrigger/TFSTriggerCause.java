package org.jenkinsci.plugins.TFSTrigger;

import hudson.model.Cause;

public class TFSTriggerCause extends Cause {

    public static final String NAME = "TFSTrigger";
    public static final String CAUSE = "A change within a specified TFS Project Path";

    @Override
    public String getShortDescription() {
        return String.format("[%s] - %s", NAME, CAUSE);
    }
}
