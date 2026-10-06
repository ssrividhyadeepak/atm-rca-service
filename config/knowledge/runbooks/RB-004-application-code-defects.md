# An application code defect is failing requests
- Id: RB-004
- Components: *
- Exceptions: NullPointerException, IllegalStateException, ClassCastException, IndexOutOfBoundsException, NumberFormatException
- Owner: The team that owns the failing component

## Signals
An exception the code itself throws, such as NullPointerException, at the same line every time, spread across the day rather than in one burst. It usually starts after a release.

## Triage
1. Open the failing line named in the stack trace and the commit that last changed it.
2. Work out which requests hit it: it is often one kind of request that the change did not consider.

## Mitigation
Roll back the release that introduced the change if the failing requests matter, otherwise fix forward. Add a test for the kind of request that failed.
