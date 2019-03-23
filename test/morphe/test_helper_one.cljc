(ns morphe.test-helper-one
  (:require [morphe.core :as m]
            [morphe.functional :as f]
            [morphe.test-helper-two :as two]))

(m/defn ^{::m/aspects [two/logged]}
  logged-fn
  ([x] (* 10 x))
  ([x y] (* x y)))

(f/defn ^{::f/advice [two/flogged]}
  flogged-fn
  ([x] (* 10 x))
  ([x y] (* x y)))
