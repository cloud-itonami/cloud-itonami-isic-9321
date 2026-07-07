(ns parksafety.parkopsllm
  "ParkOps-LLM client -- the *contained intelligence node* for the
  amusement-park/ride-safety actor.

  It normalizes ride intake, drafts a per-jurisdiction ride-safety
  evidence checklist, screens rides for a failed post-hold inspection,
  and drafts the ride-reopening action. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record or a real ride reopening.
  Every output is censored downstream by `parksafety.governor` before
  anything touches the SSoT, and `:ride/reopen` proposals NEVER auto-
  commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/reopen-ride | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [parksafety.facts :as facts]
            [parksafety.registry :as registry]
            [parksafety.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the ride, hold reason/inspection status or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "施設記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :ride/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction ride-safety evidence checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in
  `parksafety.facts` -- the Ride Safety Governor must reject this
  (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [r (store/ride db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction r))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "parksafety.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-inspection
  "Post-hold inspection screening draft. `:post-hold-inspection-
  passed?` on the ride record injects the failure mode: the Ride
  Safety Governor must HOLD, un-overridably, on any failed inspection."
  [db {:keys [subject]}]
  (let [r (store/ride db subject)]
    (cond
      (nil? r)
      {:summary "対象rideが見つかりません" :rationale "no ride record"
       :cites [] :effect :inspection/set :value {:ride-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:post-hold-inspection-passed? r))
      {:summary    (str (:ride-name r) ": 再開前検査の不合格を検出")
       :rationale  "スクリーニングが不合格の検査結果を検出。人手確認とホールドが必須。"
       :cites      [:inspection-check]
       :effect     :inspection/set
       :value      {:ride-id subject :verdict :failed}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:ride-name r) ": 検査合格")
       :rationale  "検査スクリーニング完了。"
       :cites      [:inspection-check]
       :effect     :inspection/set
       :value      {:ride-id subject :verdict :passed}
       :stake      nil
       :confidence 0.9})))

(defn- propose-ride-reopening
  "Draft the actual RIDE-REOPENING action -- reopening a real ride to
  patrons after a safety hold. ALWAYS `:stake :actuation/reopen-ride`
  -- this is a REAL-WORLD, safety-critical act, never a draft the
  actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`parksafety.phase`); the governor also
  always escalates on `:actuation/reopen-ride`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [r (store/ride db subject)
        ready? (and r (:post-hold-inspection-passed? r) (registry/operators-sufficient? r))]
    {:summary    (str subject " 向け再開提案"
                      (when r (str " (ride=" (:ride-name r) ")")))
     :rationale  (if r
                   (str "post-hold-inspection-passed?=" (:post-hold-inspection-passed? r)
                        " certified-operators-on-duty=" (:certified-operators-on-duty r)
                        " minimum-operators-required=" (:minimum-operators-required r))
                   "rideが見つかりません")
     :cites      (if r [subject] [])
     :effect     :ride/mark-reopened
     :value      {:ride-id subject}
     :stake      :actuation/reopen-ride
     :confidence (if ready? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :ride/intake             (normalize-intake db request)
    :jurisdiction/assess        (assess-jurisdiction db request)
    :inspection/screen              (screen-inspection db request)
    :ride/reopen                       (propose-ride-reopening db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは遊園地の遊戯施設再開エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:ride/upsert|:assessment/set|:inspection/set|"
       ":ride/mark-reopened) "
       ":stake(:actuation/reopen-ride か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:ride (store/ride st subject)}
    :inspection/screen    {:ride (store/ride st subject)}
    :ride/reopen          {:ride (store/ride st subject)}
    {:ride (store/ride st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Ride Safety Governor
  escalates/holds -- an LLM hiccup can never auto-reopen a ride."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :parkopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
