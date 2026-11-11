(ns tech.thomascothran.pavlov.explorer.program
  "Turn a catalogue entry into the data the explorer pages are built from.

  A catalogue entry is a map:

    :id          - string or simple keyword; names the program in the address
    :name        - shown to the user
    :description - shown to the user
    :make-config - function of no arguments returning a fresh model-check
                   config (see `tech.thomascothran.pavlov.model.check/check`).
                   Without `:max-nodes`, exploration stops at 5,000 nodes.
                   It is called every time the program is built, because
                   bthreads are stateful. Pass a var so a re-evaluated
                   definition is picked up when the program is reloaded.
    :sources     - optional map of bthread name -> var whose source is shown
                   as that bthread's code

  The program is explored with `graph/->lts`, the same way the model checker
  explores it."
  (:require [clojure.repl :as repl]
            [tech.thomascothran.pavlov.bid.proto :as bid]
            [tech.thomascothran.pavlov.event :as e]
            [tech.thomascothran.pavlov.graph :as graph]))

(defn program-id
  "The entry's id as it appears in a page address."
  [entry]
  (let [id (:id entry)]
    (if (keyword? id) (name id) (str id))))

(defn fingerprint
  "Short, stable name for an event, used to record a step in a page address.
  It depends only on the event's value, so an address keeps meaning the same
  events after the program is rebuilt."
  [event]
  (format "%08x" (hash event)))

(defn- all-bthreads
  "Same assembly order as `model.check/assemble-all-bthreads`."
  [{:keys [bthreads environment-bthreads safety-bthreads]}]
  (reduce into (if (sequential? bthreads) [] {})
          [safety-bthreads environment-bthreads bthreads]))

(def role-order [:rule :environment :safety :spawned])

(defn- roles
  [{:keys [bthreads environment-bthreads safety-bthreads]}]
  (merge (zipmap (map first bthreads) (repeat :rule))
         (zipmap (map first environment-bthreads) (repeat :environment))
         (zipmap (map first safety-bthreads) (repeat :safety))))

(defn- node->bthreads
  "What each bthread is doing at a node: bthread name -> state and bid parts."
  [{:keys [bthread->bid saved-bthread-states]}]
  (into {}
        (map (fn [[bthread-name state]]
               (let [bid (get bthread->bid bthread-name)]
                 [bthread-name
                  {:state state
                   :request (set (some-> bid bid/request))
                   :wait-on (set (some-> bid bid/wait-on))
                   :block (set (some-> bid bid/block))}])))
        saved-bthread-states))

(defn- source-of
  [v]
  (when (var? v)
    (try (repl/source-fn (symbol v))
         (catch Exception _ nil))))

(def default-max-nodes
  "Exploration limit for a config that sets no `:max-nodes`. The model checker
  has no limit in that case; the explorer has one so that opening a program
  that never ends cannot hang the page."
  5000)

(defn build
  "Explore a catalogue entry's program. Returns:

    :id :name :description
    :check-deadlock? - false when the config turns deadlock checking off
    :truncated?      - true when exploration stopped at `:max-nodes`
    :root            - id of the starting node
    :roles           - bthread name -> :rule, :environment or :safety
    :sources         - bthread name -> source text, where available
    :nodes           - node id -> bthread name -> {:state :request :wait-on :block}
    :out             - node id -> vector of edges leaving it, each
                       {:from :to :event :fingerprint :type :terminal?
                        :violation? :requested-by}"
  [entry]
  (let [config ((:make-config entry))
        lts (graph/->lts (all-bthreads config)
                         {:max-nodes (:max-nodes config default-max-nodes)})
        edges (mapv (fn [{:keys [from event] :as edge}]
                      (assoc edge
                             :fingerprint (fingerprint event)
                             :type (e/type event)
                             :terminal? (boolean (e/terminal? event))
                             :violation? (boolean (and (map? event)
                                                       (:invariant-violated event)))
                             :requested-by (vec (sort-by pr-str
                                                         (get-in lts [:nodes from :requests event])))))
                    (:edges lts))]
    {:id (program-id entry)
     :name (:name entry)
     :description (:description entry)
     :check-deadlock? (not= false (:check-deadlock? config))
     :truncated? (boolean (:truncated lts))
     :root (:root lts)
     :roles (roles config)
     :sources (into {}
                    (keep (fn [[bthread-name v]]
                            (when-let [source (source-of v)]
                              [bthread-name source])))
                    (:sources entry))
     :nodes (update-vals (:nodes lts) node->bthreads)
     :out (group-by :from edges)}))
