package com.example.urlshortener.threat;

import java.net.URI;

/**
 * The checker used when {@code app.threat.enabled} is false.
 *
 * <p>It answers {@link ThreatVerdict#UNAVAILABLE} rather than
 * {@link ThreatVerdict#ALLOW}: "checking is switched off" is the same fact as
 * "the checker could not answer", and reporting it as a clean result would hide
 * a disabled control behind a verdict that looks like a decision. Whether that
 * means accept or refuse is {@code app.threat.fail-open}'s job, and either way it
 * is logged.
 */
public class NoOpThreatCheck implements ThreatCheck {

    @Override
    public ThreatVerdict check(URI url) {
        return ThreatVerdict.UNAVAILABLE;
    }
}
