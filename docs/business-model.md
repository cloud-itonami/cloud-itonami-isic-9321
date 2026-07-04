# Business Model: Activities of amusement parks and theme parks

## Classification

- Repository: `cloud-itonami-isic-9321`
- ISIC Rev.5: `9321`
- Activity: activities of amusement parks and theme parks -- ride operation and park attraction management
- Social impact: cultural/recreational access, data sovereignty, transparent audit

## Customer

- independent amusement/theme parks
- cooperative fairground operators
- community festival/carnival programs

## Offer

- ticketing/admission intake
- ride-schedule/maintenance proposal
- ride-reopening-after-hold proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per park
- support: monthly retainer with SLA
- migration: import from an incumbent park-operations system
- per-admission fee

## Trust Controls

- no ride reopens after a safety hold without human sign-off (a licensed ride operator/inspector)
- a fabricated inspection record forces a hold, not an override
- every reopening path is auditable
- emergency manual override paths remain outside LLM control
