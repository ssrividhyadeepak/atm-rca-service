package com.srividhya.bankrca.rca;

/**
 * Turns the grouped failures of one window into findings: what each problem most likely is,
 * how urgent it is, and what to do next. Implementations: rule-based today; an LLM-backed one
 * can be added behind the same interface and compared on the same input.
 */
public interface RcaAnalyzer {

    /** Name and version of the implementation, recorded on every report. */
    String name();

    RcaReport analyze(RcaInput input);
}
