# Morphe (μορφή)

> "Thus if we regard objects independently of their attributes and investigate any aspect of them as so regarded, we shall not be guilty of any error on this account, any more than when we draw a diagram on the ground and say that a line is a foot long when it is not; because the error is not in the premises. The best way to conduct an investigation in every case is to take that which does not exist in separation and consider it separately; which is just what the arithmetician or the geometrician does."
>
> Aristotle, *Metaphysics*

[![Clojars Project](https://img.shields.io/clojars/v/galdre/morphe.svg)](https://clojars.org/galdre/morphe)

Morphe is a Clojure(script) library for writing [aspect-oriented code](https://en.wikipedia.org/wiki/Aspect-oriented_programming) without compromising on other language features (such as function metadata), by fully exploiting Lisp's "code as data" philosophy.

If you are new to the concept of AOP, read [this walkthrough](docs/rationale.md) of a motivating example. If, on the other hand, you wonder when or why you might want to use this library rather than any number of perfectly reasonable and common Clojure idioms, read [this comparison](docs/clojure-idioms.md). Unlike the excellent [Robert Hooke](https://github.com/technomancy/robert-hooke) library, morphe does not enable dynamic modifications to functions, attaching and unattaching advice; here, aspects are statically applied at compile time.

```clojure
(m/defn ^{::m/aspects [timed (logged :info) traced]} do-a-thing
  "do-a-thing has nice documentation, obviously"
  [pojo stuff]
  (.doThatThing pojo stuff))
```

## Components

In this library, I have forked `clojure.core/defn`, splitting it into its two fundamental components: the parser and writer. The new parser outputs a `FnDef` record which is consumed by the writer. But between being parsed and being compiled, this record can easily be examined and/or modified by *aspect-defining* functions.

If you wish to fully exploit these capabilities, then use `morphe.core`. Every "aspect" is a function applied during the macro-expansion of `morphe.core/defn` that accepts a `FnDef` and returns a `FnDef`. But bear in mind that unless you're doing something very fancy, you won't need to know *anything* about what a `FnDef` looks like, because `morphe.core` provides several very handy utilities for defining aspect functions. Read more about `morphe.core` [here](docs/core.md).

If you already are using traditional functional advice, or if you simply prefer higher-order functions, or rather disfavor writing macros, or for any other reason: `morphe.functional` uses [higher-order functions](https://en.wikipedia.org/wiki/Higher-order_function) to modify the function you are defining. This is very similar in concept to [test fixtures in clojure.test](https://clojure.github.io/clojure/clojure.test-api.html#clojure.test/use-fixtures). See example of `morphe.functional` in use [here](test/morphe/functional_test.cljc).

Finally, if you think this is all very great, but you want to define aspect-oriented `Records`, `Types`, `Gubbins`, or `Whatnots`, this library has you covered. The definition of `morphe.core/defn` is simply:

```Clojure
(defmacro defn
  [fn-name & fdecl]
  (morphe.impl.core/make-def
      {:parser impl/parse-defn
       :->aspects impl/fn-def->aspects
       :compiler impl/fn-def->defn
       :&form &form
       :&env &env
       :def-name fn-name
       :decl fdecl}))
```

In short, you need to define a **parsing function** and a **writing function**, and you need to specify how to find the aspects in the parsed form (morphe's parser looks at the `:morphe.core/aspects` keyword in the def'ed symbol's metadata, but you could do whatever you want).

The main benefit, if it is a benefit, to re-using `morphe.impl.core`'s utilities is that it does [what it can](src/morphe/util/cljs.clj) to ensure your new `def` form is Clojurescript-friendly.

## Clojurescript support

When compiling to Clojurescript, the library should "just work" as long as you follow this rule: **every aspect must be defined in a `cljc` file**, and (if you don't want Clojurescript warnings) must be visible to the Clojurescript reader.

```Clojure
;; Clojurescript compiler will complain whenever you use this aspect:
#?(:clj (defn my-aspect [fn-def] ...))

;; Do this instead to appease it:
(defn my-aspect [fn-def]
  #?(:clj ...))
```

The reason for the above rule is that aspects are run at compile-time, and so are executed as **Clojure** functions; however, references to them occur in Clojurescript code, so the compiler must examine the Clojurescript analyzer's context in order successfully to discover the Clojure version of the aspect.

I am brand new to Clojurescript and I encountered some interesting issues getting this working, so there could be problems. Please do submit any bugs you discover.

As far as I know (I have not tested), morphe will **not** work in self-hosted Clojurescript. I suspect it may not take much work to make that happen, but I have not prioritized looking into this.

## Project History

This project began in 2016 as a personal library I called `defdef`, a silly experiment in abstracting the creation of all `def` forms. I came up with nothing remotely satisfying until I narrowed my focus to `defn` modifiers, at which point I realized it seemed very useful indeed. At that point I copied the library into a Clojure project at Workiva, where I developed it further as `defmodfn`, eventually open-sourcing it under [the current name](https://github.com/Workiva/morphe). I have since left Workiva, and that library is unmaintained, so now it lives here.

## Contributing

1. Branch and PR to master
2. Maintainers will review.

Guidelines:

 * [generally good style](https://github.com/bbatsov/clojure-style-guide)
 * [clear commit messages](https://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html)
 * tests where appropriate

#### Maintainers
- Timothy Dean <[galdre@gmail.com](mailto:galdre@gmail.com)>
