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

(defn- find-clojure-var
  [ns sym]
  (try
    (not-targeting-cljs
      (require [ns])
      (let [v (ns-resolve ns sym)]
        (when (var? v) v)))
    (catch Throwable t nil)))

(defn find-clojure-variant
  [ns sym]
  (when-let [v (find-clojure-var ns sym)]
    @v))

;; Just to cover a few edge cases...but if these were run into
;; probably you're misusing the aspect constructors anyway.
(def special-forms '#{quote do def fn if let var loop recur try throw new set! .})

(defn- clojure-ns-of
  "The namespace whose Clojure variant defines SYM, as read in CLJS-NS, or nil."
  [sym cljs-ns]
  (let [this-ns (get-in @env/*compiler* [::ana/namespaces cljs-ns])]
    (if-let [ns (some-> (namespace sym) symbol)]
      ;; namespaced, either in full or by alias:
      (if (= 'clojure.core ns) ns (get-in this-ns [:requires ns]))
      (if (anapi/ns-resolve cljs-ns sym)
        ;; it's in the namespace currently being compiled:
        cljs-ns
        ;; presumably it's referred into this ns:
        (get-in this-ns [:uses sym])))))

(defn resolve-from-cljs
  ([sym ignoring] (resolve-from-cljs sym ignoring ana/*cljs-ns*))
  ([sym ignoring cljs-ns]
   (if (or (contains? special-forms sym)
           (contains? ignoring sym))
     sym
     ;; A var can hold nil or false, so test the var, not its value:
     (if-let [v (some-> (clojure-ns-of sym cljs-ns)
                        (find-clojure-var (symbol (name sym))))]
       @v
       (throw
        (ex-info
         (format "%s is not resolvable from location %s."
                 sym cljs-ns)
         {:ns cljs-ns :sym sym}))))))

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
