(ns morphe.test-helper-two
  (:require [morphe.test-helper-three :as three]
            [morphe.core :as m]))

(defn logged
  [fn-def]
  (m/prefix-bodies fn-def
    `(reset! three/some-atom ~&arglist)))

(defn flogged
  [f]
  (fn [& args]
    (reset! three/some-atom (count args))
    (apply f args)))
