package digital.rya.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The one call the agent needs from Claude: POST /v1/messages. An interface so tests can
 * plug in a scripted fake and run the whole loop without network or API cost.
 */
public interface MessagesApi {

    /** @throws ClaudeApiException if the API call fails after retries */
    JsonNode createMessage(ObjectNode request);

    class ClaudeApiException extends RuntimeException {
        public ClaudeApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
