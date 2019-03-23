;; Copyright 2016-2019 Timothy Dean
;; Copyright 2017-2018 Workiva Inc.
(ns morphe.impl.core
  (:require [morphe.util.cljs :as cljs]))

(defn resolve-clj-aspect
  [aspect]
  (cond (symbol? aspect)
        (ns-resolve *ns* aspect)
        (list? aspect)
        (eval aspect)))

(defn resolve-cljs-aspect
  [aspect name host-ns]
  (cond (symbol? aspect)
        (cljs/resolve-from-cljs aspect #{name} (ns-name host-ns))
        (list? aspect)
        (cljs/eval-from-cljs aspect
                             :ignoring #{name}
                             :cljs-ns (ns-name host-ns))))

(defn resolve-aspect
  [aspect name host-ns]
  (if (cljs/targeting-cljs?)
    (resolve-cljs-aspect aspect name host-ns)
    (resolve-clj-aspect aspect)))

(defn- apply-aspect
  [def-name host-ns morphe aspect]
  (try (let [aspect (resolve-aspect aspect def-name host-ns)]
         (aspect morphe))
    (catch Throwable t
      (throw
       (ex-info "An error was encountered applying aspect to form."
                {:form morphe
                 :aspect aspect
                 :name def-name}
                t)))))

(defn make-def
  [{:keys [parser ->aspects compiler &form &env def-name decl]}]
  (let [parsed (apply parser &form &env def-name decl)
        host-ns (:namespace parsed)
        aspects (->aspects parsed)
        processed (reduce (partial apply-aspect def-name host-ns)
                          parsed
                          (reverse aspects))]
    (compiler processed)))
