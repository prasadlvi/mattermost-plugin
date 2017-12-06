package jenkins.plugins.mattermost;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.scm.EditType;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.util.LogTaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static org.json.JSONObject.quote;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

   private static final Logger logger = Logger.getLogger(MattermostListener.class.getName());

   private MattermostNotifier notifier;
   private BuildListener listener;

   ActiveNotifier(MattermostNotifier notifier, BuildListener listener) {
      super();
      this.notifier = notifier;
      this.listener = listener;
   }

   private MattermostService getMattermost(AbstractBuild r) {
      return notifier.newMattermostService(r, listener);
   }

   public void deleted(AbstractBuild r) {
   }

   public void started(AbstractBuild build) {

//      CauseAction causeAction = build.getAction(CauseAction.class);
//
//      if (causeAction != null) {
//         Cause scmCause = causeAction.findCause(SCMTrigger.SCMTriggerCause.class);
//         if (scmCause == null) {
//            MessageBuilder message = new MessageBuilder(notifier, build)
//                    .appendProjectAsAuthor()
//                    .appendCommitsAsText()
//                    .appendChanges();
//
//            notifyStart(build, message.toString());
//            // Cause was found, exit early to prevent double-message
//            return;
//         }
//      }

      MessageBuilder message = new MessageBuilder(notifier, build)
              .appendProjectAsAuthor()
              .appendCommitsAsText()
              .appendChanges();
      notifyStart(build, message);
   }

   private void notifyStart(AbstractBuild build, MessageBuilder message) {
      AbstractProject<?, ?> project = (build != null) ? build.getProject() : null;
      AbstractBuild<?, ?> previousBuild = (project != null && project.getLastBuild() != null) ? project.getLastBuild().getPreviousCompletedBuild() : null;
      if (previousBuild == null) {
         getMattermost(build).publish(message.getMattermostJSON(), "good");
      } else {
         getMattermost(build).publish(message.getMattermostJSON(), getBuildColor(previousBuild));
      }
   }

   public void finalized(AbstractBuild r) {
   }

   public void completed(AbstractBuild build) {
      AbstractProject<?, ?> project = build.getProject();
      Result result = build.getResult();
      AbstractBuild<?, ?> previousBuild = project.getLastBuild();

      if (previousBuild != null) {
         do {
            previousBuild = previousBuild.getPreviousCompletedBuild();
         } while (previousBuild != null && previousBuild.getResult() == Result.ABORTED);
      }

      Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
      boolean shouldPublish = false;
      if (result == Result.ABORTED && notifier.getNotifyAborted()) {
         shouldPublish = true;
      } else if (result == Result.FAILURE //notify only on single failed build
              && previousResult != Result.FAILURE
              && notifier.getNotifyFailure()) {
         shouldPublish = true;
      } else if (result == Result.FAILURE //notify only on repeated failures
              && previousResult == Result.FAILURE
              && notifier.getNotifyRepeatedFailure()) {
         shouldPublish = true;
      } else if (result == Result.NOT_BUILT && notifier.getNotifyNotBuilt()) {
         shouldPublish = true;
      } else if (result == Result.SUCCESS
              && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
              && notifier.getNotifyBackToNormal()) {
         shouldPublish = true;
      } else if (result == Result.SUCCESS && notifier.getNotifySuccess()) {
         shouldPublish = true;
      } else if (result == Result.UNSTABLE && notifier.getNotifyUnstable()) {
         shouldPublish = true;
      }

      if (shouldPublish) {
         getMattermost(build).publish(getBuildStatusJSON(build), getBuildColor(build));
      }
   }

   private JSONObject getBuildStatusJSON(AbstractBuild build) {
      MessageBuilder message = new MessageBuilder(notifier, build)
              .appendProjectAsAuthor()
              .appendCommitsAsText()
              .appendStatus()
              .appendDuration()
              .appendChanges()
              .appendTestSummary();
      return message.getMattermostJSON();
   }

   private String getChanges(AbstractBuild build, boolean includeCustomMessage) {
      if (!build.hasChangeSetComputed()) {
         logger.info("No change set computed...");
         return null;
      }

      ChangeLogSet changeSet = build.getChangeSet();
      List<Entry> entries = new LinkedList<>();
      Set<AffectedFile> files = new HashSet<>();
      for (Object o : changeSet.getItems()) {
         Entry entry = (Entry) o;
         logger.info("Entry " + o);
         entries.add(entry);
         files.addAll(entry.getAffectedFiles());
      }

      if (entries.isEmpty()) {
         logger.info("Empty change...");
         return null;
      }

      Set<String> authors = new HashSet<>();
      for (Entry entry : entries) {
         authors.add(entry.getAuthor().getDisplayName());
      }

      StringBuilder message = new StringBuilder();
      message.append(":checkered_flag: Started by changes from ");
      message.append(StringUtils.join(authors, ", "));
      message.append(" (");
      message.append(files.size());
      message.append((files.size() == 1) ? " file" : " files").append(" changed):  \n");

      int fileCount = 0;
      outer: for (Entry entry : entries) {
         for (AffectedFile file : entry.getAffectedFiles()) {
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

      if (includeCustomMessage) {
         message.append(getCustomMessage(build));
      }

      return message.toString();
   }

   private String getCustomMessage(AbstractBuild build) {
      String customMessage = notifier.getCustomMessage();

      EnvVars envVars = new EnvVars();
      try {
         envVars = build.getEnvironment(new LogTaskListener(logger, INFO));
      } catch (IOException | InterruptedException e) {
         logger.log(SEVERE, e.getMessage(), e);
      }

      return "\n" + envVars.expand(customMessage);
   }

   private String getCommitList(AbstractBuild build) {
      ChangeLogSet changeSet = build.getChangeSet();
      List<Entry> entries = new LinkedList<>();
      for (Object o : changeSet.getItems()) {
         Entry entry = (Entry) o;
         logger.info("Entry " + o);
         entries.add(entry);
      }

      if (entries.isEmpty()) {
         logger.info("Empty change...");
         Cause.UpstreamCause c = (Cause.UpstreamCause) build.getCause(Cause.UpstreamCause.class);
         if (c == null) {
            return "No Changes.";
         }

         String upProjectName = c.getUpstreamProject();
         int buildNumber = c.getUpstreamBuild();
         AbstractProject project = Jenkins.getInstance().getItemByFullName(upProjectName, AbstractProject.class);
         if (project == null) {
            return "No upstream project.";
         }

         AbstractBuild upBuild = project.getBuildByNumber(buildNumber);
         return getCommitList(upBuild);
      }

      Set<String> commits = new HashSet<>();
      for (Entry entry : entries) {
         StringBuilder commit = new StringBuilder();
         CommitInfoChoice commitInfoChoice = notifier.getCommitInfoChoice();
         if (commitInfoChoice.showTitle()) {
            // String link = entry.getMsgAnnotated().replaceFirst(".+<a href='(.+?)'>.+", "($1)");
            // commit.append('[').append(entry.getMsg()).append(']').append(link);
            commit.append(entry.getMsg());
         }
         if (commitInfoChoice.showAuthor()) {
            commit.append(" [").append(entry.getAuthor().getDisplayName()).append("]");
         }
         commits.add(commit.toString());
      }

      return "- " + StringUtils.join(commits, "\n- ");
   }

   private static String getBuildColor(AbstractBuild r) {
      Result result = r.getResult();
      if (result == Result.SUCCESS) {
         return "good";
      } else if (result == Result.FAILURE) {
         return "danger";
      } else {
         return "warning";
      }
   }

   public class MessageBuilder {

      private static final String STARTING_STATUS_MESSAGE = ":pray: Starting...",
              BACK_TO_NORMAL_STATUS_MESSAGE = ":white_check_mark: Back to normal",
              STILL_FAILING_STATUS_MESSAGE = ":no_entry_sign: Still Failing",
              SUCCESS_STATUS_MESSAGE = ":white_check_mark: Success",
              FAILURE_STATUS_MESSAGE = ":no_entry_sign: Failure",
              ABORTED_STATUS_MESSAGE = ":warning: Aborted",
              NOT_BUILT_STATUS_MESSAGE = ":warning: Not built",
              UNSTABLE_STATUS_MESSAGE = ":warning: Unstable",
              UNKNOWN_STATUS_MESSAGE = ":question: Unknown";

      private final MattermostNotifier notifier;
      private final AbstractBuild build;
      private final JSONObject attachment;
      private final JSONArray fields;
      private final JSONObject json;

      MessageBuilder(MattermostNotifier notifier, AbstractBuild build) {
         this.notifier = notifier;
         this.build = build;

         json = new JSONObject();

         JSONArray attachments = new JSONArray();
         json.put("attachments", attachments);

         attachment = new JSONObject();
         attachments.put(attachment);

         fields = new JSONArray();
         attachment.put("fields", fields);

//         try {
//            FilePath logo = build.getWorkspace().child("logo.png");
//            if (logo.exists()) {
//               String url = (notifier.getBuildServerUrl() + "job/" + build.getProject().getName() + "/ws/" + logo.getName());
//               logger.info("thumb_url=" + url);
//               attachment.put("thumb_url", url);
//            }
//         } catch (IOException | InterruptedException e) {
//            // nothing
//         }
      }

      JSONObject getMattermostJSON() {
         return json;
      }

      MessageBuilder appendProjectAsAuthor() {
         String authorName = build.getProject().getFullDisplayName() + " " + build.getDisplayName();
         attachment.put("author_name", authorName);
         attachment.put("author_link", notifier.getBuildServerUrl() + build.getUrl());

         return this;
      }

      MessageBuilder appendStatus() {
         JSONObject field = new JSONObject();
         field.put("short", true);
         field.put("title", "Status");
         field.put("value", getStatusMessage());

         fields.put(field);
         return this;
      }

      MessageBuilder appendDuration() {
         JSONObject field = new JSONObject();
         field.put("short", true);
         field.put("title", "Duration");

         String durationString;
         if (getStatusMessage().equals(BACK_TO_NORMAL_STATUS_MESSAGE)) {
            durationString = createBackToNormalDurationString();
         } else {
            durationString = build.getDurationString();
         }

         field.put("value", durationString);

         fields.put(field);
         return this;
      }

      MessageBuilder appendCommitsAsText() {
         if (!notifier.getCommitInfoChoice().showAnything())
            return this;

         attachment.put("text", getCommitList(build));

         return this;
      }

      MessageBuilder appendChanges() {
         String changes = getChanges(build, notifier.includeCustomMessage());
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

      MessageBuilder appendTestSummary() {
         if (!notifier.includeTestSummary()) {
            return this;
         }

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
         long buildDuration = build.getDuration();
         long buildEndTime = buildStartTime + buildDuration;
         long backToNormalDuration = buildEndTime - previousSuccessEndTime;
         return Util.getTimeSpanString(backToNormalDuration);
      }

      String escape(String string) {
         string = string.replace("&", "&amp;");
         string = string.replace("<", "&lt;");
         string = string.replace(">", "&gt;");
         string = string.replace("#", "\\#");

         return quote(string);
      }

      private String getStatusMessage() {
         if (build.isBuilding()) {
            return STARTING_STATUS_MESSAGE;
         }
         Result result = build.getResult();
         Result previousResult;
         Run lastBuild = build.getProject().getLastBuild();
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
   }
}
