(ns parksafety.facts
  "Per-jurisdiction amusement-ride-safety regulatory catalog -- the
  G2-style spec-basis table the Ride Safety Governor checks every
  jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's ride-safety
  requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official ride-safety
  regulator (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done --
  never invent a jurisdiction's requirements to make coverage look
  bigger.

  Like `credit.facts`'s/`wagering.facts`'s federated jurisdictions,
  the USA and DEU entries here cite a single representative state
  regulator (fixed-site amusement-ride safety is regulated at state
  level in both countries) rather than a state-by-state survey -- an
  honest representative citation, the same simplification every prior
  catalog makes when a jurisdiction's real regulatory structure is
  itself federated.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  inspection-record/maintenance-log/ride-operator-certification/
  incident-report evidence set submitted in some form; `:legal-basis`
  / `:owner-authority` / `:provenance` are the G2 citation the
  governor requires before any :jurisdiction/assess proposal can
  commit."
  {"JPN" {:name "Japan"
          :owner-authority "国土交通省 (Ministry of Land, Infrastructure, Transport and Tourism, MLIT)"
          :legal-basis "建築基準法 (Building Standards Act) 第12条 -- 遊戯施設定期検査報告制度"
          :national-spec "遊戯施設定期検査業務基準 (periodic amusement-ride inspection standards)"
          :provenance "https://www.mlit.go.jp/"
          :required-evidence ["定期検査報告書 (inspection record)"
                              "整備記録 (maintenance-log documentation)"
                              "運転者資格確認記録 (ride-operator certification)"
                              "事故報告書 (incident-report documentation)"]}
   "USA" {:name "United States"
          :owner-authority "California Division of Occupational Safety and Health (Cal/OSHA), Amusement Ride and Tramway Unit"
          :legal-basis "California Code of Regulations, Title 8, Article 20 (Amusement Rides)"
          :national-spec "Cal/OSHA permit-to-operate inspection and certification requirements"
          :provenance "https://www.dir.ca.gov/dosh/amusementrides.html"
          :required-evidence ["Inspection record"
                              "Maintenance-log documentation"
                              "Ride-operator certification"
                              "Incident-report documentation"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Health and Safety Executive (HSE)"
          :legal-basis "Health and Safety at Work etc. Act 1974 (HSG175 guidance)"
          :national-spec "HSG175 Fairgrounds and amusement parks: safe practice guidance"
          :provenance "https://www.hse.gov.uk/entertainment/fairgrounds/"
          :required-evidence ["Inspection record"
                              "Maintenance-log documentation"
                              "Ride-operator certification"
                              "Incident-report documentation"]}
   "DEU" {:name "Germany"
          :owner-authority "Gewerbeaufsichtsämter der Länder (state trade-supervisory authorities)"
          :legal-basis "Betriebssicherheitsverordnung (BetrSichV, Operational Safety Ordinance)"
          :national-spec "BetrSichV Prüfpflichten für überwachungsbedürftige Anlagen (Fahrgeschäfte)"
          :provenance "https://www.gesetze-im-internet.de/betrsichv_2015/"
          :required-evidence ["Prüfbericht (inspection record)"
                              "Wartungsprotokoll (maintenance-log documentation)"
                              "Bedienerqualifikationsnachweis (ride-operator certification)"
                              "Unfallbericht (incident-report documentation)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to reopen a ride
  on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-9321 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `parksafety.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
