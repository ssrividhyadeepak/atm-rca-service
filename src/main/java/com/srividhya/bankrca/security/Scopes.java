package com.srividhya.bankrca.security;

import java.util.List;

/** What a token may do. A client is given only the scopes it needs. */
public final class Scopes {

    /** RCA reports, monitoring runs, correlation; the getFailureSummary and getFinding tools. */
    public static final String RCA_READ = "rca:read";
    /** Start a monitoring run or an analysis, replay an input, reload the knowledge base. */
    public static final String RCA_WRITE = "rca:write";
    /** The failure events themselves: masked log content. */
    public static final String LOGS_READ = "logs:read";
    /** Runbooks and past RCAs; the lookupRunbook and searchHistoricalRca tools. */
    public static final String KB_READ = "kb:read";
    /** Source code lookups. */
    public static final String CODE_READ = "code:read";

    /** See incident drafts and what became of them. */
    public static final String INCIDENT_READ = "incident:read";
    /** Draft an incident from a finding; the draftIncident tool. Sends nothing. */
    public static final String INCIDENT_WRITE = "incident:write";
    /** Approve or reject a draft. Approval is what creates the incident: give this to people, not to assistants. */
    public static final String INCIDENT_APPROVE = "incident:approve";

    public static final List<String> ALL = List.of(RCA_READ, RCA_WRITE, LOGS_READ, KB_READ, CODE_READ, INCIDENT_READ,
            INCIDENT_WRITE, INCIDENT_APPROVE);
    /** What a dev token gets unless more is asked for. */
    public static final String READ_ONLY = RCA_READ + " " + LOGS_READ + " " + KB_READ + " " + CODE_READ + " "
            + INCIDENT_READ;

    private Scopes() {
    }
}
