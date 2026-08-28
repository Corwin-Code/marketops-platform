package com.mimococo.marketops.marketplaceintegration.internal.application;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A name for the process holding a lease.
 *
 * <p>The name has to survive being read by a person during an incident. A
 * random identifier tells an operator that some worker holds a command; a host
 * and process together tell them which machine to look at.
 *
 * <p>It is computed once. A lease owner that changed between the claim and the
 * transition would fail the fence check every time, so this must be stable for
 * the life of the process.
 */
final class WorkerIdentity {

    /** Longest owner name the lease column will carry comfortably. */
    private static final int MAXIMUM_LENGTH = 100;

    private static final String NAME = compute();

    private WorkerIdentity() {
    }

    /** This process's stable lease-owner name. */
    static String current() {
        return NAME;
    }

    private static String compute() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unresolvable) {
            // A host that cannot name itself is still a host worth telling
            // apart from the others, so the process identifier stands alone.
            host = "unknown-host";
        }
        String name = host + '/' + ProcessHandle.current().pid();
        return name.length() > MAXIMUM_LENGTH ? name.substring(0, MAXIMUM_LENGTH) : name;
    }
}
