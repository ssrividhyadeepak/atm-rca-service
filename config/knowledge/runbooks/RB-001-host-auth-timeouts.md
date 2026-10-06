# Host authorization timeouts on withdrawals
- Id: RB-001
- Components: withdrawal-*
- Exceptions: HostAuthTimeoutException
- Owner: Bank Platform Engineering

## Signals
Withdrawals are declined with "unable to process". The withdrawal service logs HostAuthTimeoutException, "host authorization timed out", with a SocketTimeoutException underneath. Balance inquiries keep working.

## Triage
1. Compare the timeout in the message ("timed out after Nms") with how long host authorization normally takes, 600 to 1200 ms.
2. If the timeout is lower than that, look for a recent change to the host authorization timeout setting.
3. If the timeout is normal, the authorization gateway is slow: check its latency and recent network changes.

## Mitigation
Roll back the change to the timeout setting, or set it to 3000 ms or more. Withdrawals recover as soon as the setting is live.
