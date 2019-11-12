(ns morphe.test-helper-one
  (:require [morphe.core :as m]
            [morphe.functional :as f]
            [morphe.test-helper-two :as two]))

(m/defn ^{::m/aspects [two/logged]}
  logged-fn
  ([x] (* 10 x))
  ([x y] (* x y)))

^{::m/aspects [two/logged]}
(m/defn logged-fn-2
  ([x] (* 10 x))
  ([x y] (* x y)))

(f/defn ^{::f/advice [two/flogged]} flogged-fn
  ([x] (* 10 x))
  ([x y] (* x y)))

^{::f/advice [two/flogged]}
(f/defn flogged-fn-2
  ([x] (* 10 x))
  ([x y] (* x y)))
