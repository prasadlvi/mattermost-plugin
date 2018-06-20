package jenkins.plugins.mattermost.workflow;

import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitChangeSet;
import hudson.plugins.git.GitChangeSetList;
import hudson.scm.ChangeLogSet;
import hudson.scm.EditType;
import hudson.tasks.test.AbstractTestResultAction;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.plugins.mattermost.MattermostNotifier;
import jenkins.plugins.mattermost.MattermostService;
import jenkins.plugins.mattermost.StandardMattermostService;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.PrintStream;
import java.util.*;

/**
 * Workflow step to send a Slack channel notification.
 */
public class MattermostSendStep extends AbstractStepImpl {

    private final @Nonnull String message;
    private String color;
    private String channel;
    private String endpoint;
    private String icon;
    private boolean failOnError;


    @Nonnull
    public String getMessage() {
        return message;
    }

    public String getColor() {
        return color;
    }

    @DataBoundSetter
    public void setColor(String color) {
        this.color = Util.fixEmpty(color);
    }


    public String getChannel() {
        return channel;
    }

    @DataBoundSetter
    public void setChannel(String channel) {
        this.channel = Util.fixEmpty(channel);
    }

    public String getEndpoint() {
        return endpoint;
    }

    @DataBoundSetter
    public void setEndpoint(String endpoint) {
        this.endpoint = Util.fixEmpty(endpoint);
    }

    public String getIcon() {
        return icon;
    }

    @DataBoundSetter
    public void setIcon(String icon) {
        this.icon = Util.fixEmpty(icon);
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    @DataBoundSetter
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    @DataBoundConstructor
    public MattermostSendStep(@Nonnull String message) {
        this.message = message;
    }

    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(SlackSendStepExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "mattermostSend";
        }

        @Override
        public String getDisplayName() {
            return "Send Mattermost message";
        }
    }

    public static class SlackSendStepExecution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        @Inject
        transient MattermostSendStep step;

        @StepContextParameter
        transient TaskListener listener;

        @Override
        protected Void run() throws Exception {

            //default to global config values if not set in step, but allow step to override all global settings
            Jenkins jenkins;
            //Jenkins.getInstance() may return null, no message sent in that case
            try {
                jenkins = Jenkins.getInstance();
            } catch (NullPointerException ne) {
                listener.error(String.format("Mattermost notification failed with exception: %s", ne), ne);
                return null;
            }

            MattermostNotifier.DescriptorImpl slackDesc = jenkins.getDescriptorByType(MattermostNotifier.DescriptorImpl.class);
            String team = step.endpoint != null ? step.endpoint : slackDesc.getEndpoint();
            String channel = step.channel != null ? step.channel : slackDesc.getRoom();
            String icon = step.icon != null ? step.icon: slackDesc.getIcon();
            String color = step.color != null ? step.color : "";

            //placing in console log to simplify testing of retrieving values from global config or from step field; also used for tests
            listener.getLogger().printf("Mattermost Send Pipeline step configured values from global config - connector: %s, icon: %s, channel: %s, color: %s", step.endpoint == null, step.icon == null, step.channel == null, step.color == null);

            MattermostService slackService = getMattermostService(team, channel, icon);
            boolean publishSuccess;
            if(StringUtils.isBlank(step.message)) {
                WorkflowRun build = getContext().get(WorkflowRun.class);
                publishSuccess = slackService.publish(getBuildStatusJSON(build, listener.getLogger()), color);
            } else {
                publishSuccess = slackService.publish(step.message, color);
            }
            if (!publishSuccess && step.failOnError) {
                throw new AbortException("Mattermost notification failed. See Jenkins logs for details.");
            } else if (!publishSuccess) {
                listener.error("Slack notification failed. See Jenkins logs for details.");
            }
            return null;
        }

        //streamline unit testing
        MattermostService getMattermostService(String team, String channel, String icon) {
            return new StandardMattermostService(team, channel, icon);
        }

    }

    private static JSONObject getBuildStatusJSON(WorkflowRun build, PrintStream logger) {
        MessageBuilder message = new MessageBuilder(build)
                .appendProjectAsAuthor()
                .appendCommitsAsText()
                .appendStatus()
                .appendDuration()
                .appendChanges()
                .appendTestSummary();
        return message.getMattermostJSON();
    }

    public static class MessageBuilder {

        private static final String BACK_TO_NORMAL_STATUS_MESSAGE = ":white_check_mark: Back to normal",
                STILL_FAILING_STATUS_MESSAGE = ":no_entry_sign: Still Failing",
                SUCCESS_STATUS_MESSAGE = ":white_check_mark: Success",
                FAILURE_STATUS_MESSAGE = ":no_entry_sign: Failure",
                ABORTED_STATUS_MESSAGE = ":warning: Aborted",
                NOT_BUILT_STATUS_MESSAGE = ":warning: Not built",
                UNSTABLE_STATUS_MESSAGE = ":warning: Unstable",
                UNKNOWN_STATUS_MESSAGE = ":question: Unknown";

        private final WorkflowRun build;
        private final JSONObject attachment;
        private final JSONArray fields;
        private final JSONObject json;
        private final String buildServerUrl;

        public MessageBuilder(WorkflowRun build) {
            this.build = build;

            json = new JSONObject();

            JSONArray attachments = new JSONArray();
            json.put("attachments", attachments);

            attachment = new JSONObject();
            attachments.put(attachment);

            fields = new JSONArray();
            attachment.put("fields", fields);

            JenkinsLocationConfiguration jenkinsConfig = new JenkinsLocationConfiguration();
            buildServerUrl = jenkinsConfig.getUrl();

        }

        public JSONObject getMattermostJSON() {
            return json;
        }

        public MessageBuilder appendProjectAsAuthor() {
            String authorName = build.getParent().getDisplayName() + " " + build.getDisplayName();
            attachment.put("author_name", authorName);
            attachment.put("author_link", buildServerUrl + build.getUrl());

            return this;
        }

        public MessageBuilder appendStatus() {
            JSONObject field = new JSONObject();
            field.put("short", true);
            field.put("title", "Status");
            field.put("value", getStatusMessage());

            fields.put(field);
            return this;
        }

        public MessageBuilder appendDuration() {
            JSONObject field = new JSONObject();
            field.put("short", true);
            field.put("title", "Duration");

            String durationString;
            if (getStatusMessage().equals(BACK_TO_NORMAL_STATUS_MESSAGE)) {
                durationString = createBackToNormalDurationString();
            } else {
                durationString = Util.getTimeSpanString(getDuration());
            }

            field.put("value", durationString);

            fields.put(field);
            return this;
        }

        public MessageBuilder appendCommitsAsText() {
            attachment.put("text", getCommitList(build));
            return this;
        }

        public MessageBuilder appendChanges() {
            String changes = getChanges();
            if (changes == null) {
                return this;
            }

            JSONObject field = new JSONObject();
            field.put("short", false);
            field.put("title", "Changes");
            field.put("value", changes);

            fields.put(field);
            return this;
        }

        private MessageBuilder appendTestSummary() {

            JSONObject field = new JSONObject();
            field.put("short", false);
            field.put("title", "Test Summary");

            AbstractTestResultAction<?> action = this.build
                    .getAction(AbstractTestResultAction.class);
            if (action != null) {
                int total = action.getTotalCount();
                int failed = action.getFailCount();
                int skipped = action.getSkipCount();
                String message =
                        "| Passed | Failed | Skipped |\n" +
                                "|  :---: |  :---: |  :---:  |\n" +
                                "| " + (total - failed - skipped) +
                                "| " + failed +
                                "| " + skipped + " |";
                field.put("value", message);
            } else {
                field.put("value", "No Tests found.");
            }

            fields.put(field);
            return this;
        }

        private String createBackToNormalDurationString() {
            Run previousSuccessfulBuild = build.getPreviousSuccessfulBuild();
            if (previousSuccessfulBuild == null) {
                return "unknown";
            }
            long previousSuccessStartTime = previousSuccessfulBuild.getStartTimeInMillis();
            long previousSuccessDuration = previousSuccessfulBuild.getDuration();
            long previousSuccessEndTime = previousSuccessStartTime + previousSuccessDuration;
            long buildStartTime = build.getStartTimeInMillis();
            long buildDuration = getDuration();
            long buildEndTime = buildStartTime + buildDuration;
            long backToNormalDuration = buildEndTime - previousSuccessEndTime;
            return Util.getTimeSpanString(backToNormalDuration);
        }

        private long getDuration() {
            return System.currentTimeMillis() - build.getStartTimeInMillis();
        }

        private String getStatusMessage() {
            Result result = build.getResult();
            Result previousResult;
            Run lastBuild = build.getPreviousBuild();
            Run previousBuild = (lastBuild != null) ? lastBuild.getPreviousBuild() : null;
            Run previousSuccessfulBuild = build.getPreviousSuccessfulBuild();
            boolean buildHasSucceededBefore = previousSuccessfulBuild != null;

            /*
             * If the last build was aborted, go back to find the last non-aborted build.
             * This is so that aborted builds do not affect build transitions.
             * I.e. if build 1 was failure, build 2 was aborted and build 3 was a success the transition
             * should be failure -> success (and therefore back to normal) not aborted -> success.
             */
            Run lastNonAbortedBuild = previousBuild;
            while (lastNonAbortedBuild != null && lastNonAbortedBuild.getResult() == Result.ABORTED) {
                lastNonAbortedBuild = lastNonAbortedBuild.getPreviousBuild();
            }


            /* If all previous builds have been aborted, then use
             * SUCCESS as a default status so an aborted message is sent
             */
            if (lastNonAbortedBuild == null) {
                previousResult = Result.SUCCESS;
            } else {
                previousResult = lastNonAbortedBuild.getResult();
            }

            /* Back to normal should only be shown if the build has actually succeeded at some point.
             * Also, if a build was previously unstable and has now succeeded the status should be
             * "Back to normal"
             */
            if (result == Result.SUCCESS
                    && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
                    && buildHasSucceededBefore) {
                return BACK_TO_NORMAL_STATUS_MESSAGE;
            }
            if (result == Result.FAILURE && previousResult == Result.FAILURE) {
                return STILL_FAILING_STATUS_MESSAGE;
            }
            if (result == Result.SUCCESS) {
                return SUCCESS_STATUS_MESSAGE;
            }
            if (result == Result.FAILURE) {
                return FAILURE_STATUS_MESSAGE;
            }
            if (result == Result.ABORTED) {
                return ABORTED_STATUS_MESSAGE;
            }
            if (result == Result.NOT_BUILT) {
                return NOT_BUILT_STATUS_MESSAGE;
            }
            if (result == Result.UNSTABLE) {
                return UNSTABLE_STATUS_MESSAGE;
            }
            return UNKNOWN_STATUS_MESSAGE;
        }

        public String toString() {
            return attachment.toString();
        }

        private String getCommitList(WorkflowRun build) {
            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = build.getChangeSets();
            List<GitChangeSetList> entries = new LinkedList<>();
            for (Object o : changeSets) {
                GitChangeSetList entry = (GitChangeSetList) o;
                entries.add(entry);
            }

            Set<String> commits = new HashSet<>();
            for (GitChangeSetList entry : entries) {
                for (GitChangeSet gitChangeSet : entry) {
                    StringBuilder commit = new StringBuilder();
                    commit.append(gitChangeSet.getMsg());
                    commit.append(" [").append(gitChangeSet.getAuthor().getDisplayName()).append("]");
                    commits.add(commit.toString());
                }
            }

            if(entries.isEmpty()) {
                return "No Changes.";
            }

            return "- " + StringUtils.join(commits, "\n- ");
        }

        private String getChanges() {

            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = build.getChangeSets();

            if (changeSets == null) {
                return null;
            }

            List<GitChangeSetList> gitChangeSetLists = new LinkedList<>();
            for (Object o : changeSets) {
                GitChangeSetList entry = (GitChangeSetList) o;
                gitChangeSetLists.add(entry);
            }

            List<GitChangeSet> entries = new ArrayList<>();
            Set<ChangeLogSet.AffectedFile> files = new HashSet<>();
            for (GitChangeSetList changeSetList : gitChangeSetLists) {
                for (GitChangeSet gitChangeSet : changeSetList) {
                    entries.add(gitChangeSet);
                    files.addAll(gitChangeSet.getAffectedFiles());
                }
            }

            if (entries.isEmpty()) {
                return null;
            }

            Set<String> authors = new HashSet<>();
            for (ChangeLogSet.Entry entry : entries) {
                authors.add(entry.getAuthor().getDisplayName());
            }

            StringBuilder message = new StringBuilder();
            message.append(":checkered_flag: Started by changes from ");
            message.append(StringUtils.join(authors, ", "));
            message.append(" (");
            message.append(files.size());
            message.append((files.size() == 1) ? " file" : " files").append(" changed):  \n");

            int fileCount = 0;
            outer: for (ChangeLogSet.Entry entry : entries) {
                for (ChangeLogSet.AffectedFile file : entry.getAffectedFiles()) {
                    if (fileCount > 7) {
                        message.append("*...file list truncated for display.*");
                        break outer;
                    }
                    message.append((file.getEditType() == EditType.DELETE) ? "~~" : "")
                            .append("``").append(file.getPath()).append("``")
                            .append((file.getEditType() == EditType.DELETE) ? "~~" : "")
                            .append("  \n");
                    fileCount++;
                }
            }

            return message.toString();
        }
    }

}
