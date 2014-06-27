package org.jenkinsci.plugins.TFSTrigger;

import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;

public class TFSProjectPath implements Serializable {

    private String tfsProjectPath = null;

    @DataBoundConstructor
    public TFSProjectPath(String tfsProjectPath) {
        this.tfsProjectPath = tfsProjectPath;
    }

    public String getTfsProjectPath() {
        return tfsProjectPath;
    }

    public void setTfsProjectPath(String tfsProjectPath) {
        this.tfsProjectPath = tfsProjectPath;
    }

}
