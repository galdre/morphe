(ns morphe.test-macros)

(defmacro defn-with-seq-aspects
  "Expands to morphe.core/defn with each aspect form rebuilt with cons, so
  that it is a seq but not a list, as a macro that builds aspect forms might
  produce."
  [fn-name aspects & fdecl]
  `(morphe.core/defn ~(vary-meta fn-name assoc :morphe.core/aspects
                                 (mapv #(cons (first %) (rest %)) aspects))
     ~@fdecl))
