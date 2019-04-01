;; Copyright (c) Rich Hickey
;; Copyright 2016-2019 Timothy Dean
;; Copyright 2017-2018 Workiva Inc.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.
(ns morphe.impl.defn
  #?(:clj (:require [clojure.tools.macro :refer [symbol-macrolet]])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFN PARSER & COMPILER ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord ParsedFnDef [env wrapper namespace metadata fn-name arglists bodies aspects hofs])
(def fn-def->aspects :aspects)

;; The following was ripped out of clojure.core. It was a private fn,
;; and I needed it.
#?(:clj
   (defn- ^{:dynamic true} assert-valid-fdecl
     "A good fdecl looks like (([a] ...) ([a b] ...)) near the end of defn."
     [fdecl]
     (when (empty? fdecl) (throw (IllegalArgumentException.
                                  "Parameter declaration missing")))
     (let [argdecls (map
                     #(if (seq? %)
                        (first %)
                        (throw (IllegalArgumentException.
                                (if (seq? (first fdecl))
                                  (format "Invalid signature \"%s\" should be a list" %)
                                  (format "Parameter declartion \"%s\" should be a vector" %)))))
                     fdecl)
           bad-args (seq (remove #(vector? %) argdecls))]
       (when bad-args
         (throw (IllegalArgumentException. (str "Parameter declaration \"" (first bad-args)
                                                "\" should be a vector")))))))

;; The following was ripped out of clojure.core.
#?(:clj
   (defn- sigs
     [fdecl]
     (assert-valid-fdecl fdecl)
     (let [asig (fn [fdecl]
                  (let [arglist (first fdecl) ;;elide implicit macro args
                        arglist (if (clojure.lang.Util/equals '&form (first arglist))
                                  (clojure.lang.RT/subvec arglist 2 (clojure.lang.RT/count arglist))
                                  arglist)
                        body (next fdecl)]
                    (if-not (map? (first body))
                      arglist
                      (if-not (next body)
                        arglist
                        (with-meta arglist (conj (if (meta arglist) (meta arglist) {}) (first body)))))))
           resolve-tag (fn [argvec]
                         (let [m (meta argvec)
                               ^clojure.lang.Symbol tag (:tag m)]
                           (if-not (instance? clojure.lang.Symbol tag)
                             argvec
                             (if-not (clojure.lang.Util/equiv (.indexOf (.getName tag) ".") -1)
                               argvec
                               (if-not (clojure.lang.Util/equals nil
                                                                 (clojure.lang.Compiler$HostExpr/maybeSpecialTag tag))
                                 argvec
                                 (let [c (clojure.lang.Compiler$HostExpr/maybeClass tag false)]
                                   (if-not c
                                     argvec
                                     (with-meta argvec (assoc m :tag (clojure.lang.Symbol/intern (.getName c)))))))))))]
       (if (seq? (first fdecl))
         (loop [ret [] fdecls fdecl]
           (if fdecls
             (recur (conj ret (resolve-tag (asig (first fdecls)))) (next fdecls))
             (seq ret)))
         (list (resolve-tag (asig fdecl)))))))

;; The following is taken straight from clojure.core/defn (sense a
;; theme?), with modifications to output a ParsedFnDef record instead of
;; defn form.
#?(:clj
   (defn parse-defn
     [&form &env name & fdecl]
     (when-not (symbol? name)
       (throw (IllegalArgumentException. "The first argument to a def form must be a symbol.")))
     (let [m (if (string? (first fdecl))
               {:doc (first fdecl)}
               {})
           fdecl (if (string? (first fdecl))
                   (next fdecl)
                   fdecl)
           m (if (map? (first fdecl))
               (conj m (first fdecl))
               m)
           fdecl (if (map? (first fdecl))
                   (next fdecl)
                   fdecl)
           fdecl (if (vector? (first fdecl))
                   (list fdecl)
                   fdecl)
           m (if (map? (last fdecl))
               (conj m (last fdecl))
               m)
           fdecl (if (map? (last fdecl))
                   (butlast fdecl)
                   fdecl)
           m (conj {:arglists (list 'quote (sigs fdecl))} m)
           m (let [inline (:inline m)
                   ifn (first inline)
                   iname (second inline)]
               (if (and (= 'fn ifn) (not (symbol? iname)))
                 (->> (next inline)
                      (cons (clojure.lang.Symbol/intern (.concat (.getName ^clojure.lang.Symbol name) "__inliner")))
                      (cons ifn)
                      (assoc m :inline))
                 m))
           m (conj (if (meta name) (meta name) {}) m)
           params (map first fdecl)
           bodies (map rest fdecl)]
       (map->ParsedFnDef {:wrapper `(do ::form)
                          :env &env
                          :namespace *ns*
                          :metadata (dissoc m :morphe.core/aspects)
                          :fn-name name
                          :arglists params
                          :bodies bodies
                          :aspects (:morphe.core/aspects m)}))))

#?(:clj
   (defn- hof-it [expr hof] `(~hof ~expr)))

#?(:clj
   (defn fn-def->defn
     [fn-def]
     ;; vvv some more bits forked out of clojure.core/defn.
     (let [fn-def (cond-> fn-def
                    (:hofs fn-def) (assoc-in fn-def [:metadata :arglists '([& args])]))
           fn-expr (with-meta (cons `fn (map cons
                                             (:arglists fn-def)
                                             (:bodies fn-def)))
                     {:rettag (:tag (:metadata fn-def))})
           definiens (if-let [hofs (:hofs fn-def)]
                       (reduce hof-it fn-expr (reverse hofs))
                       fn-expr)
           definition (list `def (with-meta (:fn-name fn-def) (:metadata fn-def))
                            definiens)]
       (if (not= (:wrapper fn-def) `(do ::form))
         ;; vvv replaces the ::definition with the defn form, inside the wrapper form. Wrapped in do.
         (clojure.walk/postwalk-replace {::form definition}
                                        (:wrapper fn-def))
         definition))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONVENIENCE UTILITIES ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn- ->anaphoric-binding
     ([fn-def anaphore]
      (assert (not (or (= anaphore '&body)
                       (= anaphore '&params) ;; TODO: remove at 2.0.0
                       (= anaphore '&arglist))))
      (->anaphoric-binding fn-def nil nil anaphore))
     ([fn-def params anaphore]
      (assert (not (= anaphore '&body)))
      (->anaphoric-binding fn-def params nil anaphore))
     ([fn-def params body anaphore]
      [anaphore
       (condp = anaphore
         '&body body
         '&params params ;; TODO: remove at 2.0.0
         '&arglist params
         '&ns `(:namespace ~fn-def)
         '&name `(:fn-name ~fn-def)
         '&meta `(:metadata ~fn-def)
         '&form `(:wrapper ~fn-def)
         '&env-keys `(set (keys (:env ~fn-def))))])))

#?(:clj
   (defn- anaphoric-scope
     ([sym:fn-def anaphores expression]
      `(symbol-macrolet ~(into []
                               (mapcat (partial ->anaphoric-binding sym:fn-def))
                               anaphores)
                        ~expression))
     ([sym:fn-def sym:params anaphores expression]
      `(symbol-macrolet ~(into []
                               (mapcat (partial ->anaphoric-binding sym:fn-def sym:params))
                               anaphores)
                        ~expression))
     ([sym:fn-def sym:params sym:body anaphores expression]
      `(symbol-macrolet ~(into []
                               (mapcat (partial ->anaphoric-binding sym:fn-def sym:params sym:body))
                               anaphores)
                        ~expression))))

#?(:clj
   (defmacro alter-form
     [fn-def expression]
     (let [sym:fn-def (gensym 'fn-def)
           expression (->> expression
                           (anaphoric-scope sym:fn-def '#{&ns &name &env-keys &meta &form}))]
       `(let [~sym:fn-def ~fn-def]
          (assoc ~sym:fn-def :wrapper ~expression)))))

#?(:clj
   (defmacro prefix-form
     [fn-def expression]
     (let [sym:fn-def (gensym 'fn-def)
           expression (->> expression
                           (anaphoric-scope sym:fn-def '#{&ns &name &env-keys &meta}))]
       `(let [~sym:fn-def ~fn-def]
          (alter-form ~sym:fn-def
                      `(do ~~expression
                           ~~'&form))))))

(defn alter-bodies*
  [fn-def f]
  (update fn-def
          :bodies
          (fn [bodies]
            (map f (:arglists fn-def) bodies))))

#?(:clj
   (defmacro alter-bodies
     [fn-def expression]
     (let [sym:params (gensym 'params)
           sym:body (gensym 'body)
           sym:fn-def (gensym 'fn-def)
           anaphores '#{&params ;; TODO: remove at 2.0.0
                        &arglist &body &ns &name &meta &env-keys}
           expression-fn `(fn ~[sym:params sym:body]
                            (list ~(->> expression
                                        (anaphoric-scope sym:fn-def sym:params sym:body anaphores))))]
       `(let [~sym:fn-def ~fn-def]
          (alter-bodies* ~sym:fn-def ~expression-fn)))))

(defn prefix-bodies*
  [fn-def f]
  (update fn-def
          :bodies
          (fn [bodies]
            (map cons
                 (map f (:arglists fn-def))
                 bodies))))

#?(:clj
   (defmacro prefix-bodies
     [fn-def expression]
     (let [sym:params (gensym 'params)
           sym:fn-def (gensym 'fn-def)
           anaphores '#{&params ;; TODO: remove at 2.0.0
                        &arglist &ns &name &meta &env-keys}
           expression-fn `(fn ~[sym:params]
                            ~(->> expression
                                  (anaphoric-scope sym:fn-def sym:params anaphores)))]
       `(let [~sym:fn-def ~fn-def]
          (prefix-bodies* ~sym:fn-def ~expression-fn)))))
