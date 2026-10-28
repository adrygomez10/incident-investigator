package dev.adriangm.investigator.application;

/**
 * Application failure code with an optional provider retry delay; no SDK-specific exception
 * escapes.
 */
public final class AgentFailure extends RuntimeException {
    /** Stable application failure code retained in the investigation outcome. */
    public final String code;

    /** Provider delay before a later execution may retry, or zero when unspecified. */
    public final long retryAfterSeconds;

    /** Records a stable failure code and an optional delay before a later execution may retry. */
    public AgentFailure(String code)
    {
        this(code, 0);
    }

    /** Records a stable failure code and an optional delay before a later execution may retry. */
    public AgentFailure(String code, long retryAfterSeconds)
    {
        super(code);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
