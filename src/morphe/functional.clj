;; Copyright 2019 Timothy Dean
(ns morphe.functional
  (:require [morphe.impl.defn :as impl])
  (:refer-clojure :exclude [defn]))

(defn- wrap-body
  [aspect]
  (fn [arglist body]
    `((let [wrapper# ~aspect]
        (wrapper# (do ~@body))))))

(defmacro defn
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
               [name doc-string? attr-map? ([params*] prepost-map? body)+ attr-map?])}
  [fn-name & fdecl]
  (let [parsed (apply impl/parse-defn &form &env fn-name fdecl)
        hofs (-> fn-name meta ::advice)]
    (-> parsed
        (update :metadata dissoc ::advice)
        (assoc :hofs hofs)
        (impl/fn-def->defn))))


