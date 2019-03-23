;; Copyright 2016-2019 Timothy Dean
;; Copyright 2017-2018 Workiva Inc.
(ns morphe.core
  (:require [morphe.impl.defn :as impl]
            [morphe.impl.core :as core])
  (:refer-clojure :exclude [defn]))

(defmacro defn
  "Should behave exactly like clojure.core/defn, except:
  You can tag the fn name with aspects:
  `^{:morphe.core/aspects [aspects ...]}`
  The aspects must be functions of one argument that know how to manipulate a
  morphe.core/ParsedFnDef record.

  In implementation, it basically uses the guts of clojure.core/defn to parse
  the definition, representing the parsed form with a ParsedFnDef record,
  which then gets operated on by composable modification fns (aspects).

  The ParsedFnDef record has the following fields:
    :env - the `&env` var inside the `defn` call.
    :wrapper - A single expression not equal to, but representing any code that
               should wrap the `defn` call.
    :namespace - The namespace in which the fn is being interned.
    :fn-name - The symbolic name of the function being defined.
    :metadata - The metadata that was attached to the fn-name.
    :arglists - A sequence of arglists, one for each arity.
    :bodies - A sequence of arity bodies, where each body is a collection of expressions."
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
               [name doc-string? attr-map? ([params*] prepost-map? body)+ attr-map?])}
  [fn-name & fdecl]
  (core/make-def
    {:parser impl/parse-defn
     :->aspects impl/fn-def->aspects
     :compiler impl/fn-def->defn
     :&form &form
     :&env &env
     :def-name fn-name
     :decl fdecl}))

(defmacro alter-form
  "Allows specification of code that would wrap the entire `defn` form.
  Useful mainly for providing a lexical scope (e.g., evaluating the `defn`
  within the body of a `let`). Provides:
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
    * &form - A placeholder for the actual form -- not the form itself.
  NOTA BENE: &form should always be assumed to represent a *single* expression.
  Example: (alter-form fn-def `(binding [*my-var* 3 ~&form)))"
  {:style/indent 1}
  [fn-def expression]
  `(impl/alter-form ~fn-def ~expression))

(defmacro prefix-form
  "Allows the specification of an expression that will be evaluated before
  the `defn` form (presumably for side-effects). Provides:
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
  Example:
  (prefix-form fn-def
               `(println (format \"Compiling %s/%s now.\"
                                 (ns-name &ns)
                                 &name)))"
  {:style/indent 1}
  [fn-def expression]
  `(impl/prefix-form ~fn-def ~expression))

(clojure.core/defn alter-bodies*
  "Takes a fn-def and a function of args [params body] and replaces each body
  in the fn-def with the result of applying the function to the params and
  the body! body should be assumed to be a collection of valid expressions."
  {:style/indent 1}
  [fn-def f]
  (impl/alter-bodies* fn-def f))

(defmacro alter-bodies
  "Allows specification of code that should wrap each body of the `defn`
  form. Provides:
    * &params - The paramaters corresponding to this arity.
    * &body - The collection of expressions in the body of this arity.
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
  NOTA BENE: &body is an *ordered collection* of valid expressions.
  Example:
  (alter-bodies fn-def
                `(binding [*scope* ~[(ns-name &ns) &name &params]]
                   ~@&body))"
  {:style/indent 1}
  [fn-def expression]
  `(impl/alter-bodies ~fn-def ~expression))

(clojure.core/defn prefix-bodies*
  "Takes a fn-def and a function of args [params] and prefixes each body
  in the fn-def with the result of applying the function to the params!"
  {:style/indent 1}
  [fn-def f]
  (impl/prefix-bodies* fn-def f))

(defmacro prefix-bodies
  "Allows the specification of an expression that will be added to the beginning
  of each fn arity (presumably for side-effects). Provides:
    * &params - The paramaters corresponding to this arity.
    * &ns - The namespace in which this fn is being interned
    * &name - The symbol used to name this defn.
    * &meta - The metadata attached to the fn name.
    * &env-keys - The keys of the &env map known to the `defn` macro.
  Example: (prefix-bodies fn-def `(assert (even? 4) \"Math still works.\"))"
  {:style/indent 1}
  [fn-def expression]
  `(impl/prefix-bodies ~fn-def ~expression))
