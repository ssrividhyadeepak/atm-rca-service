# The screen reports a customer setup error
- Id: RB-006
- Components: ui-base-*
- Exceptions:
- Owner: Channels UI

## Signals
The UI base service logs a line starting "UI MOD" with "CustomerCommSetup: Exception - undefined" and a customer tracking session id. There is no Java exception.

## Triage
1. Look up the tracking session to see which screen step failed.
2. Check whether the customer profile service was failing at the same time.

## Mitigation
Usually a symptom of a failure further down. Fix that first; the screen errors stop with it.
