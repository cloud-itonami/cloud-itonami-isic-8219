# Governance

`cloud-itonami-8219` is an OSS open-business blueprint for community
office support services operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Office Support Governor remains independent of the advisor.
- hard policy violations (an out-of-scope document-preparation job,
  an unverified job release, an unverified quality record) cannot be
  overridden by human approval.
- every dispatch, sign-off and follow-up path is auditable.
- sensitive customer document data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or preparation-scope checks
- mishandling customer document data
- misrepresenting certification status
- failing to respond to safety incidents
