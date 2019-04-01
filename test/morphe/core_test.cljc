;; Copyright 2019 Timothy Dean
(ns morphe.core-test
  (:require [morphe.core :as m :include-macros true]
            [morphe.test-helper-one :as one]
            [morphe.test-helper-three :as three]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])))

;;;;;;;;;;;;;
;; ASPECTS ;;

(defn prefix-spied
  [s]
  (fn [fn-def]
    (m/alter-bodies fn-def
      `(let [r# ~@&body]
         (println (str ~s ": " r#))
         r#))))

(defn counted
  [c]
  (fn [fn-def]
    (m/prefix-bodies fn-def
      `(swap! ~c inc))))

(defn anaphored
  [fn-def]
  (m/alter-form fn-def
      `(let [~'anaphore ::anaphore]
         ~&form)))

(defn registered
  [registry]
  (fn [fn-def]
    (m/prefix-form fn-def
      `(swap! ~registry conj '~&name))))

;;;;;;;;;;;;;;;;;;;;
;; BASIC TEST FNS ;;

(m/defn ^{::m/aspects [(prefix-spied "prefix")]}
  alter-bodies-fn
  ([x] (inc x))
  ([x y] (+ x y)))

(def counter (atom 0))
(m/defn ^{::m/aspects [(counted `counter)]}
  prefix-bodies-fn
  ([x] (dec x))
  ([x y] (- x y)))

(m/defn ^{::m/aspects [anaphored]}
  alter-form-fn [x]
  (vector anaphore x))

(def registry (atom #{}))
(m/defn ^{::m/aspects [(registered `registry)]}
  prefix-form-fn [x]
  (pr-str x))

;;;;;;;;;;;;;;;;;
;; BASIC TESTS ;;

(t/deftest alter-bodies-test
  (t/is (= 6 (alter-bodies-fn 5)))
  (t/is (= "prefix: 6\n"
           (with-out-str
             (alter-bodies-fn 5))))
  (t/is (= 10 (alter-bodies-fn 7 3)))
  (t/is (= "prefix: 10\n"
           (with-out-str
             (alter-bodies-fn 7 3)))))

(t/deftest prefix-bodies-test
  (let [count-at-start @counter]
    (t/is (= 2 (prefix-bodies-fn 3)))
    (t/is (= -13 (prefix-bodies-fn 7 20)))
    (t/is (= (+ 2 count-at-start) @counter))))

(t/deftest alter-form-test
  (t/is (= [::anaphore ::goofy]
           (alter-form-fn ::goofy))))

(t/deftest prefix-form-test
  (t/is (= @registry '#{prefix-form-fn})))

;;;;;;;;;;;;;;;
;; ANAPHORES ;;

(defn alter-form-phores
  [fn-def]
  (m/alter-form fn-def
    `(defn ~&name []
       {:ns '~(ns-name &ns)
        :name '~&name
        :env-keys '~&env-keys
        :meta ~&meta
        :form '~&form})))

(let [bizarro 3]
  (m/defn ^{::m/aspects [alter-form-phores]}
    alter-form-anaphores-fn [x] (inc x)))

(defn alter-bodies-phores
  [fn-def]
  (m/alter-bodies fn-def
    `{:params '~&params ;; TODO: remove at 2.0.0
      :arglist '~&arglist
      :body '~&body
      :ns '~(ns-name &ns)
      :name '~&name
      :meta ~&meta
      :env-keys '~&env-keys}))

(let [extremo 10]
  (m/defn ^{::m/aspects [alter-bodies-phores]}
    alter-bodies-anaphores-fn "something" ([x] (inc x)) ([x y] (+ x y))))

(t/deftest anaphores-test
  (t/testing "alter-form anaphores"
    (let [{:keys [ns name env-keys meta form]} (alter-form-anaphores-fn)]
      (t/is (= ns 'morphe.core-test))
      (t/is (= name 'alter-form-anaphores-fn))
      (t/is (= env-keys #?(:clj '#{bizarro}
                           :cljs #{:fn-scope :locals :js-globals :ns :column :line :context})))
      (t/is #?(:clj (= meta '{:arglists ([x])})
               :cljs (= '([x]) (:arglists meta))))
      (t/is (some? form))))
  (t/testing "alter-bodies anaphores"
    (let [result-1 (alter-bodies-anaphores-fn 1)
          result-2 (alter-bodies-anaphores-fn 1 2)]
      (t/is (= (:params result-1)
               (:arglist result-1)
               '[x]))
      (t/is (= (:params result-2)
               (:arglist result-2)
               '[x y]))
      (t/is (= (:body result-1)
               '((inc x))))
      (t/is (= (:body result-2)
               '((+ x y))))
      (t/is (= (:ns result-1)
               (:ns result-2)
               'morphe.core-test))
      (t/is (= (:name result-1)
               (:name result-2)
               'alter-bodies-anaphores-fn))
      (t/is #?(:clj (= (:meta result-1)
                       (:meta result-2)
                       '{:arglists ([x] [x y])
                         :doc "something"})
               :cljs (and
                      (= (:meta result-1)
                         (:meta result-2))
                      (= '([x] [x y])
                         (:arglists (:meta result-1))))))
      (t/is (= (:env-keys result-1)
               (:env-keys result-2)
               #?(:clj '#{extremo}
                  :cljs #{:fn-scope :locals :js-globals :ns :column :line :context}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ORDERING OF COMPOSITION ;;

(defn constantly*
  [x]
  (fn [fn-def]
    (m/alter-bodies fn-def
      x)))

(m/defn ^{::m/aspects [(constantly* 1) (constantly* 2)]}
  always-one [x] (* x 2))

(m/defn ^{::m/aspects [(constantly* 2) (constantly* 1)]}
  always-two [x] (+ x 2))

(t/deftest test-ordering-of-composition
  (t/is (= 1 (always-one 10)))
  (t/is (= 2 (always-two 100))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CROSS NAMESPACE RESOLUTION ;;

(t/deftest test-cross-namespace-resolution
  (t/is (= 100 (one/logged-fn 10)))
  (t/is (= '[10] @three/some-atom))
  (t/is (= 56 (one/logged-fn 8 7)))
  (t/is (= '[8 7] @three/some-atom)))

