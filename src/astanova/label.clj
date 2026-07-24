(ns astanova.label
  "Label management: propagation, analysis, and utilities.
   Provides thread-level label propagation and related label operations."
  (:require [datalevin.core :as d]
            [clojure.set :as set]))

;; ─── Thread-level label propagation ──────────────────────────────
;;
;; Strategy: given a label, find all threads that contain at least one
;; email with that label, then propagate the label to every other email
;; in those threads that doesn't already carry it.

(defn propagate-label-to-threads
  "Propagate `label` to all emails in threads that already contain it.

   For each thread that has at least one email tagged with `label`, every
   other email in that thread (that doesn't already have `label`) receives it.

   Returns a map with:
     :threads       — number of distinct threads affected
     :total-emails  — total emails across those threads
     :updated       — number of emails that received the label

   Threadless emails (nil thread-id) are silently skipped."
  [conn label]
  (let [db (d/db conn)
        ;; 1. Find all thread-ids where at least one email has this label
        thread-ids (d/q '[:find [?thread ...]
                          :in $ ?label
                          :where [?e :email/labels ?label]
                                 [?e :email/thread-id ?thread]]
                        db label)
        _ (println (str "  Found " (count thread-ids) " threads with label \"" label "\""))


        ;; 2. Get all emails in those threads
        emails (when (seq thread-ids)
                 (d/q '[:find [(pull ?e [:db/id :email/labels]) ...]
                        :in $ [?thread ...]
                        :where [?e :email/thread-id ?thread]]
                       db (vec thread-ids)))

        ;; 3. Filter to emails that don't already have this label
        to-update (remove #(some #{(name label)} (map str (:email/labels %))) emails)
        _ (println (str "  " (count to-update) " emails to update out of "
                        (count emails) " total in those threads"))]

    ;; 4. Batch transact the label to each
    (when (seq to-update)
      (d/transact! conn
                   (mapv (fn [e]
                           (let [existing (set (map str (:email/labels e)))]
                             {:db/id (:db/id e)
                              :email/labels (vec (conj existing (name label)))}))
                         to-update)))

    {:threads (count thread-ids)
     :total-emails (count emails)
     :updated (count to-update)}))


;; ─── Preview (dry-run) ───────────────────────────────────────────

(defn preview-propagation
  "Dry-run: show what `propagate-label-to-threads` would do without
   actually transacting anything. Returns the same stats map."
  [db label]
  (let [thread-ids (d/q '[:find [?thread ...]
                          :in $ ?label
                          :where [?e :email/labels ?label]
                                 [?e :email/thread-id ?thread]]
                        db label)
        emails (when (seq thread-ids)
                 (d/q '[:find [(pull ?e [:db/id :email/labels :email/subject]) ...]
                        :in $ [?thread ...]
                        :where [?e :email/thread-id ?thread]]
                       db (vec thread-ids)))
        to-update (remove #(some #{(name label)} (map str (:email/labels %))) emails)]
    {:threads (count thread-ids)
     :total-emails (count emails)
     :updated (count to-update)
     :previews (mapv (fn [e]
                       {:db/id (:db/id e)
                        :subject (:email/subject e)
                        :existing-labels (:email/labels e)})
                     (take 10 to-update))}))


;; ─── Thread label diagnostic ────────────────────────────────────

(defn inspect-thread-labels
  "Show all emails in a thread with their labels, highlighting label gaps.

   Returns a map with:
     :thread-id        — the thread under inspection
     :email-count      — number of emails in the thread
     :emails           — list of emails with subject, from, date, labels
     :all-labels       — union of all labels across the thread
     :common-labels    — labels present on EVERY email in the thread
     :sparse-labels    — labels present on SOME but not all emails

   If `label` is provided, only checks coverage for that specific label."
  [db thread-id & {:keys [label]}]
  (let [emails (d/q '[:find [(pull ?e [:db/id :email/subject :email/from :email/date :email/labels]) ...]
                       :in $ ?thread
                       :where [?e :email/thread-id ?thread]
                              [?e :email/date ?d]]
                     db thread-id)
        sorted (sort-by :email/date emails)
        all-labels (apply set/union (map (comp set :email/labels) sorted))
        label-sets (map (comp set :email/labels) sorted)
        common-labels (apply set/intersection label-sets)
        sparse-labels (clojure.set/difference all-labels common-labels)]
    (cond-> {:thread-id thread-id
             :email-count (count sorted)
             :emails (mapv (fn [e]
                             {:subject (:email/subject e)
                              :from (:email/from e)
                              :date (:email/date e)
                              :labels (:email/labels e)})
                           sorted)
             :all-labels (vec (sort all-labels))
             :common-labels (vec (sort common-labels))
             :sparse-labels (vec (sort sparse-labels))}
      label (assoc :label-coverage
                   (let [covered (count (filter #(contains? % label) label-sets))]
                     {:label label
                      :present covered
                      :total (count label-sets)
                      :missing (- (count label-sets) covered)})))))


;; ─── Label co-occurrence ────────────────────────────────────────

(defn fetch-linked-labels
  "Find all labels that co-occur with the given label across emails.

   For a given `label`, finds every email that has it, then collects all
   other labels on those same emails. Returns a list of [label, count]
   sorted by count descending, excluding the queried label itself.

   Example:
     (fetch-linked-labels db \"trading\")
     ;; => ([[\"Important\" 45] [\"Sent\" 38] [\"Inbox\" 30] ...])"
  [db label]
  (let [;; Find all emails that have this label
        eids (d/q '[:find [?e ...]
                    :in $ ?label
                    :where [?e :email/labels ?label]]
                  db label)
        ;; Collect counts of each co-occurring label
        result (when (seq eids)
                 (d/q '[:find ?l (count ?e)
                        :in $ [?e ...] ?label
                        :where [?e :email/labels ?l]
                               [(not= ?l ?label)]]
                       db (vec eids) label))]
    (->> result (sort-by second >) vec)))