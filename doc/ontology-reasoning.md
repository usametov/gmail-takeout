# Ontology Reasoning: RDFS + OWL 2 RL subset

Status: **design**, companion to [Antigravity Port Intent](antigravity-port-intent.md).
This document defines the reasoning formalism we will implement in Datalevin.

## Position

We will implement a **typed property graph** with **RDFS-core entailment**
plus a **selected OWL 2 RL rule set**. We use closed-world integrity
constraints for required fields and category exclusivity, because the pipeline
must fail on an incomplete or contradictory ontology rather than infer around it.

We do **not** target full OWL-Lite or OWL DL. OWL-Lite was the lightweight
profile of OWL 1, but OWL 2 RL is the modern rule-friendly profile; it is the
right reference for a Datalog implementation. Full OWL DL needs a tableau or
consequence-based reasoner and is out of scope.

References:

- RDF Semantics: <https://www.w3.org/TR/rdf-mt/>
- OWL 2 Profiles (RL): <https://www.w3.org/TR/owl2-profiles/#OWL_2_RL>
- SKOS Reference: <https://www.w3.org/TR/skos-reference/>

## Representation decision

### Primary store: typed graph, not generic triples

The generic RDF representation is `triple(S, P, O)`. We will keep an equivalent
**typed graph** for primary storage because Datalevin refs, cardinality, and
attribute-specific queries are much more ergonomic and safer than a generic
object column that may be an entity reference or a literal.

| Graph attribute | RDF predicate | Notes |
|---|---|---|
| `:class/parent` | `rdfs:subClassOf` | transitive |
| `:term/category` | `rdf:type` | asserted leaf category; cardinality one |
| `:term/same-as` | `owl:sameAs` | symmetric + transitive closure |
| `:term/broader` | `skos:broader` | transitive |
| `:term/narrower` | `skos:narrower` | inverse of broader |
| `:term/related` | `skos:related` | symmetric |
| `:form/term` | `ex:canonicalForm` | maps a raw form to its term |
| `:label/parent` | `skos:broader` (label namespace) | label taxonomy |
| `:term/normalized` | `skos:prefLabel` | canonical spelling |
| `:class/disjoint-with` | `owl:disjointWith` | integrity constraint |

The rule set below is expressed over these attributes. A generic `triple/3`
view can be generated for RDF/Turtle export later; it is not the primary store.

### Asserted vs inferred

- **Asserted facts** are written by the classifier/seed/operator.
- **Inferred facts** are produced by Datalog rules (subclass closure, type
  inheritance, sameAs closure, broader/narrower/related closure).
- Inferred facts are never edited by hand.
- Start with **query-time reasoning** for the small ontology. If profiling shows
  a need, materialize the closure into an `:inferred/*` namespace (with
  provenance and rebuild semantics), never into the asserted attributes.

## Rule set

### RDFS core

| Rule | Datalog shape |
|---|---|
| subclass transitivity | `subclass(?c,?p) :- class/parent(?c,?p)`, `subclass(?c,?p) :- class/parent(?c,?x), subclass(?x,?p)` |
| type inheritance | `term-type(?t,?c) :- term/category(?t,?c)`, `term-type(?t,?anc) :- term-type(?t,?c), subclass(?c,?anc)` |
| subproperty transitivity | `subprop(?p,?r) :- subPropertyOf(?p,?q), subprop(?q,?r)` |
| property inheritance | `has(?s,?q,?o) :- subprop(?p,?q), has(?s,?p,?o)` |
| domain typing | `type(?s,?c) :- domain(?p,?c), has(?s,?p,?o)` |
| range typing | `type(?o,?c) :- range(?p,?c), has(?s,?p,?o)` |
| equivalentClass | `type(?x,?d) :- equivalentClass(?c,?d), type(?x,?c)` |
| equivalentProperty | `has(?s,?q,?o) :- equivalentProperty(?p,?q), has(?s,?p,?o)` |

In the typed graph, domain/range and functional constraints are primarily
**validation** rules, because malformed facts should fail the pipeline rather
than be silently retyped. Type inheritance and subclass closure are positive
inferences.

### OWL 2 RL / SKOS-style extensions

We implement the subset that matches the domain model:

| Rule | Purpose |
|---|---|
| `sameAs` symmetry + transitivity + type/label propagation | canonical term equivalence |
| `inverseOf` | `broader` ↔ `narrower` |
| `SymmetricProperty` | `related` both directions |
| `TransitiveProperty` | `broader`, `related-to` where appropriate |
| functional property | one normalized form/category per term; validation |
| inverse-functional property | one term per raw form; validation |
| disjoint classes | category exclusivity; integrity check |
| class hierarchy disjointness | inherited disjointness checks |
| label `broader` closure | label taxonomy ancestors |

We do **not** initially implement `someValuesFrom`, `allValuesFrom`, cardinality
restrictions, property chains, Boolean class expressions, or full equality
reasoning. Those are OWL DL/OWL 2 RL features that the email ontology does not
need yet.

Example typed-graph rules (EDN vectors, passed to Datalevin as `%`):

```clojure
[[(subclass ?c ?p) [?c :class/parent ?p]]
 [(subclass ?c ?p) [?c :class/parent ?x] (subclass ?x ?p)]

 [(term-type ?t ?c) [?t :term/category ?c]]
 [(term-type ?t ?anc) (term-type ?t ?c) (subclass ?c ?anc)]

 [(same ?a ?b) [?a :term/same-as ?b]]
 [(same ?b ?a) (same ?a ?b)]
 [(same ?a ?c) (same ?a ?b) (same ?b ?c)]

 [(broader ?a ?c) [?a :term/broader ?c]]
 [(broader ?a ?c) [?a :term/broader ?x] (broader ?x ?c)]
 [(narrower ?c ?a) (broader ?a ?c)]
 [(related ?b ?a) [?a :term/related ?b]]

 [(label-ancestor ?l ?p) [?l :label/parent ?p]]
 [(label-ancestor ?l ?p) [?l :label/parent ?x] (label-ancestor ?x ?p)]]
```

Datalevin supports recursive Datalog rules with semi-naive evaluation. We
verified the subclass/ancestor recursion; `sameAs` closure needs explicit tests
for cycles and should be bounded to the ontology-sized term set.

## Closed-world enforcement

RDFS/OWL is open-world: a missing fact is unknown, not false. Our pipeline
needs the opposite for required fields:

- Every term must have exactly one asserted category.
- Every category must be in the configured enum.
- Terms in disjoint classes are errors.
- Every normalized form maps to one category.
- Every allow-listed term must resolve.
- Every raw form must map to a term.
- The JSON cache must agree with the graph.

These are **integrity constraints**, not OWL entailments. Datalog derives
violations; the pipeline aborts before commit/export if any exist.

Example validation queries:

```clojure
;; terms with no category
[:find ?t :where [?t :term/id _] (not [?t :term/category _])]

;; normalized forms mapping to multiple categories
[:find ?n (count-distinct ?c)
 :where [?t :term/normalized ?n] [?t :term/category ?c]
 :having [(> (count-distinct ?c) 1)]]

;; terms in disjoint categories
[:find ?t ?a ?b
 :where [?t :term/category ?a]
        [?t :term/category ?b]
        [(not= ?a ?b)]
        [?ca :class/id ?a]
        [?cb :class/id ?b]
        [?ca :class/disjoint-with ?cb]]
```

Exact query shapes will be finalized against Datalevin during Phase 1.

## Inference and cache export

- The graph stores asserted facts.
- Effective category / type hierarchy / broader closure are computed with rules.
- The JSON cache is generated from the graph:
  - exact key + lowercase key
  - value `{:category <effective category> :normalized <canonical>}`
  - category must be one of the configured enum (asserted leaf, not an
    organizational superclass)
- Local matching reads the cache only.
- `--check` validates graph, cache, and graph↔cache agreement.

## What is explicitly out of scope

- Full OWL DL / OWL-Lite as a conformance target
- Open-world reasoning for missing category/type facts
- Property chains
- `someValuesFrom` / `allValuesFrom`
- Cardinality restrictions beyond functional/inverse-functional checks
- Boolean class expressions, union/intersection/complement
- Full RDF triple canonicalization/serialization in v1 (Turtle later)
- RDF-star, named graphs, provenance graphs beyond our audit log

## Acceptance tests for this layer

1. Subclass transitivity: `startup < company < organization` derives all.
2. Type inheritance: a term typed `companies` is effectively an `organizations`
   member but is still cached as `companies`.
3. `sameAs` closure: a→b→c makes a/b/c one canonical term; category conflicts
   fail.
4. `broader`/`narrower` inverse + transitive closure.
5. `related` symmetry.
6. Disjoint categories: a term with `companies` and `roles` fails validation.
7. Functional property: two normalized values for one term fails.
8. Inverse-functional: two terms claiming one raw form fails.
9. Label ancestry: `ai/agents` has ancestor `ai`.
10. Cache/graph mismatch fails export.
11. Cyclic `sameAs`/`broader` graphs terminate (bounded term set).
12. Rules and validation run under the JVM test suite, not only bb.

## Open questions

1. Materialize closure now or only after profiling? Proposal: query-time first,
   materialize if a query shows repeated closure work.
2. Do we need a generic `triple/3` view for RDF/Turtle export in Phase 6?
   Proposal: yes, export-only.
3. Should `related` be transitive? SKOS `related` is symmetric but not
   transitive; default symmetric only.
4. Is `organizations` a real cache category or only a class parent? Proposal:
   only a class parent; cache categories remain the five configured leaves.
