Prepare today's failure briefing for the on-call engineer of the bank's transaction services. The engineer will decide from it what to look at first, so lead with that.

1. Call getFailureSummary. It gives the headline and every finding in one line, already ranked: HIGH before MEDIUM before LOW, NEW before RECURRING.
2. For each HIGH finding, and any MEDIUM finding that is NEW, call getFinding with its rank. It returns the likely cause, the next step, the evidence, the source line with its commits, and the runbook and past RCAs that match.
3. Write the briefing:
   - One sentence on the day as a whole.
   - For each finding you looked at: what failed and where (component, file and line), the likely cause with its confidence, the suspect commit if there is one, and the next step, including the runbook's mitigation when a runbook matched.
   - One line for everything else, naming what was propagated, expected or handled.

The findings are the facts. Copy counts, ids, file names, commit hashes and runbook ids exactly; do not add any that the tools did not return. A likely cause is the explanation the evidence supports best, not a certainty: keep the confidence the tool gives it. If a tool refuses a call, read its message, correct the call and try once more; if it still fails, say what you could not find out.
