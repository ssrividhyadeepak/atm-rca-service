# The machine cannot dispense the requested amount
- Id: RB-005
- Components: withdrawal-*
- Exceptions: InsufficientCassetteException
- Owner: Field Services

## Signals
The withdrawal service logs InsufficientCassetteException, "cassette N cannot dispense amount". The customer is asked to choose another amount.

## Triage
1. Check whether one bank id produces most of them.

## Mitigation
Normal when a cassette runs low. If one machine stands out, raise a replenishment request for it.
