package jenkins.plugins.mattermost.workflow;

import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.tasks.Publisher;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import jenkins.plugins.mattermost.*;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
            listener.getLogger().println("All items");
            for (Item a : jenkins.getAllItems()) {
                listener.getLogger().println(a.getFullName() + " : " + a.getClass().getName());
            }

            int buildNumber = 0;
            for (Job job: jenkins.getItemByFullName("pipeline-multibranch").getAllJobs()) {
                RunList<Run> builds = job.getBuilds();
                for (Run r : builds) {
                    if(r.getResult() == null) {
                        buildNumber = r.number;
                    }
                }
            }

            for (Item a : jenkins.getAllItems()) {
                listener.getLogger().println(job.getFullName());
            }


            AbstractProject project = jenkins.getItemByFullName("pipeline-multibranch", AbstractProject.class);
            AbstractBuild build = project.getBuildByNumber(buildNumber);


            MattermostNotifier.DescriptorImpl slackDesc = jenkins.getDescriptorByType(MattermostNotifier.DescriptorImpl.class);
            String team = step.endpoint != null ? step.endpoint : slackDesc.getEndpoint();
            String channel = step.channel != null ? step.channel : slackDesc.getRoom();
            String icon = step.icon != null ? step.icon: slackDesc.getIcon();
            String color = step.color != null ? step.color : "";

            ActiveNotifier activeNotifier = null;

            Map<Descriptor<Publisher>, Publisher> map = project.getPublishersList().toMap();
            for (Publisher publisher : map.values()) {
                if (publisher instanceof MattermostNotifier) {
                    activeNotifier = new ActiveNotifier((MattermostNotifier) publisher, (BuildListener)listener);
                }
            }
            activeNotifier.completed(build);
//            //placing in console log to simplify testing of retrieving values from global config or from step field; also used for tests
//            listener.getLogger().printf("Mattermost Send Pipeline step configured values from global config - connector: %s, icon: %s, channel: %s, color: %s", step.endpoint == null, step.icon == null, step.channel == null, step.color == null);
//
//            MattermostService slackService = getMattermostService(team, channel, icon);
//            boolean publishSuccess = slackService.publish(step.message, color);
//            if (!publishSuccess && step.failOnError) {
//                throw new AbortException("Mattermost notification failed. See Jenkins logs for details.");
//            } else if (!publishSuccess) {
//                listener.error("Slack notification failed. See Jenkins logs for details.");
//            }
            return null;
        }

        //streamline unit testing
        MattermostService getMattermostService(String team, String channel, String icon) {
            return new StandardMattermostService(team, channel, icon);
        }

    }

}
