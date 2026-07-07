(ns parksafety.registry
  "Pure-function ride-reopening record construction -- an append-only
  park book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a ride-reopening reference number -- every
  park/jurisdiction assigns its own reference format. This namespace
  does NOT invent one; it builds a jurisdiction-scoped sequence number
  and validates the record's required fields, the same honest, non-
  fabricating discipline `parksafety.facts` uses.

  `operators-sufficient?` reuses `marketadmin.registry/listing-
  standard-met?`'s/`registrar.registry/credits-sufficient?`'s MINIMUM-
  threshold pure-ground-truth-recompute shape for a further domain: a
  ride must not reopen with fewer certified operators on duty than its
  own minimum staffing requirement -- a real, foundational amusement-
  ride-safety concept (complex rides require a minimum number of
  trained operators present to run safely), not an invented figure.
  Unlike `marketadmin.registry`'s/`registrar.registry`'s single global
  threshold constants, `:minimum-operators-required` is a PER-RIDE
  ground-truth field (different rides genuinely require different
  minimum staffing), so this check compares two fields on the SAME
  entity rather than one field against a shared constant -- a further
  generalization of the minimum-threshold family.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real park-operations system. It builds the RECORD a park
  would keep, not the act of reopening the ride itself (that is
  `parksafety.operation`'s `:ride/reopen`, always human-gated -- see
  README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed ride operator/inspector's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn operators-sufficient?
  "Does `ride`'s own `:certified-operators-on-duty` satisfy its own
  `:minimum-operators-required`? A pure ground-truth check comparing
  TWO permanent fields on the same entity -- see ns docstring for how
  this generalizes the minimum-threshold family beyond a single shared
  constant."
  [{:keys [certified-operators-on-duty minimum-operators-required]}]
  (and (number? certified-operators-on-duty)
       (>= certified-operators-on-duty minimum-operators-required)))

(defn register-ride-reopening
  "Validate + construct the RIDE-REOPENING registration DRAFT -- the
  park's own legal act of reopening a real ride to patrons after a
  safety hold. Pure function -- does not touch any real park-
  operations system; it builds the RECORD a park would keep.
  `parksafety.governor` independently re-verifies the ride's own post-
  hold inspection status and operator staffing, and blocks a double-
  reopening of the same ride, before this is ever allowed to commit."
  [ride-id jurisdiction sequence]
  (when-not (and ride-id (not= ride-id ""))
    (throw (ex-info "ride-reopening: ride_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "ride-reopening: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "ride-reopening: sequence must be >= 0" {})))
  (let [reopening-number (str (str/upper-case jurisdiction) "-RDE-" (zero-pad sequence 6))
        record {"record_id" reopening-number
                "kind" "ride-reopening-draft"
                "ride_id" ride-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "reopening_number" reopening-number
     "certificate" (unsigned-certificate "RideReopening" reopening-number reopening-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
