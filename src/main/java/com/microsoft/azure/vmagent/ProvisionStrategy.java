package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.Constants;
import org.kohsuke.stapler.DataBoundConstructor;

public class ProvisionStrategy {
    private static final long INIT_INTERVAL = 10 * 1000; // 10 seconds

    private static final long MAX_INTERVAL = 20 * 60 * 1000; // 20 minutes

    private long interval;

    private long lastFailureTime;

    private String configurationStatus;

    @DataBoundConstructor // needed by jcasc
    public ProvisionStrategy() {
        this.interval = INIT_INTERVAL;
        this.configurationStatus = Constants.UNVERIFIED;
        this.lastFailureTime = 0;
    }

    public boolean isVerifiedPass() {
        return configurationStatus.equals(Constants.VERIFIED_PASS);
    }

    public boolean isVerifiedFailed() {
        return configurationStatus.equals(Constants.VERIFIED_FAILED);
    }

    // Whatever verify failed or deploy failed, extend retry interval
    public synchronized void failure() {
        configurationStatus = Constants.VERIFIED_FAILED;
        interval = Math.min(interval * 2, MAX_INTERVAL);
        lastFailureTime = System.currentTimeMillis();
    }

    // If deploy succeed, clean retry interval
    public synchronized void success() {
        configurationStatus = Constants.VERIFIED_PASS;
        interval = INIT_INTERVAL;
        lastFailureTime = 0;
    }

    // If only verify succeed, don't reset retry interval
    // So, if verify passed but deploy failed, we can also accumulate retry interval.
    public synchronized void verifiedPass() {
        configurationStatus = Constants.VERIFIED_PASS;
    }

    // If enabled, it means the template can go to the provision logic
    // If lastFailureTime == 0, it always return true.
    public synchronized boolean isEnabled() {
        return lastFailureTime + interval <= System.currentTimeMillis();
    }

}
