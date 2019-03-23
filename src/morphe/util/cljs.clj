;; Copyright 2019 Timothy Dean
(ns morphe.util.cljs
  (:require [clojure.walk :as walk]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as anapi]
            [cljs.env :as env]))

(defn targeting-cljs?
  "Good method of doing this? I don't know."
  []
  (some? env/*compiler*))

(defmacro not-targeting-cljs
  [& body]
  `(binding [env/*compiler* nil]
     ~@body))

(defn find-clojure-variant
  [ns sym]
  (try
    (not-targeting-cljs
      (require [ns])
      @(ns-resolve ns sym))
    (catch Throwable t nil)))

;; Just to cover a few edge cases...but if these were run into
;; probably you're misusing the aspect constructors anyway.
(def special-forms '#{quote do def fn if let var loop recur try throw new set! .})

(defn resolve-from-cljs
  ([sym ignoring] (resolve-from-cljs sym ignoring ana/*cljs-ns*))
  ([sym ignoring cljs-ns]
   (or (if (or (contains? special-forms sym)
               (contains? ignoring sym))
         sym
         (if (simple-symbol? sym)
           (if-let [anavar (anapi/ns-resolve cljs-ns sym)]
             ;; It's in the namespace currently being compiled:
             (find-clojure-variant cljs-ns sym)
             ;; Presumably it's referred into this ns:
             (when-let [ns (get-in @env/*compiler* [::ana/namespaces
                                                    cljs-ns
                                                    :uses
                                                    sym])]
               (find-clojure-variant ns sym)))
           ;; it's namespaced, either in full or by alias:
           (let [ns (symbol (namespace sym))
                 n (symbol (name sym))
                 ns-full (or ('#{clojure.core} ns)
                             (get-in @env/*compiler* [::ana/namespaces
                                                      cljs-ns
                                                      :requires
                                                      ns]))]
             (find-clojure-variant ns-full n))))
       (throw
        (ex-info
         (format "%s is not resolvable from location %s."
                 sym cljs-ns)
         {:ns cljs-ns :sym sym})))))

(defn walk-all
  [pre post form]
  (walk/walk (partial walk-all pre post) post (pre form)))

(defn resolve-form-entirely
  [form ignoring cljs-ns]
  (let [resolve? (volatile! (list true))]
    (walk-all
     (fn [x]
       (when (and (sequential? x)
                  (= 'quote (first x)))
         (vswap! resolve? conj false))
       x)
     (fn [x]
       (cond (and (sequential? x)
                  (= 'quote (first x)))
             (do (vswap! resolve? pop)
                 x)
             (and (symbol? x)
                  (first @resolve?))
             (resolve-from-cljs x ignoring cljs-ns)
             :else
             x))
     form)))

(defn eval-from-cljs
  [form & {:keys [ignoring cljs-ns]
           :or {ignoring #{}
                cljs-ns ana/*cljs-ns*}}]
  (eval (resolve-form-entirely form ignoring cljs-ns)))
