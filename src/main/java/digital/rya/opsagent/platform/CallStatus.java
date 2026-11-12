package digital.rya.opsagent.platform;

/** Final outcome of a call, as recorded by the platform. */
public enum CallStatus {
    CONNECTED_PAYABLE,
    CONNECTED_SHORT,
    NO_ANSWER,
    BUYER_HANGUP,
    NOT_ROUTED_CAP,
    NOT_ROUTED_NO_BUYER,
    DUPLICATE;

    /** The call reached a buyer and a human talked (billable or not). */
    public boolean isConnected() {
        return this == CONNECTED_PAYABLE || this == CONNECTED_SHORT;
    }

    /** The call was sent to a buyer but the buyer did not take it. */
    public boolean isBuyerFailure() {
        return this == NO_ANSWER || this == BUYER_HANGUP;
    }

    /** The platform could not find a buyer for the call. */
    public boolean isNotRouted() {
        return this == NOT_ROUTED_CAP || this == NOT_ROUTED_NO_BUYER;
    }
}
