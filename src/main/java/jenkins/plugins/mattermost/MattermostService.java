package jenkins.plugins.mattermost;

import org.json.JSONObject;

public interface MattermostService {
	boolean publish(String message);

	boolean publish(String message, String color);

	boolean publish(JSONObject json, String color);
}
