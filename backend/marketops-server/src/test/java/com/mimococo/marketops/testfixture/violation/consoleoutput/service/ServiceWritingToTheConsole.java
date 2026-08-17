package com.mimococo.marketops.testfixture.violation.consoleoutput.service;

/**
 * A class that reports through the console.
 *
 * <p>This is the arrangement the diagnostics rule exists to reject: the output
 * carries no level, no time and no correlation identifier, so it can be neither
 * filtered nor tied back to the request that produced it.
 */
public final class ServiceWritingToTheConsole {

    /** Report progress where no logging configuration can reach it. */
    public void report(String message) {
        System.out.println(message);
    }

    /** Report a failure where no logging configuration can reach it. */
    public void report(Exception failure) {
        failure.printStackTrace();
    }
}
