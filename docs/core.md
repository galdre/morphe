# `morphe.core`

## How does it work?

In Clojure's grammar, `defn` forms have many optional and variadic terms (metadata, docstrings, single-arity vs. multi-arity structure, etc.). The main work of [`defn`](https://github.com/clojure/clojure/blob/clojure-1.9.0-alpha14/src/clj/clojure/core.clj#L283) is to parse the body of the macro, extracting all these terms; afterward its job is simply to write these out in a more restricted form to be compiled.

`morphe` works by forking `clojure.core/defn`, splitting it into these two fundamental components: the parser and writer. The forked parser outputs a `FnDef` record which is consumed by the writer. But between being parsed and being compiled, this record can easily be examined and/or modified by *aspect-defining* functions.

```Clojure
#_=> (defn spied [fn-def] (m/alter-bodies fn-def `(let [r# (do ~@&body)] (println r#) r#)))
#_=> (m/defn ^{::m/aspects [spied]} foo ([x] (inc x)) ([x y] (+ x y)))
#_=> (= 2 (foo 1))
;; prints: "2"
true
#_=> (= 5 (foo 2 3))
;; prints "5"
true
```

In the example above, `^{::m/aspects [...]}` tells Clojure's [reader](https://clojure.org/reference/reader) to attach a map of metadata to a symbol. `morphe.core/defn` parses the function definition as normal, then examines the symbol's metadata to determine which aspects it is tagged with. This is all standard Clojure stuff. As an aside, an alternate style is available that is preferable in a source file:

```clojure
;; The metadata map immediately precedes the `defn` block.

^{::m/aspects [spied]}
(m/defn foo ([x] (inc x)) ([x y] (+ x y)))
```

In any case, Morphe then exploits the fact that the compiler itself is a Clojure process, and reduces the tagged aspect functions over the parsed form (in this case, just `spied`). Once all such tags have been applied, the result is passed along to the writer, just as `clojure.core/defn` implicitly would have done.

It is fairly straightforward to modify the `FnDef` record directly. But `morphe.core` provides a number of conveniences to make writing common aspect transformations as simple as possible; examples include wrapping the whole definition (perhaps in the body of a `let`), or prefixing every body of the function (perhaps with generated log statements). Let us consider in more depth the definition of a simple trace-level logging transformation:

```clojure
(defn traced
  "Inserts a log/trace call as the first item in the fn body, recording
  the fully-qualified function name and the arity called."
  [fn-def]
  (m/prefix-bodies fn-def
                   `(log/trace "calling function: "
                               ~(format "%s/%s:%s" (ns-name &ns) &name &arglist)))))
```
This is equivalent to the following method, which does not use any convenience functions and instead modifies the `FnDef` record directly:

```clojure
(defn traced
  "Inserts a log/trace call as the first item in the fn body, recording
  the fully-qualified function name and the arity called."
  [fn-def]
  (let [namespaced-fn-name (format "%s/$s"
                                   (str (ns-name (:namespace fn-def)))
                                   (str (:fn-name fn-def)))]
    (assoc fn-def :bodies
           (for [[body args] (apply map list ((juxt :bodies :arglists) fn-def))]
             (conj body `(log/trace ~(format "calling function: %s:%s"
                                             namespaced-fn-name
                                             args)))))))
```

## Writing macros

If you have never written [Clojure macros](https://clojure.org/reference/macros), there are a very few tricky things to this process. The community is helpful, and help is also available in [Clojure for the Brave and True](https://www.braveclojure.com/writing-macros/) and of course Paul Graham's [On Lisp](https://www.amazon.com/Lisp-Advanced-Techniques-Common/dp/0130305529). If you want to dive very deep and have a strong stomach for ebullient superlatives, I recommend [Let over Lambda](https://letoverlambda.com/).

## `morphe.core` utilities

Clojure's `defmacro` is an [anaphoric macro](https://en.wikipedia.org/wiki/Anaphoric_macro). Code inside `defmacro` has access to two special variables, `&env` and `&form`. `&env` is, from the documentation, "a map of local bindings at the point of macro expansion. The env map is from symbols to objects holding compiler information about that binding." `&form` is "the actual form (as data) that is being invoked."

Morphe's convenience utilities are also anaphoric macros. Depending on the utility, some of the following variables are available:

- `&ns`: the namespace in which the aspect-modified function is being defined.
- `&name`: the unqualified name given to the function.
- `&env-keys`: the *keyset* of the `&env` map as seen by the `morphe.core/defn` macro itself (i.e., set of symbols bound in a local scope)
- `&meta`: the metadata with which the function has been tagged
- `&arglist`: the paramaters vector for *a particular* arity of the function.
- `&body`: the collection of expression(s) constituting *a particular* arity of the function.
- `&form`: an *uninspectable* representation of the collection of expressions for the entire function declaration; useful to wrap the whole `defn` with a lexical scope.

#### `defn`

A drop-in replacement for Clojure's `defn`. In the simple case, the two should be indistinguishable. But you can tag the fn-name with metadata, under the keyword `:morphe.core/aspects`, to trigger the application of aspects.

#### `prefix-form: [fn-def expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, and `&meta`.

This will prefix the entire form with the provided expression. Example:

```
(prefix-form
  fn-def
  `(def gets-defined-first 3))
```

#### `alter-form: [fn-def expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, `&meta`, and `&form`.

This will wrap the entire form, with the form's location in the code specified by `&form`. `&form` must be assumed to be a *single valid expression*, not a sequence of expressions.

Example:

```clojure
(alter-form fn-def
           `(binding [*my-var* 3] &form))
```

#### `prefix-bodies: [fn-def expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, `&meta`, and `&arglist`.

This will prefix each body of the function with the provided expression. `&arglist` will evaluate to the parameter list corresponding to each body.

Example:

```clojure
(prefix-bodies fn-def
               `(assert (even? 4)
                        (format "Math still works in the %s arity."
                                '~&arglist)))
```

#### `alter-bodies: [fn-def expression]`

Anaphoric macro, providing `&ns`, `&name`, `&env-keys`, `&meta`, `&arglist`, and `&body`.

For each arity of the function, this *replaces* the clauses with the given expression; `&arglist` and `&body` are bound appropriately for each arity, and `&body` is assumed to be a *sequence of valid expressions*, not a single valid expression. Typically used for wrapping each body somehow.

Example:

```
(alter-bodies fn-def
             `(binding [*some-scope* ~{:ns &ns,
                                       :sym &name,
                                       :arity &arglist}]
                ~@&body))
```

## Examples

### Logging/tracing call sites

Let's say you want to log every time a method is called, along with the arity. Usually you want this to be at the warn level, but sometimes you want debug or info.

```clojure
(defn logged
  "Higher order fn, returning an aspect fn. Inserts a log call as the
   first item in each fn body."
  ([] (logged :warn))
  ([level]
   (fn [fn-def]
     (d/prefix-bodies
       fn-def
       `(log/log ~level
                 ~(format "Logging at %s level: Entering fn %s/%s:%s."
                          level
                          &ns
                          &name
                          &arglist))))))

;; Now let's use it:

^{::m/aspects [(logged :debug)]}
(m/defn my-logged-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))

;; This expands to:
(defn my-logged-fn
  ([x]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x].")
    x)
  ([x y]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x y].")
    (+ x y))
  ([x y z]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x y z].")
    (+ x y z))
  ([x y z & more]
    (log/log :debug "Logging at :debug level: Entering fn my-ns/my-logged-fn:[x y z & more].")
    (apply + x y z more)))
```

### Tagging for metrics

Now suppose you want to time a function.

```clojure
(defn timed
  "Creates a lexical scope for the defn with a codehale timer defined, which is
  then used to time each function call."
  [fn-def]
  (let [timer (gensym 'timer)]
    (-> fn-def
        (d/alter-form `(let [~timer (metrics/timer ~(format "Timer for the function: %s"
                                                           (symbol (str &ns) &name)))]
                        (metrics/register metrics/DEFAULT ~[(str &ns) (str &name) "timer"])
                        ~&form))
        (d/alter-bodies `(metrics/with-timer ~timer ~@&body)))))

;; Let's use it:

^{::m/aspects [timed]}
(m/defn my-timed-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))

;; This expands to:
(let [timer7068 (metrics/timer "Timer for the function: my-ns/my-timed-fn")]
  (metrics/register metrics/DEFAULT ["my-ns" "my-timed-fn" "timer"])
  (defn my-timed-fn
    ([x]
      (metrics/with-timer timer7068
        x))
    ([x y]
      (metrics/with-timer timer7068
        (+ x y)))
    ([x y z]
      (metrics/with-timer timer7068
        (+ x y z)))
    ([x y z & more]
      (metrics/with-timer timer7068
        (apply + x y z more))))
```

### Mix & match

Let's do both.

```clojure
^{::m/aspects [timed (logged :debug)]}
(m/defn my-amazing-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))

;; This expands to:
(let [timer7068 (metrics/timer "Timer for the function: my-ns/my-amazing-fn")]
  (metrics/register metrics/DEFAULT ["my-ns" "my-amazing-fn" "timer"])
  (defn my-amazing-fn
    ([x]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x].")
        x))
    ([x y]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y].")
        (+ x y)))
    ([x y z]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z].")
        (+ x y z)))
    ([x y z & more]
      (metrics/with-timer timer7068
        (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z & more].")
        (apply + x y z more))))
```

Aspects are applied in composition order (right to left). Change the aspects' order in the tagged vector, and you change the order of application:

```clojure
^{::m/aspects [(logged :debug) timed]}
(m/defn my-amazing-fn
  ([x] x)
  ([x y] (+ x y))
  ([x y z] (+ x y z))
  ([x y z & more] (apply + x y z more)))

;; This expands to:
(let [timer7068 (metrics/timer "Timer for the function: my-ns/my-amazing-fn")]
  (metrics/register metrics/DEFAULT ["my-ns" "my-amazing-fn" "timer"])
  (defn my-amazing-fn
    ([x]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x].")
      (metrics/with-timer timer7068
        x))
    ([x y]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y].")
      (metrics/with-timer timer7068
        (+ x y)))
    ([x y z]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z].")
      (metrics/with-timer timer7068
        (+ x y z)))
    ([x y z & more]
      (log/log :debug "Logging at :debug level: Entering fn my-ns/my-amazing-fn:[x y z & more].")
      (metrics/with-timer timer7068
        (apply + x y z more))))
```

### Macrotic Transformations

In the examples so far, similar effects could be achieved via (possibly clunky) functional composition (see morphe's utilities for such [here](functional.md)). There are some limitations: the automatic exposure of `&name` or `&arglist` is not possible via purely functional means. But the fact that we are operating on the function's *code* rather than the function itself does allow even more interesting transformations one could not effect purely functionally. Consider this funny little example I once used in practice (observe carefully how some of the code gets restructured in the second arity):

```clojure
^{::m/aspects [(synchronize-on state #{pojo-1 pojo-2})]}
(m/defn safely-update-then-calculate
  ([state pojo-1]
    (when-let [x (.inspect pojo-1)]
      (.update pojo-1 (:one @state))
      (expensive-calculation x))
  ([state pojo-1 pojo-2]
    (when-let [x (.inspect pojo-1)]
      (.update pojo-1 (:one @state))
      (.update pojo-2 (:two @state))
      (expensive-calculation x))))

;; expands to:
(defn safely-update-then-calculate
  ([state pojo-1]
    (when-let [x (locking state
                   (when-let [x46735 (.inspect pojo-1)]
                     (.update pojo-1 (:one @state))
                     x46735))]
      (expensive-calculation x)))
  ([state pojo-1 pojo-2]
    (when-let [x (locking state
                   (when-let [x46736 (.inspect pojo-1)]
                     (.update pojo-1 (:one @state))
                     (.update pojo-2 (:two @state))
                     x46736))]
       (expensive-calculation x))))
```
