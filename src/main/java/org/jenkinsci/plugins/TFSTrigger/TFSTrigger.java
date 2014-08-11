package org.jenkinsci.plugins.TFSTrigger;

import antlr.ANTLRException;
import com.microsoft.tfs.core.TFSTeamProjectCollection;
import com.microsoft.tfs.core.clients.framework.location.ConnectOptions;
import com.microsoft.tfs.core.clients.versioncontrol.VersionControlClient;
import com.microsoft.tfs.core.clients.versioncontrol.exceptions.ServerPathFormatException;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.*;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.DateVersionSpec;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.VersionSpec;
import com.microsoft.tfs.core.httpclient.Credentials;
import com.microsoft.tfs.core.httpclient.UsernamePasswordCredentials;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Item;
import hudson.triggers.Trigger;
import hudson.util.SequentialExecutionQueue;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.lib.xtrigger.XTriggerDescriptor;
import org.jenkinsci.lib.xtrigger.XTriggerException;
import org.jenkinsci.lib.xtrigger.XTriggerLog;
import org.kohsuke.stapler.DataBoundConstructor;
import org.jenkinsci.lib.xtrigger.AbstractTrigger;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;

public class TFSTrigger extends AbstractTrigger {

    private String tfsServerUrl = null;
    private String tfsServerUsername = null;
    private String tfsServerPassword = null;

    private List<TFSProjectPath> tfsProjectPaths = new ArrayList<TFSProjectPath>();

    @DataBoundConstructor
    public TFSTrigger(String cronTabSpec, String tfsServerUrl, String tfsServerUsername, String tfsServerPassword,
                      List<TFSProjectPath> tfsProjectPaths) throws ANTLRException {
        super(cronTabSpec);

        this.tfsServerUrl = tfsServerUrl;
        this.tfsServerUsername = tfsServerUsername;
        this.tfsServerPassword = tfsServerPassword;
        this.tfsProjectPaths = tfsProjectPaths;

    }

    public List<TFSProjectPath> getTfsProjectPaths() {
        return tfsProjectPaths;
    }

    public String getTfsServerUrl() {
        return tfsServerUrl;
    }

    public String getTfsServerUsername() {
        return tfsServerUsername;
    }

    public String getTfsServerPassword() {
        return tfsServerPassword;
    }

    @Override
    protected File getLogFile() {
        return new File(job.getRootDir(), "tfsbuildtrigger-polling.log");
    }

    @Override
    protected boolean requiresWorkspaceForPolling() {
        return false;
    }

    @Override
    protected boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException {

        Boolean changesFound = false;

        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

        log.info("Ensuring native libraries are configured...");
        ensureNativeLibrariesConfigured(log);
        log.info("Done.");

        try {
            log.info("Beginning check for modifications.");

            if (tfsProjectPaths != null && !tfsProjectPaths.isEmpty()) {

                TFSTeamProjectCollection projectCollection = null;
                VersionControlClient versionControlClient = null;

                log.info("Establishing connection to TFS...");
                try {
                    projectCollection = getTeamProjectCollection();
                    versionControlClient = projectCollection.getVersionControlClient();
                } catch (com.microsoft.tfs.core.exceptions.TECoreException ex) {
                    log.info("TFS Exception caught: " + ex.getMessage());
                    throw ex;
                }
                log.info("Done.");

                Calendar lastBuildDate = getDateAndTimeOfLastBuild(log);
                Calendar currentTime = Calendar.getInstance();

                if (lastBuildDate == null) {
                    log.info("No builds have been performed, assuming a build needs to happen.");
                    return true;
                }

                Date searchStart = lastBuildDate.getTime();
                Date searchEnd = currentTime.getTime();

                log.info("Using a search range from " + dateFormatter.format(searchStart) +
                        " to " + dateFormatter.format(searchEnd));

                VersionSpec historyRangeStart = new DateVersionSpec(lastBuildDate);
                VersionSpec historyRangeEnd = new DateVersionSpec(currentTime);

                List<String> pathsWithoutChanges = new LinkedList<String>();

                for (TFSProjectPath path : tfsProjectPaths) {
                    boolean changesFoundInCurrentPath = checkIfProjectPathHasChanges(versionControlClient, log,
                                path.getTfsProjectPath(), historyRangeStart, historyRangeEnd);
                    changesFound |= changesFoundInCurrentPath;
                    if (!changesFoundInCurrentPath) {
                        pathsWithoutChanges.add(path.getTfsProjectPath());
                    }
                }

                if (pathsWithoutChanges.size() > 0) {
                    log.info("The following paths did not have any detected changes:");
                    log.info("------------------------------------------------------");
                    for (String pathWithoutChanges : pathsWithoutChanges) {
                        log.info(pathWithoutChanges);
                    }
                }

                versionControlClient.close();
                projectCollection.close();
            } else {
                log.info("There are no configured TFS project paths.");
            }

            log.info("Check for modifications complete.  Changes Found = " + changesFound.toString());
        }
        catch (XTriggerException e) {
            log.info("An exception was thrown while checking for modifications: " + e.getMessage());
            throw(e);
        }

        return changesFound;
    }

    static synchronized void ensureNativeLibrariesConfigured(XTriggerLog log) {
        final String nativeFolder = System.getProperty("com.microsoft.tfs.jni.native.base-directory");
        if (nativeFolder == null) {
            final Class<TFSTeamProjectCollection> metaclass = TFSTeamProjectCollection.class;
            final ProtectionDomain protectionDomain = metaclass.getProtectionDomain();
            final CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource == null) {
                return;
            }
            final URL location = codeSource.getLocation();

            final String u = location.toString();
            URI locationUri;
            if (u.startsWith("jar:file:") && u.endsWith("!/")) {
                locationUri = URI.create(u.substring(4, u.length() - 2));
            }
            else if (u.startsWith("file:")) {
                locationUri = URI.create(u);
            }
            else {
                return;
            }
            final File pathToJar = new File(locationUri);
            final File pathToLibFolder = pathToJar.getParentFile();
            final File pathToNativeFolder = new File(pathToLibFolder, "native");

            if (pathToNativeFolder != null) {
                log.info("Using Native Path: " + pathToNativeFolder.toString());
            } else {
                log.info("Path to native folder is null.  :-(");
            }

            System.setProperty("com.microsoft.tfs.jni.native.base-directory", pathToNativeFolder.toString());
        } else {
            log.info("Property is already configured.  Native folder is: " + nativeFolder);
        }
    }

    private TFSTeamProjectCollection getTeamProjectCollection() throws XTriggerException {

        Credentials tfsServerCredentials = getTfsCredentials();
        URI tfsServerUri = getTfsServerUri();

        return new TFSTeamProjectCollection(tfsServerUri, tfsServerCredentials);
    }

    private Credentials getTfsCredentials() {
        return new UsernamePasswordCredentials(tfsServerUsername, tfsServerPassword);
    }

    private URI getTfsServerUri() throws XTriggerException {

        URI serverUri = null;

        try {
            serverUri = new URI(tfsServerUrl);
        }
        catch(URISyntaxException e) {
            throw new XTriggerException(e.getMessage());
        }

        return serverUri;
    }

    private Calendar getDateAndTimeOfLastBuild(XTriggerLog log) {

        if (job instanceof AbstractProject) {
            AbstractProject project = (AbstractProject)job;
            AbstractBuild lastBuild = project.getLastBuild();
            if (lastBuild != null) {
                return lastBuild.getTimestamp();
            }
        } else {
            log.info("The job is not an instance of AbstractProject.");
        }

        return null;
    }

    protected Boolean checkIfProjectPathHasChanges(VersionControlClient vcc, XTriggerLog log, String tfsProjectPath,
                                                   VersionSpec historyRangeStart, VersionSpec historyRangeEnd)
            throws XTriggerException {

        try {
            Changeset[] listOfChanges = vcc.queryHistory(tfsProjectPath, historyRangeEnd, 0, RecursionType.FULL, null,
                    historyRangeStart, historyRangeEnd, Integer.MAX_VALUE, true, true, false, false);

            if (listOfChanges.length > 0) {
                logInformationAboutChanges(log, tfsProjectPath, listOfChanges);
            }

            return (listOfChanges.length > 0);
        }
        catch (ServerPathFormatException e) {
            throw new XTriggerException(e.getMessage());
        }
    }

    private String createStringOfDashes(int length) {
        StringBuilder dashes = new StringBuilder();
        for (int i = 0; i < length; i++) {
            dashes.append('-');
        }
        return dashes.toString();
    }

    protected void logInformationAboutChanges(XTriggerLog log, String tfsProjectPath, Changeset[] listOfChanges) {

        log.info(tfsProjectPath);
        log.info(createStringOfDashes(tfsProjectPath.length()));

        for (Changeset change : listOfChanges) {
            log.info("Change Set #" + change.getChangesetID());
            Change[] changes = change.getChanges();
            for (Change itemChange : changes) {
                com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Item item = itemChange.getItem();
                String serverItemPath = item.getServerItem();
                if (serverItemPath != null && !serverItemPath.isEmpty()) {
                    log.info(serverItemPath);
                }
            }
            log.info("\n");
        }
    }

    @Override
    protected String getName() {
        return TFSTriggerCause.NAME;
    }

    @Override
    protected String getCause() {
        return TFSTriggerCause.CAUSE;
    }

    @Override
    protected Action[] getScheduledActions(Node node, XTriggerLog xTriggerLog) {
        return new Action[0];
    }

    @Extension
    public static class TFSTriggerDescriptor extends XTriggerDescriptor {

        @Override
        public String getDisplayName() {
            return "TFS Trigger";
        }

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

        @Override
        public TFSTrigger newInstance(StaplerRequest req, JSONObject formData) throws FormException {

            String cronTabSpec = formData.getString("cronTabSpec");
            String tfsServerUrl = formData.getString("tfsServerUrl");
            String tfsServerUsername = formData.getString("tfsServerUsername");
            String tfsServerPassword = formData.getString("tfsServerPassword");

            List<TFSProjectPath> tfsProjectPaths = new ArrayList<TFSProjectPath>();

            Object tfsProjectPathsObject = formData.get("tfsProjectPaths");
            if (tfsProjectPathsObject instanceof JSONObject) {
                tfsProjectPaths.add(createProjectPathFromObject((JSONObject)tfsProjectPathsObject));
            } else if (tfsProjectPathsObject instanceof JSONArray) {
                for (Object singleTfsProjectPathObject : (JSONArray)tfsProjectPathsObject ) {
                    tfsProjectPaths.add(createProjectPathFromObject((JSONObject)singleTfsProjectPathObject));
                }
            }

            TFSTrigger trigger = null;
            try {
               trigger = new TFSTrigger(cronTabSpec, tfsServerUrl, tfsServerUsername, tfsServerPassword, tfsProjectPaths);
            }
            catch (ANTLRException e) {
               throw new RuntimeException(e.getMessage());
            }

            return trigger;
        }

        TFSProjectPath createProjectPathFromObject(JSONObject source) {
            String path = source.getString("tfsProjectPath");
            return new TFSProjectPath(path);
        }
    }

}
