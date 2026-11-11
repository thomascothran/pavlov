(ns explorer-mock.gen
  "Generates canned data for the Pavlov Explorer UI mock-ups.

  The mock-ups are static pages. This namespace builds the real LTS for a few
  small programs and writes it to `data.js`, so the mock-ups show states and
  transitions the model checker would actually produce.

  Events are deliberately mixed: most are maps, some carry nested maps, and
  some are bare keywords.

  Run from the repository root:

    clojure -Sdeps '{:paths [\"doc/design/010_pavlov-explorer/mockups/gen\"]}' \\
            -M -m explorer-mock.gen doc/design/010_pavlov-explorer/mockups/data.js"
  (:require [clojure.pprint :as pprint]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [jsonista.core :as json]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]))

;; ---------------------------------------------------------------------------
;; Account opening: the bank demo from `dev/demo/bank/domain.cljc`, with
;; events that carry data.

(defn make-request-cip-verification-bthread
  []
  (b/scenario [{:wait-on #{:application-submitted}}
               {:request #{:request-cip-verification}}]))

(defn make-request-ofac-screening-bthread
  []
  (b/scenario [{:wait-on #{:application-submitted}}
               {:request #{{:type :ofac-screening-requested
                            :lists [:sdn :consolidated]
                            :subject {:name "Ada Okafor" :country "NG"}}}}]))

(defn make-cip-failure-rule-bthread
  []
  (b/on :cip-failed
        (constantly
         {:request #{{:type :application-declined :reason :cip-failed}}})))

(defn make-ofac-hit-rule-bthread
  []
  (b/on :ofac-hit
        (constantly
         {:request #{{:type :application-declined :reason :ofac-hit}}})))

(defn make-request-initial-deposit-bthread
  []
  (b/scenario [{:request #{:initial-deposit-requested}}]))

(defn make-block-deposit-until-cip-verified
  []
  (b/scenario [{:block #{:initial-deposit-requested}
                :wait-on #{:cip-verified}}]))

(defn make-block-opening-until-ofac-cleared
  []
  (b/scenario [{:block #{:initial-deposit-requested}
                :wait-on #{:ofac-clear}}]))

(defn make-open-on-funding-bthread
  []
  (b/scenario [{:wait-on #{:initial-deposit-paid}}
               {:request #{{:type :account-opened
                            :account {:number "0042-7781"
                                      :kind :checking
                                      :holder {:name "Ada Okafor"}}}}}]))

(defn make-application-submitted-bthread
  []
  (b/scenario
   [{:request #{{:type :application-submitted
                 :product :checking
                 :applicant {:name "Ada Okafor"
                             :date-of-birth "1990-04-12"
                             :address {:street "14 Marina Road"
                                       :city "Lagos"
                                       :country "NG"}}}}}]))

(defn make-pay-deposit-bthread
  []
  (b/scenario [{:request #{{:type :initial-deposit-paid
                            :amount 250
                            :currency "USD"}}}]))

(defn make-account-opening-requires-ofac-screening-bthread
  []
  (b/scenario [{:wait-on #{:account-opened}}
               {:wait-on #{:ofac-clear}
                :request #{{:type :account-opened
                            :invariant-violated true}}}]))

;; ---------------------------------------------------------------------------
;; Order fulfilment: has a loop, a proper ending, a dead end, and two events
;; of the same type that differ only in their data.

(defn make-customer-bthread
  []
  (b/scenario
   [{:request #{{:type :order/placed
                 :order {:id "ord-1042"
                         :customer {:name "Sam Reyes" :tier :gold}
                         :items [{:sku "kb-75" :qty 1 :price 80}
                                 {:sku "cable-usb-c" :qty 2 :price 10}]
                         :total 100}}}}]))

(defn make-payment-bthread
  []
  (b/step
   (fn [state event]
     (let [ask [:awaiting-payment
                {:request #{{:type :payment/received
                             :amount 100
                             :method {:kind :card :last4 "4242"}}
                            {:type :payment/received
                             :amount 50
                             :method {:kind :gift-card :code "GC-7F2"}}
                            {:type :payment/declined
                             :reason :insufficient-funds
                             :attempt {:method {:kind :card :last4 "4242"}
                                       :processor {:code "51"
                                                   :message "Insufficient funds"}}}}}]]
       (case [state (e/type event)]
         [nil nil] [:no-order {:wait-on #{:order/placed}}]
         [:no-order :order/placed] ask
         [:awaiting-payment :payment/declined] [:declined {:wait-on #{:payment/retry-requested}}]
         [:declined :payment/retry-requested] ask
         [:paid nil])))))

(defn make-retry-declined-payment-bthread
  []
  (b/step
   (fn [state event]
     (if (and (= :waiting state) (= :payment/declined (e/type event)))
       [:retrying {:request #{:payment/retry-requested}}]
       [:waiting {:wait-on #{:payment/declined}}]))))

(defn make-ship-when-paid-bthread
  []
  (b/scenario [{:wait-on #{:payment/received}}
               {:request #{{:type :order/shipped
                            :shipment {:carrier "DHL"
                                       :tracking "JD014600003"
                                       :to {:city "Porto" :country "PT"}}}}}
               {:request #{{:type :order/completed :terminal true}}}]))

(defn make-hold-part-paid-orders-bthread
  []
  (b/step
   (fn [state event]
     (cond
       (nil? state) [:watching {:wait-on #{:payment/received}}]
       (and (= :watching state) (< (:amount event 0) 100))
       [:holding {:block #{:order/shipped}}]
       (= :holding state) [:holding {:block #{:order/shipped}}]
       :else [:satisfied nil]))))

;; ---------------------------------------------------------------------------
;; Ticket queue: never ends, so exploration stops at the configured limit.

(defn make-open-tickets-bthread
  []
  (b/step
   (fn [state _event]
     (let [n (inc (or state -1))]
       [n {:request #{{:type :ticket/opened
                       :number (inc n)
                       :from {:channel :email}}}}]))))

(defn make-escalate-every-third-ticket-bthread
  []
  (b/step
   (fn [state event]
     (let [n (if (= :ticket/opened (e/type event)) (inc (or state 0)) (or state 0))]
       (if (and (pos? n) (zero? (mod n 3)) (= :ticket/opened (e/type event)))
         [n {:request #{:ticket/escalated}}]
         [n {:wait-on #{:ticket/opened}}])))))

;; ---------------------------------------------------------------------------
;; Catalogue. Each entry is a name, a description and a model-check config.
;; `:sources` maps a bthread name to the var whose source defines it; it only
;; exists to give the mock-ups some code to show.

(def catalogue
  [{:id "account-opening"
    :name "Account opening"
    :description "Opening a bank account: identity (CIP) and sanctions (OFAC) checks, the initial deposit, and the rule that an account must not open before sanctions are cleared."
    :config {:bthreads {:bank/request-cip-verification (make-request-cip-verification-bthread)
                        :bank/request-ofac-screening (make-request-ofac-screening-bthread)
                        :bank/cip-failure-rule (make-cip-failure-rule-bthread)
                        :bank/ofac-hit-rule (make-ofac-hit-rule-bthread)
                        :bank/request-initial-deposit (make-request-initial-deposit-bthread)
                        :bank/block-deposit-until-cip-verified (make-block-deposit-until-cip-verified)
                        :bank/block-opening-until-ofac-cleared (make-block-opening-until-ofac-cleared)
                        :bank/open-on-funding (make-open-on-funding-bthread)}
             :environment-bthreads {:bank/application-submitted (make-application-submitted-bthread)
                                    :bank/pay-deposit (make-pay-deposit-bthread)}
             :safety-bthreads {:bank/account-opening-requires-ofac-screening
                               (make-account-opening-requires-ofac-screening-bthread)}
             :check-deadlock? false}
    :sources {:bank/request-cip-verification #'make-request-cip-verification-bthread
              :bank/request-ofac-screening #'make-request-ofac-screening-bthread
              :bank/cip-failure-rule #'make-cip-failure-rule-bthread
              :bank/ofac-hit-rule #'make-ofac-hit-rule-bthread
              :bank/request-initial-deposit #'make-request-initial-deposit-bthread
              :bank/block-deposit-until-cip-verified #'make-block-deposit-until-cip-verified
              :bank/block-opening-until-ofac-cleared #'make-block-opening-until-ofac-cleared
              :bank/open-on-funding #'make-open-on-funding-bthread
              :bank/application-submitted #'make-application-submitted-bthread
              :bank/pay-deposit #'make-pay-deposit-bthread
              :bank/account-opening-requires-ofac-screening
              #'make-account-opening-requires-ofac-screening-bthread}}

   {:id "order-fulfilment"
    :name "Order fulfilment"
    :description "A customer places an order and pays. Declined payments can be retried, full payment ships the order, and part payment puts it on hold."
    :config {:bthreads {:ship-when-paid (make-ship-when-paid-bthread)
                        :retry-declined-payment (make-retry-declined-payment-bthread)
                        :hold-part-paid-orders (make-hold-part-paid-orders-bthread)}
             :environment-bthreads {:customer (make-customer-bthread)
                                    :payment (make-payment-bthread)}}
    :sources {:ship-when-paid #'make-ship-when-paid-bthread
              :retry-declined-payment #'make-retry-declined-payment-bthread
              :hold-part-paid-orders #'make-hold-part-paid-orders-bthread
              :customer #'make-customer-bthread
              :payment #'make-payment-bthread}}

   {:id "ticket-queue"
    :name "Ticket queue"
    :description "Support tickets keep arriving and every third one is escalated. This program never ends, so it is only explored up to a limit."
    :config {:bthreads {:escalate-every-third-ticket (make-escalate-every-third-ticket-bthread)}
             :environment-bthreads {:open-tickets (make-open-tickets-bthread)}
             :max-nodes 8}
    :sources {:escalate-every-third-ticket #'make-escalate-every-third-ticket-bthread
              :open-tickets #'make-open-tickets-bthread}}])

;; "Things to try" links for index.html. Each step is a substring of the
;; printed event to choose; `:extra` is appended to the address as is.

(def cases
  [["Pick a program from the catalogue" nil []]
   ["Part-way through, with alternatives at each step" "account-opening"
    ["application-submitted" "request-cip" "ofac-screening"]]
   ["A rule violation, with more still possible" "account-opening"
    ["initial-deposit-paid" "account-opened" "invariant-violated"]]
   ["Nothing more can happen (deadlock checking off)" "account-opening"
    ["application-submitted" "initial-deposit-paid" "ofac-screening" "request-cip"
     "account-opened" "invariant-violated"]]
   ["A loop: you have been here before" "order-fulfilment" ["order/placed" "declined" "retry"]]
   ["Same event type, different data" "order-fulfilment" ["order/placed"]]
   ["A proper ending" "order-fulfilment" ["order/placed" ":amount 100" "shipped" "completed"]]
   ["The program is stuck" "order-fulfilment" ["order/placed" ":amount 50"]]
   ["The edge of what was explored" "ticket-queue"
    [":number 1" ":number 2" ":number 3" "escalated" ":number 4"]]
   ["A link that no longer matches the program" "order-fulfilment"
    ["order/placed" ":amount 100"] ".9.0"]
   ["A link to a program that is not offered" "loan-approval" :unknown]])

;; ---------------------------------------------------------------------------

(defn- all-bthreads
  "Same assembly order as `model.check/assemble-all-bthreads`."
  [{:keys [bthreads environment-bthreads safety-bthreads]}]
  (reduce into (if (sequential? bthreads) [] {})
          [safety-bthreads environment-bthreads bthreads]))

(defn- roles
  [{:keys [bthreads environment-bthreads safety-bthreads]}]
  (merge (zipmap (map first bthreads) (repeat "rule"))
         (zipmap (map first environment-bthreads) (repeat "environment"))
         (zipmap (map first safety-bthreads) (repeat "safety check"))))

(defn- strs [xs] (vec (sort (map pr-str xs))))

(defn- pretty
  [x width]
  (binding [pprint/*print-right-margin* width]
    (str/trimr (with-out-str (pprint/pprint x)))))

(defn- event->json
  "An event as the pages need it: as written, pretty-printed, and split into
  its type and its other fields."
  [event]
  (let [fields (when (map? event) (dissoc event :type))]
    {:event (pr-str event)
     :pretty (pretty event 56)
     :type (pr-str (e/type event))
     :keyword (not (map? event))
     :summary (str/join " " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) fields))
     :fields (mapv (fn [[k v]]
                     {:k (pr-str k)
                      :v (pr-str v)
                      :pretty (pretty v 44)
                      :nested (coll? v)})
                   fields)
     :terminal (boolean (e/terminal? event))
     :violation (boolean (and (map? event) (:invariant-violated event)))}))

(defn- node->json
  [{:keys [bthread->bid saved-bthread-states]}]
  (into {}
        (map (fn [[bthread-name state]]
               (let [bid (get bthread->bid bthread-name)]
                 [(pr-str bthread-name)
                  {:state (pr-str state)
                   :request (strs (some-> bid bid/request))
                   :waitOn (strs (some-> bid bid/wait-on))
                   :block (strs (some-> bid bid/block))}])))
        saved-bthread-states))

(defn- program->json
  [{:keys [id name description config sources]}]
  (let [lts (graph/->lts (all-bthreads config)
                         {:max-nodes (:max-nodes config 100)})
        role-of (roles config)]
    {:id id
     :name name
     :description description
     :checkDeadlock (not= false (:check-deadlock? config))
     :truncated (boolean (:truncated lts))
     :root (:root lts)
     :bthreads (->> role-of
                    (map (fn [[bthread-name role]]
                           {:name (pr-str bthread-name)
                            :role role
                            :source (some-> (get sources bthread-name)
                                            symbol
                                            repl/source-fn)}))
                    (sort-by (juxt :role :name))
                    vec)
     :nodes (update-vals (:nodes lts) node->json)
     :edges (mapv (fn [{:keys [from to event]}]
                    (assoc (event->json event)
                           :from from
                           :to to
                           :requestedBy (strs (get-in lts [:nodes from :requests event]))))
                  (:edges lts))}))

(defn- case->json
  [programs [label program-id steps extra]]
  {:label label
   :hash (cond
           (nil? program-id) ""
           (= :unknown steps) (str "#/" program-id "/0")
           :else
           (let [{:keys [root edges]} (first (filter #(= program-id (:id %)) programs))
                 out (group-by :from edges)]
             (loop [node root, steps steps, idx []]
               (if-let [step (first steps)]
                 (let [options (get out node)
                       i (first (keep-indexed #(when (str/includes? (:event %2) step) %1) options))]
                   (assert i (str "no choice matching " step " in " label))
                   (recur (:to (nth options i)) (rest steps) (conj idx i)))
                 (str "#/" program-id "/" (str/join "." idx) extra)))))})

(defn -main
  [out]
  (let [programs (mapv program->json catalogue)
        mapper (json/object-mapper {:pretty true})]
    (spit out (str "// Generated by doc/design/010_pavlov-explorer/mockups/gen. Do not edit.\n"
                   "window.EXPLORER_DATA = "
                   (json/write-value-as-string {:programs programs} mapper)
                   ";\n"
                   "window.EXPLORER_CASES = "
                   (json/write-value-as-string (mapv #(case->json programs %) cases) mapper)
                   ";\n"))
    (println "wrote" out)))
