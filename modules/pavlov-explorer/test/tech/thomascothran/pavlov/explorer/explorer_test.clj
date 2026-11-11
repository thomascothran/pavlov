(ns tech.thomascothran.pavlov.explorer.explorer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.explorer :as explorer]
            [tech.thomascothran.pavlov.explorer.demo :as demo]
            [tech.thomascothran.pavlov.explorer.page :as page]
            [tech.thomascothran.pavlov.explorer.position :as position]
            [tech.thomascothran.pavlov.explorer.program :as program]
            [tech.thomascothran.pavlov.graph :as graph]))

(defn- entry [id] (first (filter #(= id (:id %)) demo/catalogue)))

(defn- trace
  "Fingerprints for a route through `program`. Each step is a substring of
  the printed event to choose."
  [program steps]
  (loop [node (:root program), steps steps, fps []]
    (if-let [step (first steps)]
      (let [edge (first (filter #(str/includes? (pr-str (:event %)) step)
                                (get-in program [:out node])))]
        (assert edge (str "no choice matching " step))
        (recur (:to edge) (rest steps) (conj fps (:fingerprint edge))))
      fps)))

(defn- at [program steps] (position/locate program (trace program steps)))

(defn- kinds [position] (set (map :kind (:banners position))))

(def orders (delay (program/build (entry :order-fulfilment))))
(def accounts (delay (program/build (entry :account-opening))))
(def tickets (delay (program/build (entry :ticket-queue))))

(deftest build-describes-the-program
  (testing "roles come from the config"
    (is (= :rule (get-in @orders [:roles :ship-when-paid])))
    (is (= :environment (get-in @orders [:roles :customer])))
    (is (= :safety (get-in @accounts [:roles :bank/account-opening-requires-ofac-screening]))))
  (testing "source is found through the var"
    (is (str/starts-with? (get-in @orders [:sources :customer])
                          "(defn make-customer-bthread")))
  (testing "limits and deadlock checking come from the config"
    (is (:truncated? @tickets))
    (is (not (:truncated? @orders)))
    (is (false? (:check-deadlock? @accounts)))
    (is (true? (:check-deadlock? @orders)))))

(deftest a-config-without-a-limit-is-explored-up-to-the-default
  (let [counter (fn [] (b/step (fn [state _] [(inc (or state 0)) {:request #{:tick}}])))
        built (with-redefs [program/default-max-nodes 5]
                (program/build {:id :endless :make-config (fn [] {:bthreads {:counter (counter)}})}))]
    (is (:truncated? built))
    (is (= 5 (count (:nodes built))))))

(deftest explorer-and-model-checker-see-the-same-transitions
  (let [config (demo/make-order-fulfilment-config)
        lts (graph/->lts (reduce into {} [(:safety-bthreads config)
                                          (:environment-bthreads config)
                                          (:bthreads config)])
                         {})]
    (is (= (set (map #(select-keys % [:from :to :event]) (:edges lts)))
           (set (map #(select-keys % [:from :to :event])
                     (mapcat val (:out @orders))))))))

(deftest locate-follows-the-trace
  (let [start (at @orders [])]
    (is (empty? (:steps start)))
    (is (= 1 (count (:choices start))))
    (is (empty? (:banners start))))
  (let [position (at @orders ["order/placed"])]
    (is (= [:order/placed] (map (comp :type :edge) (:steps position))))
    (testing "same type, different data, are separate choices"
      (is (= [:payment/declined :payment/received :payment/received]
             (sort (map :type (:choices position)))))
      (is (apply distinct? (map :fingerprint (:choices position)))))
    (testing "the bthreads an event changed are known"
      (is (contains? (:changed position) :payment))
      (is (not (contains? (:changed position) :retry-declined-payment))))
    (testing "a bthread that finished because of the event counts as changed"
      (is (contains? (:changed position) :customer))))
  (testing "siblings are the other options at a step"
    (let [{:keys [steps]} (at @orders ["order/placed" ":amount 100"])]
      (is (= 3 (count (:options (peek steps))))))))

(deftest locate-marks-the-position
  (is (= #{:finished} (kinds (at @orders ["order/placed" ":amount 100" "shipped" "completed"]))))
  (is (= #{:stuck} (kinds (at @orders ["order/placed" ":amount 50"]))))
  (let [position (at @orders ["order/placed" "declined" "retry"])]
    (is (= #{:revisit} (kinds position)))
    (is (= 1 (:seen-at (first (:banners position))))))
  (let [position (at @accounts ["initial-deposit-paid" "account-opened" "invariant-violated"])]
    (is (contains? (kinds position) :violation))
    (is (= [:bank/account-opening-requires-ofac-screening]
           (:reported-by (first (:banners position))))))
  (testing "a dead end is not called stuck when deadlock checking is off"
    (is (= #{:nothing}
           (disj (kinds (at @accounts ["application-submitted" "initial-deposit-paid"
                                       "ofac-screening" "request-cip"
                                       "account-opened" "invariant-violated"]))
                 :violation))))
  (testing "the edge of what was explored is not called stuck"
    (is (= #{:edge} (kinds (at @tickets [":number 1" ":number 2" ":number 3"
                                         "escalated" ":number 4"]))))))

(deftest locate-stops-at-a-step-the-program-cannot-take
  (let [good (trace @orders ["order/placed" ":amount 100"])
        position (position/locate @orders (conj good "deadbeef" "cafebabe"))]
    (is (:stale? position))
    (is (= good (:trace position)))
    (is (contains? (kinds position) :notice))))

;; ---------------------------------------------------------------------------
;; Pages

(defn- make-handler
  ([] (make-handler demo/catalogue))
  ([catalogue] (explorer/make-handler {:catalogue catalogue :!programs (atom {})})))

(defn- GET
  ([handler uri] (GET handler uri nil))
  ([handler uri query] (handler {:request-method :get :uri uri :query-string query})))

(deftest catalogue-page
  (let [{:keys [status body]} (GET (make-handler) "/")]
    (is (= 200 status))
    (is (str/includes? body "Account opening"))
    (is (str/includes? body "A customer places an order and pays."))
    (is (str/includes? body "href=\"/p/order-fulfilment\"")))
  (is (str/includes? (:body (GET (make-handler []) "/"))
                     "There are no programs to explore.")))

(deftest program-page
  (let [handler (make-handler)
        fps (trace @orders ["order/placed" ":amount 100"])
        {:keys [status body]} (GET handler (page/href "order-fulfilment" fps nil))]
    (is (= 200 status))
    (testing "the trace, with a link back to each step and to each sibling"
      (is (str/includes? body (str "href=\"/p/order-fulfilment/" (first fps) "\"")))
      (is (str/includes? body "2 alternatives"))
      (is (str/includes? body "instead: ")))
    (testing "the next choice links one step further"
      (let [[next-edge] (:choices (position/locate @orders fps))]
        (is (str/includes? body (str "href=\""
                                     (page/href "order-fulfilment"
                                                (conj fps (:fingerprint next-edge)) nil)
                                     "\"")))))
    (testing "bthreads are hidden until asked for"
      (is (not (str/includes? body "class=\"drawer\"")))))
  (testing "the bthread view stays open as the user moves"
    (let [body (:body (GET (make-handler) "/p/order-fulfilment" "bthreads=1"))]
      (is (str/includes? body "class=\"drawer\""))
      (is (str/includes? body "?bthreads=1"))
      (is (str/includes? body "(defn make-customer-bthread"))))
  (testing "a bthread with nothing further to do is shown as finished"
    (let [fps (trace @orders ["order/placed"])
          body (:body (GET (make-handler) (page/href "order-fulfilment" fps nil) "bthreads=1"))]
      (is (re-find #":customer</div><div class=\"bt-sum\">.*?finished" body)))))

(deftest links-that-no-longer-fit
  (let [handler (make-handler)]
    (let [{:keys [status body]} (GET handler "/p/loan-approval")]
      (is (= 404 status))
      (is (str/includes? body "The program \"loan-approval\" is not available"))
      (is (str/includes? body "Account opening")))
    (let [{:keys [status body]} (GET handler "/p/order-fulfilment/deadbeef")]
      (is (= 200 status))
      (is (str/includes? body "This link no longer matches the program")))))

(deftest reload-rebuilds-the-program
  (let [!builds (atom 0)
        handler (make-handler [{:id "counted"
                                :name "Counted"
                                :description ""
                                :make-config (fn []
                                               (swap! !builds inc)
                                               {:bthreads {:a (b/scenario [{:request #{:a}}])}})}])]
    (GET handler "/p/counted")
    (GET handler "/p/counted")
    (is (= 1 @!builds) "explored once and kept")
    (let [response (handler {:request-method :post
                             :uri "/p/counted/reload"
                             :headers {"content-type" "application/x-www-form-urlencoded"}
                             :body (java.io.ByteArrayInputStream. (.getBytes "trace=abc.def&bthreads=1"))})]
      (is (= 303 (:status response)))
      (is (= "/p/counted/abc.def?bthreads=1" (get-in response [:headers "location"]))))
    (GET handler "/p/counted")
    (is (= 2 @!builds))))

(deftest a-program-that-cannot-be-built
  (let [handler (make-handler [{:id "broken" :name "Broken" :description ""
                                :make-config (fn [] (throw (ex-info "no such rule" {})))}])
        {:keys [status body]} (GET handler "/p/broken")]
    (is (= 500 status))
    (is (str/includes? body "This program could not be loaded"))
    (is (str/includes? body "no such rule"))))
