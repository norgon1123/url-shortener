package com.example.urlshortener.threat;

/**
 * The answer a {@link ThreatCheck} gives about a target URL.
 *
 * <p>Three values rather than a boolean, because "I could not tell" is a
 * different fact from "it is clean" and the create path must be able to log the
 * difference. Collapsing them into {@code false} is how a fail-open decision
 * becomes invisible in production.
 */
public enum ThreatVerdict {

    /** Checked, nothing known against it. */
    ALLOW,

    /** Known phishing or malware: the link is never created (AC21). */
    BLOCK,

    /**
     * The checker could not answer - store down, timeout, disabled.
     *
     * <p>Treated as {@link #ALLOW} when {@code app.threat.fail-open} is true,
     * which is the default and follows AC20's preference for availability, and
     * logged at WARN every time so the decision is auditable rather than assumed.
     * Setting fail-open to false turns this into a 503 on create; the click path
     * is unaffected either way.
     */
    UNAVAILABLE
}
