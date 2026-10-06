# Deposit credits fail to post to the ledger
- Id: RB-002
- Components: deposit-*
- Exceptions: LedgerPostingException
- Owner: Deposits Engineering

## Signals
The deposit service logs LedgerPostingException, "ledger posting failed status=503". The gateway in front reports the same requests as upstream errors. Customers get their receipt later than usual.

## Triage
1. Check whether the failures fall inside the ledger's nightly maintenance window.
2. Outside the window, check the ledger service's health and its error rate.

## Mitigation
Nothing to do inside the maintenance window: the deposit is kept and the reconciliation job posts the credit afterwards. Outside it, page the ledger team.
