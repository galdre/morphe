;; Copyright 2019 Timothy Dean
(ns morphe.functional-test
  (:require [morphe.functional :as f :include-macros true]
            [morphe.test-helper-one :as one]
            [morphe.test-helper-three :as three]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])))

;;;;;;;;;;;;
;; ADVICE ;;

(defn spied
  [s]
  (fn [f]
    (fn [& args]
      (let [r (apply f args)]
        (println (str s ": " r))
        r))))

(defn counted
  [c]
  (fn [f]
    (fn [& args]
      (swap! c inc)
      (apply f args))))

(defn partialed
  [g]
  (fn [f]
    (fn [& args]
      (apply f (g) args))))

(defn registered
  [registry reg-name]
  (fn [f]
    (swap! registry assoc reg-name f)
    (fn [& args]
      (apply f args))))

;;;;;;;;;;;;;;;;;;;;
;; BASIC TEST FNS ;;

(f/defn ^{::f/advice [(spied "prefix")]}
  spied-fn
  ([x] (inc x))
  ([x y] (+ x y)))

(def counter (atom 0))
(f/defn ^{::f/advice [(counted counter)]}
  counted-fn
  ([x] (dec x))
  ([x y] (- x y)))

(defn ten [] (+ 3 7))
(f/defn ^{::f/advice [(partialed ten)]}
  partialed-fn
  ([x] (inc x))
  ([x y] (+ x y)))

(def registry (atom {}))
(f/defn ^{::f/advice [(registered registry ::registered-fn)]}
  registered-fn
  ([x] (pr-str x)))

;;;;;;;;;;;;;;;;;
;; BASIC TESTS ;;

(t/deftest spied-test
  (t/is (= 6 (spied-fn 5)))
  (t/is (= "prefix: 6\n"
           (with-out-str
             (spied-fn 5))))
  (t/is (= 10 (spied-fn 7 3)))
  (t/is (= "prefix: 10\n"
           (with-out-str
             (spied-fn 7 3)))))

(t/deftest counted-test
  (let [count-at-start @counter]
    (t/is (= 9 (counted-fn 10)))
    (t/is (= 5 (counted-fn 21 16)))
    (t/is (= (+ 2 count-at-start) @counter))))

(t/deftest partialed-test
  (t/is (= 11 (partialed-fn)))
  (t/is (= 16 (partialed-fn 6))))

(t/deftest registered-test
  (t/is (= (contains? @registry ::registered-fn)))
  (t/is (= (registered-fn [1 "hi" ::foo])
           ((get @registry ::registered-fn) [1 "hi" ::foo]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ORDERING OF COMPOSITION ;;

(defn constantly*
  [x]
  (fn [f]
    (constantly x)))

(f/defn ^{::f/advice [(constantly* 1) (constantly* 2)]}
  always-one [x] (* x 2))

(f/defn ^{::f/advice [(constantly* 2) (constantly* 1)]}
  always-two [x] (+ x 2))

(t/deftest test-ordering-of-composition
  (t/is (= 1 (always-one 10)))
  (t/is (= 2 (always-two 100))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CROSS NAMESPACE RESOLUTION ;;

(t/deftest test-cross-namespace-resolution
  (t/is (= 100 (one/flogged-fn 10)))
  (t/is (= 1 @three/some-atom))
  (t/is (= 56 (one/flogged-fn 8 7)))
  (t/is (= 2 @three/some-atom)))
