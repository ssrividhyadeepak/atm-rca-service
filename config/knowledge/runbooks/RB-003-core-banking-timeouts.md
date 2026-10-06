# Balance inquiries time out calling core banking
- Id: RB-003
- Components: balance-*
- Exceptions: CoreBankingTimeoutException
- Owner: Core Banking Integration

## Signals
The balance service logs CoreBankingTimeoutException, "core banking balance call timed out", or falls back and logs "In downstreamBalanceFallback". Customers see a stale balance or an error.

## Triage
1. Check core banking response times for the period.
2. Check whether a batch job or an index rebuild was running on core banking at that time.

## Mitigation
A short burst clears on its own. If it lasts, ask the core banking team to pause the batch job. The fallback serves the last known balance in the meantime.
