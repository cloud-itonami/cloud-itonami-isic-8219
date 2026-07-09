# cloud-itonami-8219

Open Business Blueprint for **ISIC Rev.5 8219**: photocopying,
document preparation and other specialized office support activities
(retail copy/print shops, document-typing and preparation services,
mailing-list and business-support services).

This repository designs a forkable OSS business for community office
support services: document-handling and preparation-scope
management, robotics-assisted photocopying/printing/finishing and job
staging, and job/quality records — run by a qualified operator so a
copy shop or document-preparation service keeps its own compliance
and job history instead of renting a closed office-support platform.

## Scope note: office/business support, not industrial print bindery

`cloud-itonami-isic-1812` ("Community Print Support Services
Operations") covers independent pre-press and post-press/bindery
services for the commercial printing industry (plate-making,
industrial cutting/folding/binding equipment serving print shops).
This repository is deliberately scoped to the SEPARATE business of
retail office/business support services: copy shops, document-typing
and formatting services, and mailing/business-support services for
individual and small-business customers. Document-preparation
services carry their own distinct compliance concerns in many
jurisdictions: unauthorized-practice-of-law statutes restrict
document preparers from giving legal advice (several US states, e.g.
California, require registration as a Legal Document Assistant for
fee-based legal-document preparation); copyright compliance and fair-
use awareness apply when photocopying published works; and data-
privacy obligations apply to the personal and financial documents
customers bring in to be copied or prepared.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a
**robot performs the physical domain work**. Here robots
(photocopying/printing/finishing equipment operation, job staging and
retrieval) operate under an actor that proposes actions and an
independent **Office Support Governor** that gates them. The governor
never releases a prepared document or dispatches a job outside its
verified scope itself; `:high`/`:safety-critical` actions (a document-
preparation job outside verified registration/scope, a job release
without a completed accuracy check, a quality record without
verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + document-handling/preparation scope + job registration
        |
        v
Office Support Advisor -> Office Support Governor -> match, dispatch, follow-up record, or human approval
        |
        v
robot actions (gated) + job record + audit ledger
```

No automated advice can release a prepared document the governor
refuses, match an unregistered preparer to an out-of-scope job, or
publish a follow-up record without governor approval and audit
evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `8219`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — staff registration, dispatch, timesheet/follow-up contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
