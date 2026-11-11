(ns tech.thomascothran.pavlov.explorer.position
  "Where a user is in a program, worked out from the trace in a page address.")

(defn- banners
  [{:keys [check-deadlock? truncated?]} {:keys [stale? steps node choices visited]}]
  (let [last-edge (:edge (peek steps))
        seen-at (.indexOf ^java.util.List visited node)]
    (cond-> []
      stale?
      (conj {:kind :notice
             :title "This link no longer matches the program"
             :text "You have been placed at the last step of the link that is still possible."})

      (:violation? last-edge)
      (conj {:kind :violation
             :title "A rule has been violated"
             :reported-by (:requested-by last-edge)})

      (:terminal? last-edge)
      (conj {:kind :finished
             :title "The program has finished"
             :text (str (pr-str (:type last-edge))
                        " ended the program. There are no further choices.")})

      (and (not (:terminal? last-edge)) (empty? choices))
      (conj (cond
              truncated? {:kind :edge
                          :title "The program was only explored this far"
                          :text "Nothing is known beyond this point."}
              check-deadlock? {:kind :stuck
                               :title "The program is stuck here"
                               :text "Nothing can happen next, and the program has not finished."}
              :else {:kind :nothing
                     :title "Nothing more can happen here"
                     :text "There are no further choices."}))

      (< seen-at (count steps))
      (conj {:kind :revisit
             :title "You have been here before"
             :seen-at seen-at}))))

(defn locate
  "Follow `fingerprints` from the start of `program`.

  Stops at the first fingerprint that is not among the choices; `:stale?` is
  then true and the position is the last one that could be reached.

  Returns:
    :steps    - vector of {:edge :options}; `:options` are all the choices
                that were available where `:edge` was chosen
    :trace    - fingerprints of the steps actually taken
    :node     - id of the current node
    :choices  - edges that can be chosen next
    :stale?   - true when the address named a step the program cannot take
    :bthreads - bthread name -> what it is doing at the current node
    :changed  - names of bthreads the last event changed
    :banners  - what kind of position this is"
  [program fingerprints]
  (let [{:keys [root out nodes]} program
        [steps node stale?]
        (loop [node root, fps (seq fingerprints), steps []]
          (if-let [fp (first fps)]
            (let [options (get out node [])]
              (if-let [edge (first (filter #(= fp (:fingerprint %)) options))]
                (recur (:to edge) (next fps) (conj steps {:edge edge :options options}))
                [steps node true]))
            [steps node false]))
        now (get nodes node)
        before (some->> (peek steps) :edge :from (get nodes))
        position {:steps steps
                  :trace (mapv (comp :fingerprint :edge) steps)
                  :node node
                  :choices (get out node [])
                  :stale? stale?
                  :visited (into [root] (map (comp :to :edge)) steps)
                  :bthreads now
                  ;; a bthread that finishes is no longer among the node's
                  ;; bthreads, so compare over the names on either side
                  :changed (if before
                             (into #{}
                                   (filter #(not= (get now %) (get before %)))
                                   (concat (keys now) (keys before)))
                             #{})}]
    (assoc position :banners (banners program position))))
