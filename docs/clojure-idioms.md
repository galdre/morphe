## Aspect-oriented Clojure patterns

Aspect-oriented programming is fundamentally a focus on being able to write code in such a way that you can separate business logic from other application concerns, and separate the logic of these other concerns from one another.

As a functional language and as a Lisp, Clojure has good tools built-in to tackle this problem, whether via functional composition, higher-order functions, or macros. Let's examine some of those. For a foil, we'll consider the following common use-case: the integration of a [tracing library](https://opentracing.io/).

The requirements are that we need to trace all API endpoints and all major internal operations. It has been determined that the text attached to each trace be of the following format:

```clojure
(format "%s:%s:%s"
        *service-name*
        *function-name*
        *arity*)
```

For convenience of discussion, the function we will use will be very simplistic:

```clojure
(defn sum
  "I add numbers together"
  ([x] x)
  ([x y] (+ x y))
  ([x y & more] (reduce + (sum x y) more)))
```

An example label for a trace might be:

```clojure
"my-service:api.core/sum:[x y & more]"
```

### Functional Composition

For this particular use case, functional composition is not a great fit, but we'll run with it for now, so you can see why.

```clojure
;; Helper function (in a utils namespace, presumably)
(defn make-trace
  [name]
  (let [span (make-span-somehow)]
    [(fn [& args]
       (begin-trace! span
                     (format "my-service:%s:%s"
                             name
                             (count args)))
       args)
     (fn [return]
       (complete-trace! span)
       return)]))


;; Begin business logic
(defn sum*
  ([x] x)
  ([x y] (+ x y))
  ([x y & more] (reduce + (sum x y) more)))

(def sum
  "I add numbers together"
  (let [[begin end] (make-trace "api.core/sum")]
    (comp end
          (partial apply sum*)
          begin)))
```
This is pretty terrible. Not only is the definition of `sum` too complex to make its business logic obvious at first glance, `sum` will no longer have metadata identifying it as a function or allowing IDEs to embed its signature `(([x]) ([x y]) ([x y & more]))` into its docstring. We have also compromised somewhat on the trace's label: instead of displaying the parameters vector of the arity being called, it just counts arguments (arguably better -- but let's stick with the contrived example).

If we want to fix what we can and add some more aspects, we get something like so:

```clojure
(def ^{:arglists '[([x]) ([x y]) ([x y & more])]}
  sum
  "I add numbers together"
  (let [[begin-trace end-trace] (make-trace "api.core/sum")]
    (comp end-trace
          (partial apply sum*)
          (logging :info "calling api.core/sum")
          begin-trace)))
```

The problem here isn't with functional composition, but with the fact that this is simply a bad use-case for it. Let's move on.

### Higher-order functions

This is a much better fit. The tracing helper will accept the function as an argument, and transform it; thereby it can control the execution context for the original function.

```clojure
;; Helper function (in a utils namespace, presumably)
(defn tracing
  [name f]
  (fn [& args]
    (let [span (make-span-somehow)]
      (begin-trace! span
                    (format "my-service:%s:%s"
                            name
                            (count args)))
      (let [return (apply f args)]
        (complete-trace! span)
        return))))

;; Begin business logic
(defn sum*
  ([x] x)
  ([x y] (+ x y))
  ([x y & more] (reduce + (sum x y) more)))

(def sum
  "I add numbers together"
  (tracing "api.core/sum" sum*))
```

It is much clearer here what `sum` is doing. The boilerplate is pretty minimal. We have still lost the `defn`-specific metadata on `sum`, but we can fix that and add more higher-order aspects:

```clojure
(def ^{:arglists '[([x]) ([x y]) ([x y & more])]}
  sum
  "I add numbers together"
  (logging :info "calling api.core/sum"
    (tracing "api.core/sum" sum*)))
```

### `with-` Macros

```clojure
;; Helper macro (in a utils namespace, presumably)
(defmacro with-tracing
  [name params & body]
  `(let [span# (make-span-somehow)]
     (begin-trace! span#
                  ~(format "my-service:%s:%s"
                           name
                           params))
     (let [return# (do ~@body)]
       (complete-trace! span#)
       return#)))

;; Begin business logic
(defn sum
  "I add numbers together"
  ([x]
    (with-tracing "api.core/sum" '[x]
      x))
  ([x y]
    (with-tracing "api.core/sum" '[x y]
      (+ x y)))
  ([x y & more]
    (with-tracing "api.core/sum" '[x y & more]
      (reduce + (sum x y) more))))
```

This is a very common idiom in Clojure code, and it's no wonder. We have done away with the need for `sum*`, we have retained the `defn` metadata on `sum`, and the bureaucratic nonsense is isolate to a single line. We are now trivially conforming to the agreed-upon tracing spec. Adding more aspects is relatively straightforward:

```clojure
(defn sum
  "I add numbers together"
  ([x]
    (with-logging :info "calling api.core/sum"
      (with-tracing "api.core/sum" '[x]
        x)))
  ([x y]
    (with-logging :info "calling api.core/sum"
      (with-tracing "api.core/sum" '[x y]
        (+ x y))))
  ([x y & more]
    (with-logging :info "calling api.core/sum"
      (with-tracing "api.core/sum" '[x y & more]
        (reduce + (sum x y) more)))))
```

That said, in this approach we have given up a nice feature of higher-order functions: brevity. In this example we are repeating every one of our aspect definitions in each arity of the function. If that is so, how is `with-logging` an improvement, even in the slightest, over a one-line log call? Our business logic is now swamped by the code of cross-cutting concerns.

### `defn-*` macros

```clojure
;; Begin helpers (in a utils namespace, presumably)
(defn wrap-body
  [name body params]
  `(let [span# (make-span-somehow)]
     (begin-trace! span#
                   ~(format "my-service:%s:%s"
                            name
                            params))
     (let [return# (do ~@body)]
       (complete-trace! span#)
       return#)))

(defmacro defn-traced
  [name & fdecl]
  ;; Lots of handwaving here -- the parsing in reality can get complicated
  ;; something like clojure.tools.macro/name-with-attributes can help
  (let [parsed (parse-somehow fdecl)
        params (:params parsed)
        bodies (:bodies parsed)
        updated-bodies (map (partial wrap-body (format "%s/%s" *ns* name))
                            bodies
                            params)]
    (unparse-somehow (assoc parsed :bodies updated-bodies))))

;; Begin business logic
(defn-traced sum
  "I add numbers together"
  ([x] x)
  ([x y] (+ x y))
  ([x y & more] (reduce + (sum x y) more)))
```

Once the helper is written, this seems ideal! `sum` will retain its `defn` metadata (assuming the helpers are written correctly), the business logic is now totally unconstrained, and the interruption of the bureaucratic aspect is constrained to a suffix on the `defn`.

But now -- how do you combine these? You can, as I have done, define a function like `defn-traced-and-logged`. This is messy, and simply *cannot* scale when you have many aspects to apply in your system.

### morphe

```clojure
;; Helper function (in a utils namespace, presumably)
(defn traced
  [fn-def]
  (m/alter-bodies fn-def
    `(let [span# (make-span-somehow)]
       (begin-trace! span#
                     ~(format "my-service:%s/%s:%s"
                              &ns
                              &name
                              &params))
       (let [return# (do ~@&body)]
         (complete-trace! span#)
         return#))))

;; Begin business logic:
(m/defn ^{::m/aspects [traced]} sum
  "I add numbers together"
  ([x] x)
  ([x y] (+ x y))
  ([x y & more] (reduce + (sum x y) more)))
```
