## Aspect-oriented: a motivating exampler

Gather round, and I shall tell you a fine tale. Once upon a time, there was a simple function in an API, a thin wrapper over more meaty code:

```clojure
(defn do-a-thing [x stuff] (.doThatThing x stuff))
```

But a time came when we wanted to log every time it was called:

```clojure
(defn do-a-thing
  [x stuff]
  (log/trace "calling function: app.api/do-a-thing")
  (.doThatThing x stuff))
```

Of course, we wanted to do the same with many functions in our codebase. This would involve a lot of copying and pasting, except we would also need to remember to change the function name in the string. This would lead to unnecessary code bloat, so we employed a standard and idiomatic solution, one that simultaneously:

 - reduced the amount of bureaucratic code.
 - ensured that we could trivially switch out clojure.tools/logging for another solution in all places at any time.
 - avoided bloating the call stack with unnecessary functional wrapping.

That is, we defined a new `defn`-like macro that would automatically generate the appropriate logging line, including embedding the current namespace and the function name into the logged string. This was an improvement. We could replace the definitions for `do-a-thing` and the many other logged functions with this simple line:

```clojure
(def-logged-fn do-a-thing [x stuff] (.doThatThing x stuff))
```

Soon after, we wanted to know how long each call to `do-a-thing` would take:

```clojure
(def do-a-thing-timer (metrics/timer "Timer for the function: app.api/do-a-thing"))

(metrics/register metrics/DEFAULT
                  ["app.api" "do-a-thing" "timer"]
                  do-a-thing-timer)

(def-logged-fn do-a-thing
  [x stuff]
  (let [context (.time timer)
        result (.doThatThing x stuff)]
    (.stop context)
    result)))
```


At first, all the functions we were logging were also functions we wanted to time, so we wrote a macro to generate all this code and let us go back to something simple, this time saving ourselves a few hundred lines of fragile copy-paste boilerplate:

```clojure
(def-logged-and-timed-fn do-a-thing [x stuff] (.doThatThing x stuff))
```

But alas! Our needs still grew, and several things happened at once. We incorporated tracing into our codebase, and we no longer wished for all our logged functions to be timed, or for all our timed functions to be traced, or all our traced functions to be logged -- we wanted any combination of the three. The optimizations we'd made no longer applied, so our little one-line wrapper was up to twenty-seven lines of ugly nonsense. Even after applying common Clojure-idiomatic mitigation techniques, it was not ideal:

```clojure
(def ^:private do-a-thing-timer
  (metrics/register-a-timer! metrics/DEFAULT :function ["api.api" "do-a-thing"]))
(defn do-a-thing
  [x stuff]
  (log/trace "calling function: app.api/do-a-thing")
  (metrics/with-timer do-a-thing-timer
    (tracing/with-tracing "app.api/do-a-thing:[x stuff]"
      (.doThatThing x stuff))))))
```

Multiply this effect by the number of functions we apply telemetry to across our entire service. It is tedious, full of copy-pasta, and fragile under inevitable future changes (for instance, swapping out a logging library or modifying the metrics implementation). Moreover, all that boilerplate is very distacting if you care about the business logic and nothing else. What it seemed we really *needed* was a full suite of `def-*-fn`s:

 - `def-logged-fn`
 - `def-traced-fn`
 - `def-timed-fn`
 - `def-logged-traced-fn`
 - `def-logged-timed-fn`
 - `def-traced-timed-fn`
 - `def-logged-traced-timed-fn`

That, of course, is ridiculous, and assumes there would be no more cross-cutting concerns to incorporate (consistent error handling across an API? Attaching concurrency counters to specific functions? Applying arbitrarily sophisticated custom memoization? Etc.) With just one additional fourth axis, we'd need 15 of these. For n, 2<sup>n</sup>-1.

The key to solving our problem once and for all was to recognize that these were all _completely independent_ [aspects](https://en.wikipedia.org/wiki/Aspect-oriented_programming) of a function definition. None of the manual transformations depended on any of the others. Thus was born `morphe`. Our one-liner could once again be a one-liner:

```clojure
(m/defn ^{::m/aspects [timed logged traced]} do-a-thing [x stuff] (.doThatThing x stuff))
```

In case you are skeptical as to how this solves any problem in the first place, remember that the best known predictor of bug count in a code base is the *size* of the code base. This library has a number of potential applications, but the easiest all involve removing boilerplate.

I no longer have exact numbers, but at one point I estimated in a Clojure project a few tens of thousands of lines large that the application of cross-cutting aspects to ~2% of my project's functions resulted in a ~25% LOC reduction, not to mention greater programmer consistency in adhering to those cross-cutting concerns.
