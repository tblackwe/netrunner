(ns game.core.hosting
  (:require
    [game.core.card :refer [assoc-host-zones corp? condition-counter? get-card rezzed? runner?]]
    [game.core.card-defs :refer [card-def]]
    [game.core.effects :refer [register-constant-effects unregister-constant-effects]]
    [game.core.eid :refer [make-eid]]
    [game.core.engine :refer [register-events unregister-events]]
    [game.core.initializing :refer [card-init]]
    [game.core.update :refer [update! update-hosted!]]
    [game.utils :refer [remove-once]]))

(defn remove-from-host
  "Removes a card from its host."
  [state side {:keys [cid] :as card}]
  (when-let [host-card (get-card state (:host card))]
    (update-hosted! state side (update-in host-card [:hosted] (fn [coll] (remove-once #(= (:cid %) cid) coll))))
    (when-let [hosted-lost (:hosted-lost (card-def host-card))]
      (hosted-lost state side (make-eid state) (get-card state host-card) (dissoc card :host)))))

(defn host
  "Host the target onto the card."
  ([state side card target] (host state side card target nil))
  ([state side card {:keys [zone cid host installed] :as target} {:keys [facedown]}]
   (when (not= cid (:cid card))
     (when installed
       (unregister-events state side target)
       (unregister-constant-effects state side target))
     (doseq [s [:runner :corp]]
       (if host
         (when-let [host-card (get-card state host)]
           (update! state side (update-in host-card [:hosted]
                                          (fn [coll] (remove-once #(= (:cid %) cid) coll)))))
         (swap! state update-in (cons s (vec zone))
                (fn [coll] (remove-once #(= (:cid %) cid) coll)))))
     (swap! state update-in (cons side (vec zone)) (fn [coll] (remove-once #(= (:cid %) cid) coll)))
     (let [card (get-card state card)
           card (assoc-host-zones card)
           c (assoc target :host (dissoc card :hosted)
                           :facedown facedown
                           :zone [:onhost] ;; hosted cards should not be in :discard or :hand etc
                           :previous-zone (:zone target))
           ;; Update any cards hosted by the target, so their :host has the updated zone.
           c (update-in c [:hosted] #(map (fn [h] (assoc h :host c)) %))
           cdef (card-def card)
           tdef (card-def c)]
       (update! state side (update-in card [:hosted] #(conj % c)))
       ;; events should be registered for condition counters
       (when (or (condition-counter? target)
                 (and installed
                      (or (runner? target)
                          (and (corp? target)
                               (rezzed? target)))))
         (register-events state side c)
         (register-constant-effects state side c)
         (when (or (:recurring tdef)
                   (:prevent tdef)
                   (:corp-abilities tdef)
                   (:runner-abilities tdef))
           (card-init state side c {:resolve-effect false
                                    :init-data true})))
       (when (:events tdef)
         (when (and installed (:recurring tdef))
           (unregister-events state side target)
           (register-events state side c)))
       (when-let [hosted-gained (:hosted-gained cdef)]
         (hosted-gained state side (make-eid state) (get-card state card) [c]))
       c))))
