# ADR-0001: OfficeSupport-LLM ⊣ Office Support Governor architecture

## Status

Accepted. `cloud-itonami-isic-8219` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-8219` was scaffolded early as a `:blueprint`-tier
repository -- `README.md`/`docs/business-model.md`/`docs/operator-
guide.md`/`blueprint.edn` were published, but no `deps.edn`/`src`/
`test` ever existed. This ADR records the governed-actor architecture
that fills in that pre-existing blueprint with real, tested code,
following the same langgraph StateGraph + independent Governor +
Phase 0→3 rollout pattern established by `cloud-itonami-isic-6511`
(life insurance) and applied across many prior siblings, most closely
`cloud-itonami-isic-8020` (security systems services), whose four-op
closed-allowlist / propose-only / self-trip-test discipline this build
mirrors closely, substituting the office-support-services domain.

## Scope decision: operations-coordination actor, never data-privacy-compliance authority over client documents

The pre-existing `README.md` `Scope note` already draws a careful
distinction: `cloud-itonami-isic-1812` covers independent pre-press
and post-press/bindery services for the commercial printing industry;
this repository is the SEPARATE business of retail office/business
support services: copy shops, document-typing/preparation services,
and mailing/business-support services, with their own distinct
compliance concerns (unauthorized-practice-of-law / Legal Document
Assistant registration, copyright/fair-use awareness, and customer
data-privacy obligations). This R0 build implements a NARROWER,
explicitly-scoped slice of the pre-existing README's aspirational
business: an office-support-services OPERATIONS COORDINATION actor
with a closed, four-member op allowlist:

- `:log-service-record` -- client-job (document-count/turnaround) data
  logging
- `:schedule-service-operation` -- job/equipment scheduling proposal
- `:flag-confidentiality-concern` -- surfaces a document-
  confidentiality/data-breach concern; ALWAYS escalates
- `:coordinate-supply-order` -- paper/toner/equipment procurement
  coordination

This actor is explicitly NOT the authority that finalizes a
data-privacy-compliance decision over a client's documents, and NOT
the authority that releases or discloses a client's documents -- both
remain a certified office-support operator's own act, never this
actor's. Every proposal it emits carries a literal `:effect :propose`
and an `:action` drawn from a four-member closed allowlist
(`ofsup.governor/allowed-actions`) -- a proposal to directly finalize
a data-privacy-compliance decision or a document-release/disclosure
decision is not merely disallowed by policy, it cannot be represented
in this allowlist at all. Broader capability-library integration
(`kotoba-lang/robotics` missions/telemetry, `kotoba-lang/labor` staff
dispatch, as named in the pre-existing README `Capability layer`
section) remains an explicit follow-up, not attempted in this R0
slice.

## Decision

### Decision 1: TWO independent layers block any data-privacy-compliance-finalization or document-release/disclosure proposal

1. **Structural**: `action-allowlist-violations` hard-blocks any
   `:action` outside the four-member `ofsup.governor/allowed-actions`
   set -- a finalizing action literally cannot be represented.
   `op-allowlist-violations` does the same for `:op`.
2. **Textual**: `scope-exclusion-violations` scans the proposal's own
   rationale/summary for a small set of finalization/execution ACTION
   phrases (see Decision 2) -- catches a proposal that merely NAMES a
   forbidden act in its prose even without a matching `:action`.

Both are HARD, permanent, un-overridable blocks -- a human approver
never even sees them (HOLD never reaches `:request-approval`).

### Decision 2: scope-exclusion terms are phrased as ACTIONS, never bare nouns -- a fleet-wide self-tripping bug class, fixed by construction AND by test

Multiple sibling agents in this fleet have independently discovered
and fixed the SAME bug: a governor's own scope-exclusion term list
phrased as a bare noun (e.g. "release", "disclosure", "compliant")
accidentally matches inside the mock advisor's OWN default rationale/
disclaimer text for a legitimate, allowed proposal -- causing the
actor to self-block on its own happy path. Every disclaimer in
`ofsup.ofsupllm` DENIES having data-privacy-compliance-finalization/
document-release authority ("does not decide any data-privacy-
compliance determination", "does not release any client documents")
using wording deliberately DIFFERENT from the full finalization-action
phrases in `ofsup.governor/scope-exclusion-actions` ("finalize the
data-privacy-compliance determination for these documents", "release
these client documents without client authorization") -- phrased as
the complete action, not a noun a denial sentence would also contain.
`test/ofsup/governor_self_trip_test.clj` is the actual guarantee, not
wording care alone: it runs the default mock advisor's `infer` across
every op and every seeded job (including the registration/open-
concern/already-open/no-spec-basis/high-and-low-cost branches) and
asserts none of the resulting proposals trip
`scope-exclusion-violations`.

### Decision 3: `:effect` is a literal, uniform `:propose` -- a directly-testable structural invariant

`:effect` is ALWAYS the literal keyword `:propose` (asserted by
`effect-not-propose-violations`, HARD/unconditional), and a separate
`:action` key carries the concrete mutation (`:job/log`/`:job/mark-
scheduled`/`:job/flag-confidentiality-concern`/`:job/mark-supply-
coordinated`). This makes "this actor never actuates" a literal,
type-checkable field value rather than an implicit convention.

### Decision 4: "record must be independently verified/registered before ANY action" applies to all three non-registration ops, not only the highest-stakes one

`record-not-verified-violations` gates `:schedule-service-operation`,
`:flag-confidentiality-concern`, AND `:coordinate-supply-order` alike
on the subject job's own `:registered?` fact (set only by a committed
`:log-service-record` with a valid spec-basis citation). This matches
this vertical's own hard invariant text literally ("a client/job
record must be independently verified/registered before any action").

### Decision 5: a differentiated escalation model, NOT "every non-logging write always escalates"

UNLIKE `cloud-itonami-isic-8020` (where every non-logging write op is
unconditionally `high-stakes`, because a security-systems installation
dispatch always carries real-world safety weight), this domain's risk
profile is deliberately more differentiated, matching this repo's own
domain design:

- `:flag-confidentiality-concern` ALWAYS escalates -- TWO independent
  layers agree (`ofsup.governor/high-stakes` includes
  `:office-support/flag-concern` unconditionally, AND
  `ofsup.phase/phases` never includes `:flag-confidentiality-concern`
  in any phase's `:auto` set). Both `ofsup.phase-test` and
  `ofsup.governor-contract-test` assert this independently.
- `:coordinate-supply-order` escalates ONLY when its own estimated
  cost exceeds `ofsup.governor/high-cost-supply-threshold-usd` (a
  routine paper/toner reorder is low-stakes and phase-3 auto-eligible
  when clean; a large equipment/procurement commitment always needs a
  human). A missing/non-numeric cost estimate is treated
  conservatively as high-cost.
- `:log-service-record` and `:schedule-service-operation` carry no
  data-privacy-finalization/document-release weight of their own, so
  neither ever sets a `high-stakes` `:stake` -- both are phase-3
  auto-eligible when the governor is otherwise clean and confidence is
  high, governed by the ordinary confidence-floor gate alone.

### Decision 6: dedicated double-actuation-guard booleans

`:scheduled?`/`:supply-coordination-open?` are dedicated booleans on
the `job` record, never a single `:status` value -- the same
discipline every prior governor's guards establish.

### Decision 7: hand-rolled `enc`/`dec*` EDN-blob codec, not `kotoba-lang/langchain-store`

`kotoba-lang/langchain-store` (ADR-2607141600) is the newer shared
substrate for this codec + identity-schema + entity field-spec
pattern, and is the preferred path for NEW stores. This build instead
mirrors `cloud-itonami-isic-8020`'s own hand-rolled `secsys.store`
exactly (Decision 7 of that repo's own ADR-0001), to minimize
dependency-resolution risk from combining two different `langchain`/
`langchain-clj` coordinate families on one classpath while this
actor's own CI/test path only exercises `-M:test` (no `:dev`
override). Migrating `ofsup.store` to `langchain-store` is a
reasonable, low-risk follow-up once touched again, per this
workspace's own "touched, migrate incrementally" policy -- not
attempted here to keep this R0 build on the most-proven path.

### Decision 8: Store protocol, MemStore + DatomicStore parity

`ofsup.store/Store` is implemented by both `MemStore` (atom-backed,
default for dev/tests/demo) and `DatomicStore` (`langchain.db`-
backed), proven to satisfy the same contract in `test/ofsup/
store_contract_test.clj`.

## Alternatives considered

- **Making `:schedule-service-operation` and `:coordinate-supply-
  order` unconditionally `high-stakes`, mirroring `cloud-itonami-
  isic-8020` exactly.** Rejected: this domain's own design explicitly
  differentiates the escalation model (see Decision 5) -- a
  photocopying/document-preparation job schedule and a routine
  consumables reorder do not carry the real-world dispatch weight a
  security-systems installation does, and forcing every write through
  mandatory human approval would misrepresent that risk profile.
- **A single combined op that both logs and schedules.** Rejected:
  this actor's closed op allowlist is fixed at exactly four members by
  its own domain design; `:log-service-record` does both the patch
  normalization AND the spec-basis citation in one proposal, and
  scheduling is always a separate op.
- **Adopting `kotoba-lang/langchain-store` immediately.** Deferred per
  Decision 7 above -- a reasonable near-term follow-up, not a
  rejection.

## Consequences

- `cloud-itonami-isic-8219` promoted from `:blueprint` to
  `:implemented`, with `:maturity :implemented` added to the
  `kotoba-lang/industry` registry entry (plus de-truncating the
  registry's own `:name` field, a pre-existing ~10% seed-data bug
  unrelated to this build).
- Establishes the closed four-op/four-action allowlist as a literal,
  structurally-enforced (not merely documented) invariant.
- `test/ofsup/governor_self_trip_test.clj` is a dedicated, fleet-
  pattern regression test against the self-tripping scope-exclusion
  bug class -- not just careful wording.
- `MemStore` ‖ `DatomicStore` parity is proven by `test/ofsup/
  store_contract_test.clj`.
- The demo (`clojure -M:dev:run`) walks one clean record-log +
  service-schedule (auto-commit) + low-cost supply-order (auto-commit)
  + high-cost supply-order (escalate/approve/commit, different job) +
  confidentiality-concern-flag (always escalate/approve/commit)
  lifecycle, plus seven HARD-hold scenarios (no-spec-basis,
  unregistered record on two different ops, unconfirmed document-
  preparer registration, an open confidentiality concern on two
  different ops, an already-open supply-order coordination, a
  double-schedule, and a double supply-order coordination), end-to-end.
- `clojure -M:test`: see this superproject's own landing ADR
  (`90-docs/adr/*-cloud-itonami-isic-8219-office-support-coverage.md`)
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output captured at merge time.

## References

- `cloud-itonami-isic-8020/docs/adr/0001-architecture.md` (nearest
  structural sibling; mirrored closely, substituting the office-
  support-services domain for security-systems-services, and
  differentiating the escalation model per Decision 5)
- `kotoba-lang/langchain-store` (ADR-2607141600; deferred adoption,
  see Decision 7)
- This repo's own pre-existing `blueprint.edn`/`README.md`/`docs/
  business-model.md`/`docs/operator-guide.md` (the blueprint this
  build fills in)
- superproject `com-junkawasaki/root` ADR recording this promotion
  (`90-docs/adr/*-cloud-itonami-isic-8219-office-support-coverage.md`/
  `.edn`)
